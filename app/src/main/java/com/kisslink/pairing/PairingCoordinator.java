package com.kisslink.pairing;

import android.content.Context;
import android.os.Build;
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

    public enum Phase { IDLE, LATCHED, LINKING, ELECTING, CONNECTING, CONNECTED }

    public interface Listener {
        @MainThread void onPhase(@NonNull Phase phase);
        /** Wi-Fi Direct 已連線;isGroupOwner 表本機是否為 GO。 */
        @MainThread void onPaired(boolean isGroupOwner);
        @MainThread void onError(@NonNull String message);
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

    // ══════════════════════════════════════════════════════════
    //  NFC latch 入口（由前景 Activity 經 binder 餵入）
    // ══════════════════════════════════════════════════════════

    /** reader 相位讀到對方 token → 本機當 BLE central。 */
    @MainThread
    public void onLatchedAsReader(@NonNull PairingToken peer) {
        if (finished || started) return; // 角色已鎖 → 忽略(含同一次貼合的反向 tag latch)
        started = true;
        this.peerToken = peer;
        listener.onPhase(Phase.LATCHED);
        observeWifi();
        listener.onPhase(Phase.LINKING);

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
        listener.onPhase(Phase.LATCHED);
        observeWifi();
        listener.onPhase(Phase.LINKING);

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
        if (finished || roleDecided || peerToken == null) return;
        roleDecided = true;
        iAmGroupOwner = localToken.shouldBeGroupOwner(peerToken);
        listener.onPhase(Phase.ELECTING);
        Log.i(TAG, "GO election: iAmGO=" + iAmGroupOwner
                + " (local=" + localToken + ", peer=" + peerToken + ")");

        if (iAmGroupOwner) {
            // 我是 GO：建群組,憑證就緒後經 BLE 交給對方。
            listener.onPhase(Phase.CONNECTING);
            wifi.createGroupAsGO();
            // 憑證由 credObserver 接手 → publishCredentialToPeer()
        } else {
            // 對方是 GO：等對方經 BLE 送憑證(onCredentialFromPeer),收到才 connectAsClient。
            listener.onPhase(Phase.CONNECTING);
        }
    }

    /** 我是 GO,Wi-Fi 憑證就緒 → 經 BLE 把憑證送給對方。 */
    @MainThread
    private void publishCredentialToPeer(@NonNull GroupCredential cred) {
        if (bleClient != null) bleClient.publishCredential(cred);
        else if (bleServer != null) bleServer.publishCredential(cred);
        Log.i(TAG, "Credential sent to peer via BLE");
    }

    /** 對方是 GO,經 BLE 收到憑證 → 以 client 連線。 */
    @MainThread
    private void onCredentialFromPeer(@NonNull GroupCredential cred) {
        if (finished || iAmGroupOwner) return;
        Log.i(TAG, "Credential received from peer → connectAsClient");
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
                listener.onPhase(Phase.CONNECTED);
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
        Log.w(TAG, "Pairing failed: " + msg);
        stopBle();
        listener.onError(msg);
    }

    private void stopBle() {
        if (bleClient != null) { bleClient.stop(); bleClient = null; }
        if (bleServer != null) { bleServer.stop(); bleServer = null; }
    }

    /** 完整重置(第三台重連 / 取消):停 BLE、移除觀察者。Wi-Fi 的拆除由呼叫端決定。 */
    @MainThread
    public void reset() {
        finished = true;
        stopBle();
        if (stateObserver != null) { wifi.getState().removeObserver(stateObserver); stateObserver = null; }
        if (credObserver  != null) { wifi.getCredential().removeObserver(credObserver); credObserver = null; }
        Log.d(TAG, "Coordinator reset");
    }
}
