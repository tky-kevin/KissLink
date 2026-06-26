package com.kisslink.wifidirect;

/**
 * Wi-Fi Direct 連線狀態機。
 *
 * <pre>
 *  IDLE
 *   │
 *   ├─(GO)──► CREATING_GROUP ──► HOSTING ──► CONNECTED ──► DISCONNECTED
 *   │
 *   └─(Client)──► CONNECTING ──────────────► CONNECTED ──► DISCONNECTED
 *
 *  任何狀態發生錯誤 → ERROR → reset() → IDLE
 * </pre>
 */
public enum ConnectionState {

    /** 初始 / 已重置，可開始新的配對流程。 */
    IDLE,

    /** GO 端：正在向系統申請建立 Wi-Fi P2P Group。 */
    CREATING_GROUP,

    /** GO 端：Group 就緒，憑證已產生，等待對方透過 NFC 碰觸取得憑證。 */
    HOSTING,

    /** Client 端：正在透過 WifiNetworkSpecifier 連接 GO 的 SoftAP。 */
    CONNECTING,

    /** 雙方已成功建立 Wi-Fi Direct 連線，可進入傳輸畫面。 */
    CONNECTED,

    /** 連線中斷（對方離開 / Wi-Fi 關閉）。 */
    DISCONNECTED,

    /** 發生不可自動恢復的錯誤，需呼叫 reset() 後重試。 */
    ERROR
}
