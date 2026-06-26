package com.kisslink.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;

/**
 * Wi-Fi Direct 廣播事件的回調介面。
 *
 * <p>{@link WifiDirectReceiver} 依賴此介面而非 {@link WifiDirectManager} 具體類別，
 * 使 Receiver 的行為可在測試中透過假實作（Fake）獨立驗證，
 * 無需啟動真實的 WifiP2pManager。
 */
interface WifiDirectEventCallback {

    /** Wi-Fi P2P 功能是否可用。 */
    void onP2pStateChanged(boolean enabled);

    /**
     * WIFI_P2P_CONNECTION_CHANGED_ACTION 廣播觸發。
     *
     * <p>不從廣播 Extra 取 WifiP2pInfo（資料可能過期），
     * 由 {@link WifiDirectManager} 內部呼叫 {@code requestConnectionInfo()} 取最新狀態。
     */
    void onConnectionChanged();

    /** P2P Group 連線資訊更新，由 requestConnectionInfo() 回呼觸發。 */
    void onConnectionInfoAvailable(WifiP2pInfo info);

    /** P2P Group 解散或連線中斷。 */
    void onDisconnected();

    /** 本機裝置資訊變更。 */
    void onThisDeviceChanged(WifiP2pDevice device);
}
