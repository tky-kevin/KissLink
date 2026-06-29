package com.kisslink.transfer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import com.kisslink.pairing.PairingCoordinator;
import com.kisslink.pairing.PairingToken;

/**
 * Manages session state and peer identity. FileTransferService delegates state transitions here for
 * a clean single source of truth.
 */
public final class SessionManager {

    private static final String TAG = "SessionManager";

    private final MutableLiveData<SessionState> sessionLd =
            new MutableLiveData<>(SessionState.idle());

    // ── Session core ──────────────────────────────────────────
    private int sessionGen = 0;
    private boolean pendingReset = false;

    // ── Peer identity ─────────────────────────────────────────
    // volatile：在主執行緒（onPaired / clearPeerIdentity）寫入，但 currentPeerName() 會被
    // PeerConnection 的 reader/sender 執行緒經 FileTransferService.onItemCompleted 讀取，故需可見性保證。
    @Nullable private volatile PairingToken connectedPeerToken;
    @Nullable private volatile String peerNameFromHello;
    @Nullable private volatile byte[] peerAvatarBytes;
    @Nullable private volatile PairingToken pendingSwitchPeer;

    // ── Progress bridging ─────────────────────────────────────
    @Nullable private LiveData<TransferProgress> peerProgressSrc;
    @Nullable private Observer<TransferProgress> peerProgressObs;

    public LiveData<SessionState> getState() {
        return sessionLd;
    }

    public int getSessionGen() {
        return sessionGen;
    }

    public boolean isPendingReset() {
        return pendingReset;
    }

    /** 從 sessionLd 派生，消除獨立布林值的同步窗口。 */
    public boolean isTransferring() {
        SessionState current = sessionLd.getValue();
        return current != null && current.phase == SessionState.Phase.TRANSFERRING;
    }

    @Nullable
    public PairingToken getConnectedPeerToken() {
        return connectedPeerToken;
    }

    @Nullable
    public PairingToken getPendingSwitchPeer() {
        return pendingSwitchPeer;
    }

    public boolean hasPendingSwitch() {
        return pendingSwitchPeer != null;
    }

    /** 清掉「傳完再切換」的待切換對象（自動切換實作前，傳輸完成後呼叫以免殘留 stale 狀態）。 */
    public void clearPendingSwitch() {
        pendingSwitchPeer = null;
    }

    /** 目前已連線對方的顯示名稱（HELLO 名片優先，其次 token）。 */
    @Nullable
    public String currentPeerName() {
        if (peerNameFromHello != null && !peerNameFromHello.isEmpty()) return peerNameFromHello;
        return connectedPeerToken != null && !connectedPeerToken.deviceName.isEmpty()
                ? connectedPeerToken.deviceName
                : null;
    }

    @Nullable
    public String getPeerNameFromHello() {
        return peerNameFromHello;
    }

    @Nullable
    public byte[] getPeerAvatarBytes() {
        return peerAvatarBytes;
    }

    // ══════════════════════════════════════════════════════════
    //  State transitions
    // ══════════════════════════════════════════════════════════

    public void transitionTo(@NonNull SessionState state) {
        publish(state);
    }

    // ── 語意化轉移：呼叫端表達意圖，SessionState 的建構收斂於此（單一寫入者）──
    public void toIdle() {
        publish(SessionState.idle());
    }

    public void toResetting() {
        publish(SessionState.of(SessionState.Phase.RESETTING));
    }

    public void toConnected() {
        publish(SessionState.of(SessionState.Phase.CONNECTED));
    }

    public void toError(@NonNull String msg) {
        publish(SessionState.error(msg));
    }

    public void onPhase(@NonNull PairingCoordinator.Phase phase, int currentGen) {
        if (currentGen != sessionGen) return;
        publish(mapPhase(phase));
    }

    public void onPaired(
            boolean groupOwner, int currentGen, @NonNull PairingCoordinator coordinator) {
        if (currentGen != sessionGen) return;
        connectedPeerToken = coordinator.peerToken();
        // P2P 群組已成形,進入「建立 TCP 通道」視窗(socket 仍在背景建,peer 尚未 alive)。
        // 與 Wi-Fi Direct 群組成形(CONNECTING)區隔,讓階段文字能顯示到 TCP 這步。
        publish(SessionState.of(SessionState.Phase.SOCKETING));
    }

    public void onError(@NonNull String message, int currentGen) {
        if (currentGen != sessionGen) return;
        publish(SessionState.error(message));
    }

    /**
     * Publish a session state to LiveData safely from any thread. LiveData.setValue() must run on
     * the main thread; PeerConnector callbacks (peer-accept / peer-connect threads) reach us off
     * the main thread, so we fall back to postValue there. Main-thread callers keep synchronous
     * setValue so existing ordering/test semantics are preserved.
     */
    private void publish(@NonNull SessionState state) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sessionLd.setValue(state);
        } else {
            sessionLd.postValue(state);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  NFC latch decision
    // ══════════════════════════════════════════════════════════

    /**
     * NFC latch 單一序列化入口。
     *
     * @return true if the latch should be delivered (clean state or post-settle), false if ignored
     *     (in-progress, same-peer resume, or queued switch).
     */
    @MainThread
    public LatchResult handleLatch(
            @Nullable PairingToken tappedPeer,
            @NonNull PairingCoordinator coordinator,
            boolean isPeerAlive) {
        if (coordinator.hasStarted() && !coordinator.isFinished()) {
            return LatchResult.IGNORED;
        }

        if (isPeerAlive) {
            boolean samePeer = (tappedPeer == null) || tappedPeer.sameSession(connectedPeerToken);
            if (samePeer) {
                sessionLd.setValue(SessionState.of(SessionState.Phase.CONNECTED));
                Log.i(TAG, "Same-peer tap while connected → resume");
                return LatchResult.RESUME;
            }
            if (isTransferring()) {
                pendingSwitchPeer = tappedPeer;
                Log.i(TAG, "New peer while transferring → queued switch");
                return LatchResult.QUEUED_SWITCH;
            }
        }
        return LatchResult.PROCEED;
    }

    public enum LatchResult {
        IGNORED, // Coordinator in progress
        RESUME, // Same peer, already connected
        QUEUED_SWITCH, // New peer, transfer in progress
        PROCEED // Clean or dirty state, proceed with latch
    }

    // ══════════════════════════════════════════════════════════
    //  Progress bridging
    // ══════════════════════════════════════════════════════════

    public void setupProgressObserver(
            @NonNull LiveData<TransferProgress> progressSrc,
            @NonNull Handler mainHandler,
            @NonNull Runnable onTransferEnded) {
        teardownProgressObserver(mainHandler);
        peerProgressSrc = progressSrc;
        peerProgressObs =
                tp -> {
                    if (tp == null) return;
                    boolean wasTransferring = isTransferring();
                    sessionLd.setValue(mapTransfer(tp));
                    boolean nowTransferring = (tp.phase == TransferProgress.Phase.TRANSFERRING);
                    if (wasTransferring && !nowTransferring) onTransferEnded.run();
                };
        mainHandler.post(
                () -> {
                    if (peerProgressSrc != null && peerProgressObs != null)
                        peerProgressSrc.observeForever(peerProgressObs);
                });
    }

    public void teardownProgressObserver(@NonNull Handler mainHandler) {
        if (peerProgressSrc != null && peerProgressObs != null) {
            final LiveData<TransferProgress> src = peerProgressSrc;
            final Observer<TransferProgress> obs = peerProgressObs;
            mainHandler.post(() -> src.removeObserver(obs));
        }
        peerProgressSrc = null;
        peerProgressObs = null;
    }

    // ══════════════════════════════════════════════════════════
    //  Identity management
    // ══════════════════════════════════════════════════════════

    public void setPeerProfile(@Nullable String name, @Nullable byte[] avatar) {
        peerNameFromHello = name;
        peerAvatarBytes = avatar;
    }

    public void clearPeerIdentity() {
        connectedPeerToken = null;
        peerNameFromHello = null;
        peerAvatarBytes = null;
    }

    public void resetSession() {
        clearPeerIdentity();
        pendingSwitchPeer = null;
    }

    public int nextSessionGen() {
        return ++sessionGen;
    }

    // ══════════════════════════════════════════════════════════
    //  Static helpers
    // ══════════════════════════════════════════════════════════

    /** 配對協調器相位 → session 狀態。跨 enum 轉譯政策的單一所在地。 */
    static SessionState mapPhase(PairingCoordinator.Phase p) {
        if (p == null) return SessionState.idle();
        switch (p) {
            case LATCHED:
                return SessionState.of(SessionState.Phase.PAIRING_LATCHED);
            case LINKING:
                return SessionState.of(SessionState.Phase.PAIRING_LINKING);
            case ELECTING:
                return SessionState.of(SessionState.Phase.PAIRING_ELECTING);
            case CONNECTING:
                return SessionState.of(SessionState.Phase.CONNECTING);
            case CONNECTED:
                return SessionState.of(SessionState.Phase.CONNECTED);
            case IDLE:
            default:
                return SessionState.idle();
        }
    }

    /** 傳輸進度 → session 狀態（含進度酬載）。 */
    static SessionState mapTransfer(TransferProgress tp) {
        if (tp == null) return SessionState.idle();
        switch (tp.phase) {
            case CONNECTED:
                return new SessionState(SessionState.Phase.CONNECTED, null, tp);
            case TRANSFERRING:
                return new SessionState(SessionState.Phase.TRANSFERRING, null, tp);
            case FILE_DONE:
                return new SessionState(SessionState.Phase.FILE_DONE, null, tp);
            case ALL_DONE:
                return new SessionState(SessionState.Phase.ALL_DONE, null, tp);
            case CANCELLED:
                return new SessionState(SessionState.Phase.CANCELLED, null, tp);
            case ERROR:
                return new SessionState(SessionState.Phase.ERROR, tp.errorMessage, tp);
            default:
                return new SessionState(SessionState.Phase.CONNECTING, null, null);
        }
    }
}
