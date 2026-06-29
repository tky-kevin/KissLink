package com.kisslink.transfer;

/**
 * UI 面向的單一 session 狀態——<b>純不可變值物件</b>(phase + error + progress)。
 *
 * <p>本類別<b>只</b>承載狀態值與對自身 phase 的判斷;<b>不</b>含跨子系統 enum 的轉譯政策 (那是「誰擁有狀態機」的責任,收斂在 {@link
 * SessionManager})。子系統各有其內部 FSM ({@link com.kisslink.pairing.PairingCoordinator.Phase}、{@link
 * com.kisslink.wifidirect.ConnectionState}、 {@link TransferProgress.Phase}),由 {@link
 * SessionManager} 統一映射為本狀態並發布—— 單一真相、單一寫入者。
 */
public final class SessionState {

    public enum Phase {
        RESETTING,
        IDLE,
        PAIRING_LATCHED,
        PAIRING_LINKING,
        PAIRING_ELECTING,
        CONNECTING,
        SOCKETING,
        CONNECTED,
        TRANSFERRING,
        FILE_DONE,
        ALL_DONE,
        CANCELLED,
        ERROR
    }

    public final Phase phase;
    public final String error;
    public final TransferProgress progress;

    public SessionState(Phase phase, String error, TransferProgress progress) {
        this.phase = phase;
        this.error = error;
        this.progress = progress;
    }

    public boolean isError() {
        return phase == Phase.ERROR;
    }

    public boolean isPairing() {
        return phase == Phase.RESETTING
                || phase == Phase.PAIRING_LATCHED
                || phase == Phase.PAIRING_LINKING
                || phase == Phase.PAIRING_ELECTING
                || phase == Phase.CONNECTING
                || phase == Phase.SOCKETING;
    }

    public boolean isTransferStartedOrConnected() {
        return phase == Phase.CONNECTED
                || phase == Phase.TRANSFERRING
                || phase == Phase.FILE_DONE
                || phase == Phase.ALL_DONE;
    }

    // ── 值建構（同型別便利工廠；跨 enum 轉譯不在此，見 SessionManager）──

    public static SessionState idle() {
        return new SessionState(Phase.IDLE, null, null);
    }

    public static SessionState error(String msg) {
        return new SessionState(Phase.ERROR, msg, null);
    }

    public static SessionState of(Phase p) {
        return new SessionState(p, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionState)) return false;
        SessionState that = (SessionState) o;
        return phase == that.phase
                && (error != null ? error.equals(that.error) : that.error == null);
    }

    @Override
    public int hashCode() {
        return 31 * phase.hashCode() + (error != null ? error.hashCode() : 0);
    }
}
