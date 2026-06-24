package com.kisslink.pairing;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.Observer;

import com.kisslink.model.GroupCredential;
import com.kisslink.pairing.ble.BleCredentialClient;
import com.kisslink.pairing.ble.BleCredentialServer;
import com.kisslink.wifidirect.ConnectionState;
import com.kisslink.wifidirect.WifiDirectManager;

/**
 * 配對協調器——一次「碰觸 → 連線」的中樞,串接三層:
 * <pre>
 *   NFC latch(方案A,Activity 驅動)
 *        │  reader：onLatchedAsReader(peerToken)         tag：onLatchedAsTag()
 *        ▼
 *   BLE 側通道(方案B2)
 *        reader = BLE central（掃 peer nonce、寫自身 token、收/送憑證）
 *        tag    = BLE peripheral（廣播 nonce、收 central token、送/收憑證）
 *        │  兩邊湊齊兩份 token
 *        ▼
 *   GO 選舉（{@link PairingToken#shouldBeGroupOwner} 決定性比較）
 *        GO  → WifiDirectManager.createGroupAsGO() → 憑證就緒 → 經 BLE 交給對方
 *        非GO → 經 BLE 收到憑證 → WifiDirectManager.connectAsClient()
 *        ▼
 *   Wi-Fi Direct CONNECTED → {@link Listener#onPaired}
 * </pre>
 *
 * <p>本類別不持有 Activity,可駐留於 Service。NFC 切換需前景 Activity,故由畫面持有
 * {@link NfcPairingController},latch 後把結果經 binder 餵進
 * {@link #onLatchedAsReader}/{@link #onLatchedAsTag}。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class PairingCoordinator {

    private static final String TAG = "PairingCoordinator";

    /**
     * PAIRSEQ 配對序列診斷：預設靜默（不寫入 log），排查時以
     * {@code adb shell setprop log.tag.PairingCoordinator DEBUG} 即可叫出，免重編譯。
     */
    private static void seq(String msg) {
        PairingFlightRecorder.seq(TAG, msg);
    }

    public enum Phase { IDLE, LATCHED, LINKING, ELECTING, CONNECTING, CONNECTED }

    public interface Listener {
        @MainThread void onPhase(@NonNull Phase phase);
        /** Wi-Fi Direct 已連線;isGroupOwner 表本機是否為 GO。 */
        @MainThread void onPaired(boolean isGroupOwner);
        @MainThread void onError(@NonNull String message);
        /**
         * 兩台都被 2.4GHz STA 綁住,GO 選舉無法挑出 5GHz 主機 → 傳輸會被鎖在 2.4GHz。
         * 非錯誤,僅提示使用者改善連線(改連 5GHz 或關 Wi-Fi)。預設不處理。
         */
        @MainThread default void onSlowLinkWarning() {}
    }

    private final Context context;
    private final WifiDirectManager wifi;
    private final Listener listener;

    private final PairingToken localToken;
    @Nullable private PairingToken peerToken;
    private boolean iAmGroupOwner;
    private boolean roleDecided = false;
    private boolean finished    = false;
    private boolean started     = false; // 首次 latch 即鎖定;之後忽略任何 latch(含反向角色)

    @Nullable private BleCredentialServer bleServer; // tag 角色
    @Nullable private BleCredentialClient bleClient; // reader 角色

    @Nullable private Observer<ConnectionState> stateObserver;
    @Nullable private Observer<GroupCredential> credObserver;

    // ── 每階段逾時看門狗 ───────────────────────────────────────
    // 每次相位推進就重新計時;某一階段卡住超過預算 → fail() 帶該階段專屬文字。
    // 這填補了子元件各自逾時的縫隙(如 BLE 在 GATT 連上後即取消自身逾時、
    // 非 GO 端等對方憑證時根本沒人計時、GO 端 HOSTING 等 client 也無逾時)。
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    @Nullable private Runnable watchdogRunnable;

    public PairingCoordinator(@NonNull Context ctx, @NonNull WifiDirectManager wifi,
                              @NonNull Listener listener) {
        this.context  = ctx.getApplicationContext();
        this.wifi     = wifi;
        this.listener = listener;
        // 與各前景畫面 HCE 廣播的 token 同源,確保「對方讀到的」=「本機選舉用的」。
        this.localToken = LocalPairing.current();
    }

    /** 本機這場配對的 token(供 NfcPairingController 寫進 HCE)。 */
    @NonNull public PairingToken localToken() { return localToken; }

    /** 本場是否已結束(已連線或失敗)。供上層判斷是否需要重開新一場。 */
    public boolean isFinished() { return finished; }

    /** 是否已收到第一次 latch 並鎖定角色。已 started 的場不再接受新 latch。 */
    public boolean hasStarted() { return started; }

    /** 本場對方的 token(供 resume / 新手機判別「同對象 vs 新對象」)。 */
    @Nullable public PairingToken peerToken() { return peerToken; }

    // ══════════════════════════════════════════════════════════
    //  NFC latch 入口（由前景 Activity 經 binder 餵入）
    // ══════════════════════════════════════════════════════════

    /** reader 相位讀到對方 token → 本機當 BLE central。 */
    @MainThread
    public void onLatchedAsReader(@NonNull PairingToken peer) {
        if (finished || started) return; // 角色已鎖 → 忽略(含同一次貼合的反向 tag latch)
        started = true;
        seq("latched as READER (BLE central), peer=" + peer);
        this.peerToken = peer;
        emit(Phase.LATCHED);
        observeWifi();
        emit(Phase.LINKING);

        bleClient = new BleCredentialClient(context, new BleCredentialClient.Callback() {
            @Override public void onReady() {
                // central 已把自身 token 寫給 peripheral → 兩邊皆有兩份 token,可選舉。
                decideRoleAndProceed();
            }
            @Override public void onCredentialReceived(@NonNull GroupCredential cred) {
                onCredentialFromPeer(cred);
            }
            @Override public void onError(@NonNull String m) { fail(m); }
        });
        bleClient.start(peer.nonce, localToken);
    }

    /** 自己 HCE 被讀 → 本機當 BLE peripheral。 */
    @MainThread
    public void onLatchedAsTag() {
        if (finished || started) return; // 角色已鎖 → 忽略(含同一次貼合的反向 reader latch)
        started = true;
        seq("latched as TAG (BLE peripheral)");
        emit(Phase.LATCHED);
        observeWifi();
        emit(Phase.LINKING);

        bleServer = new BleCredentialServer(context, new BleCredentialServer.Callback() {
            @Override public void onPeerToken(@NonNull PairingToken peer) {
                // central 寫來它的 token → 兩邊皆有兩份 token,可選舉。
                peerToken = peer;
                decideRoleAndProceed();
            }
            @Override public void onCredentialReceived(@NonNull GroupCredential cred) {
                onCredentialFromPeer(cred);
            }
            @Override public void onError(@NonNull String m) { fail(m); }
        });
        bleServer.start(localToken);
    }

    // ══════════════════════════════════════════════════════════
    //  GO 選舉 + 連線
    // ══════════════════════════════════════════════════════════

    @MainThread
    private void decideRoleAndProceed() {
        if (finished || roleDecided || peerToken == null) {
            // 兩份 token 是否都到齊的關鍵診斷：peerToken==null 代表對方 token 還沒經 BLE 送達。
            seq("decideRole skipped (finished=" + finished
                    + " roleDecided=" + roleDecided + " havePeerToken=" + (peerToken != null) + ")");
            return;
        }
        roleDecided = true;
        iAmGroupOwner = localToken.shouldBeGroupOwner(peerToken);
        emit(Phase.ELECTING);
        seq("GO election: iAmGO=" + iAmGroupOwner
                + " (local=" + localToken + ", peer=" + peerToken + ")");

        // 任一台被 2.4GHz STA 綁住 → 群組只能落在 2.4GHz(連線優先),傳輸偏慢,提示使用者。
        if (!localToken.canHost5G || !peerToken.canHost5G) {
            Log.w(TAG, "A peer is pinned to 2.4GHz STA → group limited to 2.4GHz (slow)");
            listener.onSlowLinkWarning();
        }

        if (iAmGroupOwner) {
            // 我是 GO：建群組,憑證就緒後經 BLE 交給對方。
            emit(Phase.CONNECTING);
            wifi.createGroupAsGO();
            // 憑證由 credObserver 接手 → publishCredentialToPeer()
        } else {
            // 對方是 GO：等對方經 BLE 送憑證(onCredentialFromPeer),收到才 connectAsClient。
            emit(Phase.CONNECTING);
            // central 端啟動憑證讀取後備:GO 的 notify 若因 race/丟包沒到,改主動 READ 補上,
            // 杜絕重連時「GO 已送憑證、client 沒收到 → 卡在 Wi-Fi Direct 連線逾時」。
            if (bleClient != null) bleClient.startCredentialReadFallback();
        }
    }

    /** 我是 GO,Wi-Fi 憑證就緒 → 經 BLE 把憑證送給對方。 */
    @MainThread
    private void publishCredentialToPeer(@NonNull GroupCredential cred) {
        if (bleClient != null) bleClient.publishCredential(cred);
        else if (bleServer != null) bleServer.publishCredential(cred);
        seq("credential sent to peer via BLE (I am GO)");
    }

    /** 對方是 GO,經 BLE 收到憑證 → 以 client 連線。 */
    @MainThread
    private void onCredentialFromPeer(@NonNull GroupCredential cred) {
        if (finished || iAmGroupOwner) return;
        seq("credential received from peer → connectAsClient (I am client)");
        wifi.connectAsClient(cred);
    }

    // ══════════════════════════════════════════════════════════
    //  Wi-Fi Direct 狀態觀察
    // ══════════════════════════════════════════════════════════

    @MainThread
    private void observeWifi() {
        if (stateObserver != null) return;
        stateObserver = state -> {
            if (finished) return;
            if (state == ConnectionState.CONNECTED) {
                finished = true;
                emit(Phase.CONNECTED);
                listener.onPaired(iAmGroupOwner);
                stopBle(); // 憑證已交付、連線已建立,BLE 任務完成
            } else if (state == ConnectionState.ERROR) {
                fail("Wi-Fi Direct 連線失敗");
            }
        };
        wifi.getState().observeForever(stateObserver);

        credObserver = cred -> {
            if (finished || cred == null) return;
            if (iAmGroupOwner) publishCredentialToPeer(cred);
        };
        wifi.getCredential().observeForever(credObserver);
    }

    // ══════════════════════════════════════════════════════════
    //  收尾 / 重置（第三台重連用）
    // ══════════════════════════════════════════════════════════

    @MainThread
    private void fail(@NonNull String msg) {
        if (finished) return;
        finished = true;
        cancelWatchdog();
        PairingFlightRecorder.event(TAG, "Pairing failed: " + msg);
        PairingFlightRecorder.dump(context, msg); // 落檔前 buffer 尚含本場完整序列，先 dump 再 stopBle
        stopBle();
        listener.onError(msg);
    }

    /**
     * 輕量中斷(使用者點階段文字「中斷重來」):停掉進行中的 BLE、標記本場結束,
     * 但<b>不</b>移除 Wi-Fi 觀察者、<b>不</b>清 HCE token、<b>不</b>拆 Wi-Fi 群組。
     * 與 {@link #fail} 的差別:不發 {@link Listener#onError}(不顯示失敗文字),由上層直接回待機。
     * Wi-Fi 群組/coordinator 的重建交給下一次貼合的 dirty 重建流程(那時才走 reset + 沉澱)。
     */
    @MainThread
    public void cancelLightweight() {
        if (finished) return;
        finished = true;
        cancelWatchdog();
        stopBle();
        Log.d(TAG, "Coordinator cancelled (lightweight; wifi/HCE untouched)");
    }

    private void stopBle() {
        if (bleClient != null) { bleClient.stop(); bleClient = null; }
        if (bleServer != null) { bleServer.stop(); bleServer = null; }
    }

    /** 完整重置(第三台重連 / 取消):停 BLE、移除觀察者。Wi-Fi 的拆除由呼叫端決定。 */
    @MainThread
    public void reset() {
        finished = true;
        cancelWatchdog();
        stopBle();
        if (stateObserver != null) { wifi.getState().removeObserver(stateObserver); stateObserver = null; }
        if (credObserver  != null) { wifi.getCredential().removeObserver(credObserver); credObserver = null; }
        Log.d(TAG, "Coordinator reset");
    }

    // ══════════════════════════════════════════════════════════
    //  相位推進 + 每階段逾時看門狗
    // ══════════════════════════════════════════════════════════

    /** 推進相位:通知上層並重新武裝該階段的逾時看門狗(已結束則不再推進)。 */
    @MainThread
    private void emit(@NonNull Phase phase) {
        if (finished && phase != Phase.CONNECTED) return;
        seq("phase → " + phase);
        listener.onPhase(phase);
        armWatchdog(phase);
    }

    /** 依目前階段設定逾時:逾時即 fail() 帶該階段專屬文字,讓 UI 進入「可再碰一下重連」的失敗態。 */
    @MainThread
    private void armWatchdog(@NonNull Phase phase) {
        cancelWatchdog();
        final long budgetMs;
        final String msg;
        switch (phase) {
            case LATCHED:    budgetMs =  8_000; msg = "配對逾時,請再碰一下重試"; break;
            case LINKING:    budgetMs = 15_000; msg = "BLE 連線逾時,請再碰一下重試"; break;
            case ELECTING:   budgetMs =  8_000; msg = "配對協商逾時,請再碰一下重試"; break;
            case CONNECTING: budgetMs = 25_000; msg = "Wi-Fi Direct 連線逾時,請再碰一下重試"; break;
            default:         return; // IDLE / CONNECTED：不設逾時
        }
        watchdogRunnable = () -> {
            watchdogRunnable = null;
            PairingFlightRecorder.event(TAG, "watchdog FIRED at phase " + phase
                    + " (budget " + budgetMs + "ms) → fail");
            fail(msg);
        };
        watchdog.postDelayed(watchdogRunnable, budgetMs);
    }

    private void cancelWatchdog() {
        if (watchdogRunnable != null) {
            watchdog.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }
}
