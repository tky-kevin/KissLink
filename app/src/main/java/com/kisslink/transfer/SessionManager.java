package com.kisslink.transfer;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.MainThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.kisslink.pairing.PairingCoordinator;
import com.kisslink.pairing.PairingToken;

/**
 * Manages session state and peer identity.
 * FileTransferService delegates state transitions here for a clean single source of truth.
 */
public final class SessionManager {

    private static final String TAG = "SessionManager";

    private final MutableLiveData<SessionState> sessionLd =
            new MutableLiveData<>(SessionState.idle());

    // ── Session core ──────────────────────────────────────────
    private int sessionGen = 0;
    private boolean pendingReset = false;

    // ── Peer identity ─────────────────────────────────────────
    @Nullable private PairingToken connectedPeerToken;
    @Nullable private volatile String peerNameFromHello;
    @Nullable private volatile byte[] peerAvatarBytes;
    @Nullable private PairingToken pendingSwitchPeer;
    private boolean transferring = false;

    // ── Progress bridging ─────────────────────────────────────
    @Nullable private LiveData<TransferProgress> peerProgressSrc;
    @Nullable private Observer<TransferProgress> peerProgressObs;

    public LiveData<SessionState> getState() { return sessionLd; }

    public int getSessionGen() { return sessionGen; }

    public boolean isPendingReset() { return pendingReset; }

    public boolean isTransferring() { return transferring; }

    @Nullable
    public PairingToken getConnectedPeerToken() { return connectedPeerToken; }

    @Nullable
    public PairingToken getPendingSwitchPeer() { return pendingSwitchPeer; }

    public boolean hasPendingSwitch() { return pendingSwitchPeer != null; }

    /** 目前已連線對方的顯示名稱（HELLO 名片優先，其次 token）。 */
    @Nullable
    public String currentPeerName() {
        if (peerNameFromHello != null && !peerNameFromHello.isEmpty()) return peerNameFromHello;
        return connectedPeerToken != null && !connectedPeerToken.deviceName.isEmpty()
                ? connectedPeerToken.deviceName : null;
    }

    @Nullable
    public String getPeerNameFromHello() { return peerNameFromHello; }

    @Nullable
    public byte[] getPeerAvatarBytes() { return peerAvatarBytes; }

    // ══════════════════════════════════════════════════════════
    //  State transitions
    // ══════════════════════════════════════════════════════════

    public void transitionTo(@NonNull SessionState state) {
        sessionLd.setValue(state);
    }

    public void onPhase(@NonNull PairingCoordinator.Phase phase, int currentGen) {
        if (currentGen != sessionGen) return;
        sessionLd.setValue(mapPhase(phase));
    }

    public void onPaired(boolean groupOwner, int currentGen,
                         @NonNull PairingCoordinator coordinator) {
        if (currentGen != sessionGen) return;
        connectedPeerToken = coordinator.peerToken();
        sessionLd.setValue(SessionState.of(SessionState.Phase.CONNECTING));
    }

    public void onError(@NonNull String message, int currentGen) {
        if (currentGen != sessionGen) return;
        sessionLd.setValue(SessionState.error(message));
    }

    // ══════════════════════════════════════════════════════════
    //  NFC latch decision
    // ══════════════════════════════════════════════════════════

    /**
     * NFC latch 單一序列化入口。
     *
     * @return true if the latch should be delivered (clean state or post-settle),
     *         false if ignored (in-progress, same-peer resume, or queued switch).
     */
    @MainThread
    public LatchResult handleLatch(@Nullable PairingToken tappedPeer,
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
            if (transferring) {
                pendingSwitchPeer = tappedPeer;
                Log.i(TAG, "New peer while transferring → queued switch");
                return LatchResult.QUEUED_SWITCH;
            }
        }
        return LatchResult.PROCEED;
    }

    public enum LatchResult {
        IGNORED,        // Coordinator in progress
        RESUME,         // Same peer, already connected
        QUEUED_SWITCH,  // New peer, transfer in progress
        PROCEED         // Clean or dirty state, proceed with latch
    }

    // ══════════════════════════════════════════════════════════
    //  Progress bridging
    // ══════════════════════════════════════════════════════════

    public void setupProgressObserver(@NonNull LiveData<TransferProgress> progressSrc,
                                      @NonNull Handler mainHandler,
                                      @NonNull Runnable onTransferEnded) {
        teardownProgressObserver(mainHandler);
        peerProgressSrc = progressSrc;
        peerProgressObs = tp -> {
            if (tp == null) return;
            sessionLd.setValue(SessionState.fromTransfer(tp));
            boolean now = (tp.phase == TransferProgress.Phase.TRANSFERRING);
            boolean ended = transferring && !now;
            transferring = now;
            if (ended) onTransferEnded.run();
        };
        mainHandler.post(() -> {
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
        transferring = false;
    }

    public int nextSessionGen() {
        return ++sessionGen;
    }

    // ══════════════════════════════════════════════════════════
    //  Static helpers
    // ══════════════════════════════════════════════════════════

    static SessionState mapPhase(PairingCoordinator.Phase p) {
        switch (p) {
            case LATCHED:    return SessionState.of(SessionState.Phase.PAIRING_LATCHED);
            case LINKING:    return SessionState.of(SessionState.Phase.PAIRING_LINKING);
            case ELECTING:   return SessionState.of(SessionState.Phase.PAIRING_ELECTING);
            case CONNECTING: return SessionState.of(SessionState.Phase.CONNECTING);
            case CONNECTED:  return SessionState.of(SessionState.Phase.CONNECTED);
            case IDLE:
            default:         return SessionState.of(SessionState.Phase.IDLE);
        }
    }
}
