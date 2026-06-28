package com.kisslink.wifidirect;

import static com.kisslink.wifidirect.ConnectionState.CONNECTED;
import static com.kisslink.wifidirect.ConnectionState.CONNECTING;
import static com.kisslink.wifidirect.ConnectionState.CREATING_GROUP;
import static com.kisslink.wifidirect.ConnectionState.DISCONNECTED;
import static com.kisslink.wifidirect.ConnectionState.ERROR;
import static com.kisslink.wifidirect.ConnectionState.HOSTING;
import static com.kisslink.wifidirect.ConnectionState.IDLE;

import java.util.EnumSet;
import java.util.Set;

/**
 * 驅動 Wi-Fi Direct 狀態機的「意圖」事件。各角色控制器
 * （{@link GroupOwnerController}、{@link ClientConnector}、{@link GoDetectionPoller}、
 * 門面 {@link WifiDirectManager}）<b>不</b>直接寫狀態，而是呼叫
 * {@link WifiDirectCore#dispatch(WifiDirectEvent)} 投遞事件；由 {@code core} 作為唯一寫入者
 * 把事件映射到目標 {@link ConnectionState}，並對照 {@link #legalFrom} 驗證轉移合法性。
 *
 * <p>每個事件對應「目前程式碼某個 {@code setState(...)} 呼叫點」，target 與該呼叫點一致，
 * 故轉換為事件模型後行為不變；唯一新增的是非法來源狀態時寫入 FlightRecorder 的診斷紀錄。
 */
enum WifiDirectEvent {

    // ── Client 角色 ────────────────────────────────────────────
    /** Client 開始靜默連線（connectAsClient）。 */
    CLIENT_CONNECT_INITIATED(CONNECTING, EnumSet.of(IDLE)),
    /** Client 連線逾時（startTimeout 觸發）。 */
    CLIENT_CONNECT_TIMED_OUT(ERROR, EnumSet.of(CONNECTING)),
    /** Client connect() 終態失敗（已用盡暫時性重試）。 */
    CLIENT_CONNECT_FAILED(ERROR, EnumSet.of(CONNECTING)),
    /** Client：P2P 群組已形成，立即可開 TCP。 */
    CLIENT_GROUP_FORMED(CONNECTED, EnumSet.of(CONNECTING)),
    /** Client：成功綁定真正的 P2P 網路（路由優化，狀態已是 CONNECTED）。 */
    CLIENT_NETWORK_BOUND(CONNECTED, EnumSet.of(CONNECTING, CONNECTED)),
    /** Client：已綁定的 P2P 網路遺失。 */
    CLIENT_NETWORK_LOST(DISCONNECTED, EnumSet.of(CONNECTED)),

    // ── GO 角色 ────────────────────────────────────────────────
    /** GO 開始建立群組（createGroupAsGO）。 */
    GO_CREATE_INITIATED(CREATING_GROUP, EnumSet.of(IDLE)),
    /** GO 建群逾時（startTimeout 觸發）。 */
    GO_CREATE_TIMED_OUT(ERROR, EnumSet.of(CREATING_GROUP)),
    /** GO createGroup 終態失敗（已用盡暫時性重試）。 */
    GO_CREATE_FAILED(ERROR, EnumSet.of(CREATING_GROUP)),
    /** GO 無法從系統或 fallback 取得連線憑證。 */
    GO_CREDENTIAL_UNAVAILABLE(ERROR, EnumSet.of(CREATING_GROUP)),
    /** GO 群組就緒、憑證已發布，進入等待 Client 加入。 */
    GO_GROUP_READY(HOSTING, EnumSet.of(CREATING_GROUP)),
    /** GO 偵測到 Client 加入群組（GoDetectionPoller）。 */
    GO_CLIENT_DETECTED(CONNECTED, EnumSet.of(HOSTING)),

    // ── 共用 / 門面 ────────────────────────────────────────────
    /** P2P Channel 遺失並已重新初始化。 */
    CHANNEL_LOST(DISCONNECTED, EnumSet.allOf(ConnectionState.class)),
    /** 系統回報 Wi-Fi P2P 被關閉。 */
    P2P_DISABLED(DISCONNECTED, EnumSet.of(CONNECTED, HOSTING)),
    /** 連線在傳輸/等待中掉線。 */
    CONNECTION_DROPPED(DISCONNECTED, EnumSet.of(CONNECTED, HOSTING, CONNECTING)),
    /** 完整重置回 IDLE（ViewModel.onCleared / 取消）。 */
    RESET(IDLE, EnumSet.allOf(ConnectionState.class));

    final ConnectionState target;
    private final Set<ConnectionState> legalFrom;

    WifiDirectEvent(ConnectionState target, Set<ConnectionState> legalFrom) {
        this.target = target;
        this.legalFrom = legalFrom;
    }

    /** 從 {@code from} 狀態投遞本事件是否屬於已知合法轉移（僅供診斷紀錄，不阻擋）。 */
    boolean isLegalFrom(ConnectionState from) {
        return from != null && legalFrom.contains(from);
    }
}
