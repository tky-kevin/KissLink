package com.kisslink.transfer;

import com.kisslink.pairing.PairingCoordinator;
import com.kisslink.wifidirect.ConnectionState;

public final class SessionState {

    public enum Phase {
        RESETTING,
        IDLE, PAIRING_LATCHED, PAIRING_LINKING, PAIRING_ELECTING,
        CREATING_GROUP, HOSTING, CONNECTING, SOCKETING, CONNECTED,
        TRANSFERRING, FILE_DONE, ALL_DONE,
        CANCELLED, ERROR
    }

    public final Phase phase;
    public final String error;
    public final TransferProgress progress;

    public SessionState(Phase phase, String error, TransferProgress progress) {
        this.phase = phase;
        this.error = error;
        this.progress = progress;
    }

    public boolean isError() { return phase == Phase.ERROR; }

    public boolean isPairing() {
        return phase == Phase.RESETTING || phase == Phase.PAIRING_LATCHED
                || phase == Phase.PAIRING_LINKING || phase == Phase.PAIRING_ELECTING
                || phase == Phase.CREATING_GROUP || phase == Phase.HOSTING
                || phase == Phase.CONNECTING || phase == Phase.SOCKETING;
    }

    public boolean isTransferStartedOrConnected() {
        return phase == Phase.CONNECTED || phase == Phase.TRANSFERRING
                || phase == Phase.FILE_DONE || phase == Phase.ALL_DONE;
    }

    public static SessionState idle() {
        return new SessionState(Phase.IDLE, null, null);
    }

    public static SessionState error(String msg) {
        return new SessionState(Phase.ERROR, msg, null);
    }

    public static SessionState of(Phase p) {
        return new SessionState(p, null, null);
    }

    public static SessionState fromTransfer(TransferProgress tp) {
        if (tp == null) return idle();
        switch (tp.phase) {
            case CONNECTED:    return new SessionState(Phase.CONNECTED, null, tp);
            case TRANSFERRING: return new SessionState(Phase.TRANSFERRING, null, tp);
            case FILE_DONE:    return new SessionState(Phase.FILE_DONE, null, tp);
            case ALL_DONE:     return new SessionState(Phase.ALL_DONE, null, tp);
            case CANCELLED:    return new SessionState(Phase.CANCELLED, null, tp);
            case ERROR:        return new SessionState(Phase.ERROR, tp.errorMessage, tp);
            default:           return new SessionState(Phase.CONNECTING, null, null);
        }
    }

    public static SessionState fromConnection(ConnectionState cs) {
        if (cs == null) return idle();
        switch (cs) {
            case CREATING_GROUP: return of(Phase.CREATING_GROUP);
            case HOSTING:        return of(Phase.HOSTING);
            case CONNECTING:     return of(Phase.CONNECTING);
            case CONNECTED:      return of(Phase.CONNECTED);
            case DISCONNECTED:   return error("連線中斷，請重試");
            case ERROR:          return error("連線失敗，請重試");
            case IDLE:
            default:             return idle();
        }
    }

    public static SessionState mapPhase(PairingCoordinator.Phase p) {
        if (p == null) return idle();
        switch (p) {
            case LATCHED:    return of(Phase.PAIRING_LATCHED);
            case LINKING:    return of(Phase.PAIRING_LINKING);
            case ELECTING:   return of(Phase.PAIRING_ELECTING);
            case CONNECTING: return of(Phase.CONNECTING);
            case CONNECTED:  return of(Phase.CONNECTED);
            case IDLE:
            default:         return idle();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionState)) return false;
        SessionState that = (SessionState) o;
        return phase == that.phase && (error != null ? error.equals(that.error) : that.error == null);
    }

    @Override
    public int hashCode() {
        return 31 * phase.hashCode() + (error != null ? error.hashCode() : 0);
    }
}
