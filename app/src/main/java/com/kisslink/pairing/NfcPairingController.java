package com.kisslink.pairing;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;

import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.nfc.NfcForegroundHelper;

/**
 * 碰觸配對的 NFC 主控——<b>前景派發(foreground dispatch)+ 常駐 HCE</b>。
 *
 * <h3>為何不再用 reader/HCE 快速切換?</h3>
 * <p>實機 log 證實:兩台同時做 reader/listen 切換時,只要一方進入讀取、另一方剛好翻相位
 * (enable/disableReaderMode 會重置 NFC 控制器),正在進行的 APDU 連結就 LINK_LOSS,
 * NDEF 讀不完 → 對方退回系統 tech 派發 → 跳「選擇應用程式」。
 *
 * <p>改用「<b>不切換</b>」:HCE token 一直設好(本機隨時可被讀),同時用
 * {@code enableForegroundDispatch} 攔下「本機讀到的任何標籤」。前景派發優先於系統派發,
 * 因此不跳選擇器、不觸發冷啟動 deep link;讀取能完整跑完。誰讀誰被讀由 NFC 射頻層天然仲裁:
 * 一次貼合恰好一方為 reader(→ {@link Callback#onPeerToken})、一方為 tag(→ {@link Callback#onTagRead})。
 *
 * <h3>Activity 接線</h3>
 * <pre>
 *   onResume:    enable();  handleIntent(getIntent());
 *   onNewIntent: setIntent(i); handleIntent(i);
 *   onPause:     disable();
 * </pre>
 */
public class NfcPairingController {

    private static final String TAG = "NfcPairingController";

    public interface Callback {
        @MainThread void onPeerToken(@NonNull PairingToken peer);
        @MainThread void onTagRead();
        @MainThread void onError(@NonNull String message);
    }

    private final Activity activity;
    private final Callback callback;
    @Nullable private final NfcAdapter nfcAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean latched = false;

    public NfcPairingController(@NonNull Activity activity, @NonNull Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
    }

    /** 設定本機這場要廣播的 token(常駐寫進 HCE)。 */
    public void setLocalToken(@NonNull PairingToken token) {
        KissLinkHCEService.setActiveToken(token);
    }

    // ══════════════════════════════════════════════════════════
    //  enable / disable
    // ══════════════════════════════════════════════════════════

    @MainThread
    public void enable() {
        if (nfcAdapter == null) { callback.onError("此裝置不支援 NFC"); return; }
        if (!nfcAdapter.isEnabled()) { callback.onError("請先在設定中開啟 NFC"); return; }

        // 注意:不在此重設 latched。enable() 會被 onResume / onServiceConnected 多次呼叫,
        // 一旦 latch 成功就必須維持,避免重複觸發 onPeerToken/onTagRead。
        KissLinkHCEService.setOnTagReadListener(() -> handler.post(this::onHceRead));

        int piFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_MUTABLE : 0;
        Intent dispatch = new Intent(activity, activity.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(activity, 0, dispatch, piFlags);

        try {
            // filters/techLists 皆 null → 攔下所有標籤,優先於系統派發(不跳選擇器)。
            nfcAdapter.enableForegroundDispatch(activity, pi, null, null);
            Log.d(TAG, "Foreground dispatch enabled");
        } catch (Exception e) {
            Log.w(TAG, "enableForegroundDispatch failed: " + e.getMessage());
        }

        // 設定 preferred HCE service，避免 Samsung ConflictResolverActivity
        try {
            CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
            if (ce != null) {
                ce.setPreferredService(activity,
                        new ComponentName(activity, KissLinkHCEService.class));
                Log.d(TAG, "Preferred HCE service set");
            }
        } catch (Exception e) {
            Log.w(TAG, "setPreferredService failed: " + e.getMessage());
        }
    }

    @MainThread
    public void disable() {
        KissLinkHCEService.setOnTagReadListener(null);
        // 取消 preferred HCE service
        try {
            CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
            if (ce != null) ce.unsetPreferredService(activity);
        } catch (Exception e) {
            Log.w(TAG, "unsetPreferredService failed: " + e.getMessage());
        }
        try {
            if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(activity);
        } catch (Exception e) {
            Log.w(TAG, "disableForegroundDispatch failed: " + e.getMessage());
        }
    }

    @MainThread
    public void resetLatched() {
        this.latched = false;
    }

    // ══════════════════════════════════════════════════════════
    //  收到標籤(本機為 reader)
    // ══════════════════════════════════════════════════════════

    @MainThread
    public void handleIntent(@Nullable Intent intent) {
        if (intent == null || latched) return;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            return;
        }

        // 連上標籤,用 IsoDep 送 SELECT AID 讀對方 token(背景執行緒)
        Tag tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag.class);
        if (tag != null) readTagAsync(tag);
    }

    private void readTagAsync(@NonNull Tag tag) {
        new Thread(() -> {
            PairingToken peer = NfcForegroundHelper.readToken(tag);
            if (peer != null) handler.post(() -> deliverPeer(peer));
        }, "nfc-read").start();
    }

    @MainThread
    private void deliverPeer(@NonNull PairingToken peer) {
        if (latched) return;
        latched = true;
        Log.i(TAG, "Peer token read (role=reader): " + peer);
        callback.onPeerToken(peer);
    }

    @MainThread
    private void onHceRead() {
        if (latched) return;
        latched = true;
        Log.i(TAG, "Own tag read by peer (role=tag)");
        callback.onTagRead();
    }
}
