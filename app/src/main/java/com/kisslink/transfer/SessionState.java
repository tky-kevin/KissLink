package com.kisslink.transfer;

import androidx.annotation.Nullable;

import com.kisslink.wifidirect.ConnectionState;

/**
 * 一次傳輸 session 的「單一狀態快照」——UI 唯一需要觀察的對象。
 *
 * <p>過去 UI 要同時觀察三條通道（{@link ConnectionState} 連線狀態、
 * {@code LiveData<String>} 連線錯誤、{@link TransferProgress} 傳輸進度），容易漏接或重複處理。
 * 本類別把三者合併：{@link FileTransferService} 將連線層與傳輸層的事件整合成單一
 * {@code LiveData<SessionState>}，由 PairingActivity / TransferActivity 共同觀察。
 *
 * <p>不可變，可安全跨執行緒傳遞。
 */
public final class SessionState {

    public enum Phase {
        // ── 配對階段（碰觸 → BLE → 選舉）──
        IDLE, PAIRING_LATCHED, PAIRING_LINKING, PAIRING_ELECTING,
        // ── 連線階段 ──
        CREATING_GROUP, HOSTING, CONNECTING, CONNECTED,
        // ── 傳輸階段 ──
        TRANSFERRING, FILE_DONE, ALL_DONE,
        // ── 終態 ──
        CANCELLED, ERROR
    }

    public final Phase phase;
    /** 僅 {@link Phase#ERROR} 時有值。 */
    @Nullable public final String error;
    /** 傳輸細節，僅在傳輸階段（TRANSFERRING/FILE_DONE/ALL_DONE 及帶細節的 ERROR）非 null。 */
    @Nullable public final TransferProgress progress;

    private SessionState(Phase phase, @Nullable String error, @Nullable TransferProgress progress) {
        this.phase = phase;
        this.error = error;
        this.progress = progress;
    }

    public boolean isError() { return phase == Phase.ERROR; }

    /** 是否已進入（或越過）傳輸階段——UI 可據此判斷該離開配對畫面。 */
    public boolean isTransferStartedOrConnected() {
        return phase == Phase.CONNECTED
                || phase == Phase.TRANSFERRING
                || phase == Phase.FILE_DONE
                || phase == Phase.ALL_DONE;
    }

    /** 是否仍在配對/連線中（尚未可傳輸）。 */
    public boolean isPairing() {
        return phase == Phase.PAIRING_LATCHED
                || phase == Phase.PAIRING_LINKING
                || phase == Phase.PAIRING_ELECTING
                || phase == Phase.CREATING_GROUP
                || phase == Phase.HOSTING
                || phase == Phase.CONNECTING;
    }

    // ── 工廠 ────────────────────────────────────────────────────

    /** 以指定 Phase 建立（配對階段用）。 */
    public static SessionState of(Phase p) { return new SessionState(p, null, null); }

    static SessionState idle() { return new SessionState(Phase.IDLE, null, null); }

    static SessionState error(String msg) { return new SessionState(Phase.ERROR, msg, null); }

    /** 由 Wi-Fi Direct 連線狀態建立（傳輸尚未開始時的階段）。 */
    static SessionState fromConnection(@Nullable ConnectionState cs) {
        if (cs == null) return idle();
        switch (cs) {
            case CREATING_GROUP: return new SessionState(Phase.CREATING_GROUP, null, null);
            case HOSTING:        return new SessionState(Phase.HOSTING, null, null);
            case CONNECTING:     return new SessionState(Phase.CONNECTING, null, null);
            case CONNECTED:      return new SessionState(Phase.CONNECTED, null, null);
            case DISCONNECTED:   return new SessionState(Phase.ERROR, "連線中斷，請重試", null);
            case ERROR:          return new SessionState(Phase.ERROR, "連線失敗，請重試", null);
            case IDLE:
            default:             return idle();
        }
    }

    /** 由傳輸進度建立（傳輸層的事件優先於連線層）。 */
    static SessionState fromTransfer(TransferProgress tp) {
        switch (tp.phase) {
            case CONNECTED:    return new SessionState(Phase.CONNECTED, null, tp);
            case TRANSFERRING: return new SessionState(Phase.TRANSFERRING, null, tp);
            case FILE_DONE:    return new SessionState(Phase.FILE_DONE, null, tp);
            case ALL_DONE:     return new SessionState(Phase.ALL_DONE, null, tp);
            case CANCELLED:    return new SessionState(Phase.CANCELLED, null, tp);
            case ERROR:        return new SessionState(Phase.ERROR,
                                       tp.errorMessage != null ? tp.errorMessage : "傳輸失敗", tp);
            case WAITING:
            default:           return new SessionState(Phase.CONNECTING, null, null);
        }
    }
}
