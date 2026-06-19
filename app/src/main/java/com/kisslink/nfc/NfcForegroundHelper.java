package com.kisslink.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kisslink.pairing.LocalPairing;
import com.kisslink.pairing.PairingToken;

import java.nio.charset.StandardCharsets;

/**
 * 任何前景 Activity 的 NFC 碰觸配對主控:
 * <ul>
 *   <li>常駐把本機 token 寫進 HCE(自訂 AID),讓本機隨時是可被讀的 KissLink 標籤。</li>
 *   <li>{@code enableForegroundDispatch} 攔下本機讀到的標籤,優先於系統 dispatch(不跳選擇器)。</li>
 *   <li>{@code setPreferredService} 讓本機 HCE 在前景時優先(避免卡片模擬衝突)。</li>
 * </ul>
 * 讀到對方標籤後用 IsoDep 送 SELECT AID,取回對方 token。誰讀誰被讀由 NFC 射頻層仲裁。
 *
 * <pre>
 *   onResume:    onResume();
 *   onNewIntent: handleIntent(intent);
 *   onPause:     onPause();
 * </pre>
 */
public class NfcForegroundHelper {

    private static final String TAG = "NfcForegroundHelper";

    public interface Callback {
        @MainThread void onPeerToken(@NonNull PairingToken peer);
        @MainThread void onTagRead();
    }

    private final Activity activity;
    @Nullable private final NfcAdapter nfcAdapter;
    @Nullable private final Callback callback;
    private boolean latched = false;

    public NfcForegroundHelper(@NonNull Activity activity, @Nullable Callback callback) {
        this.activity = activity;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
        this.callback = callback;
    }

    @MainThread
    public void onResume() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) return;

        latched = false; // 回到前景 → 重新待命

        // 0. 設定本機 HCE 對外的 token(與 Coordinator 同源)——任何前景畫面都是有效標籤。
        //    先刷新 5GHz 開群組能力旗標，確保對方碰一下讀到的 token 反映最新 Wi-Fi 狀態。
        try {
            LocalPairing.setCanHost5G(
                    com.kisslink.wifidirect.WifiDirectManager.canHostFastGroup(activity));
            KissLinkHCEService.setActiveToken(LocalPairing.current());
        } catch (Exception e) {
            Log.w(TAG, "setActiveToken failed: " + e.getMessage());
        }

        // 1. 前景派發:攔下所有 NFC tag,不讓系統 dispatch。
        int piFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_MUTABLE : 0;
        Intent dispatch = new Intent(activity, activity.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(activity, 0, dispatch, piFlags);
        try {
            nfcAdapter.enableForegroundDispatch(activity, pi, null, null);
        } catch (Exception e) {
            Log.w(TAG, "enableForegroundDispatch failed: " + e.getMessage());
        }

        // 2. 前景優先 HCE,避免卡片模擬衝突。
        try {
            CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
            if (ce != null) {
                ce.setPreferredService(activity,
                        new ComponentName(activity, KissLinkHCEService.class));
            }
        } catch (Exception e) {
            Log.w(TAG, "setPreferredService failed: " + e.getMessage());
        }

        // 3. 監聽自己被讀取。
        KissLinkHCEService.setOnTagReadListener(() ->
                activity.runOnUiThread(this::fireTagRead));
    }

    @MainThread
    public void onPause() {
        KissLinkHCEService.setOnTagReadListener(null);
        if (nfcAdapter == null) return;
        try {
            CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
            if (ce != null) ce.unsetPreferredService(activity);
        } catch (Exception e) {
            Log.w(TAG, "unsetPreferredService failed: " + e.getMessage());
        }
        try {
            nfcAdapter.disableForegroundDispatch(activity);
        } catch (Exception e) {
            Log.w(TAG, "disableForegroundDispatch failed: " + e.getMessage());
        }
    }

    @MainThread
    private void fireTagRead() {
        if (latched || callback == null) return;
        latched = true;
        callback.onTagRead();
    }

    /** 處理前景派發送來的 NFC tag intent;用 IsoDep 讀對方 token。 */
    @MainThread
    public boolean handleIntent(@Nullable Intent intent) {
        if (intent == null || callback == null || latched) return false;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            return false;
        }
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return false;
        readTagAsync(tag);
        return true;
    }

    private void readTagAsync(@NonNull Tag tag) {
        new Thread(() -> {
            PairingToken t = readToken(tag);
            if (t != null && callback != null) {
                final PairingToken peer = t;
                activity.runOnUiThread(() -> {
                    if (latched) return;
                    latched = true;
                    callback.onPeerToken(peer);
                });
            }
        }, "nfc-fg-read").start();
    }

    /**
     * 對 HCE 送 SELECT AID,取回 token bytes 並解析。在背景執行緒呼叫。
     * 重試數次:碰觸瞬間射頻常不穩(transient「tag lost / connect 失敗」),快速重試多半能救回。
     */
    @Nullable
    public static PairingToken readToken(@NonNull Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) return null;
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    if (!iso.isConnected()) iso.connect();
                    iso.setTimeout(5000);
                    byte[] resp = iso.transceive(APDUHelper.buildSelectAidApdu());
                    byte[] payload = APDUHelper.extractPayload(resp);
                    if (payload != null) {
                        return PairingToken.fromUri(
                                Uri.parse(new String(payload, StandardCharsets.UTF_8)));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "readToken attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(60); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return null;
        } finally {
            try { iso.close(); } catch (Exception ignored) {}
        }
    }
}
