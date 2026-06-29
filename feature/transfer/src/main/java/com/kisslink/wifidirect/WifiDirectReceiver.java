package com.kisslink.wifidirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;
import androidx.core.content.IntentCompat;

/**
 * 監聽 Wi-Fi Direct 系統廣播，將事件轉發給 {@link WifiDirectEventCallback}。
 *
 * <p>依賴 {@link WifiDirectEventCallback} 介面而非具體的 {@link WifiDirectManager}， 使此類別在測試中可注入假實作（{@code
 * FakeWifiDirectEventCallback}）， 無需啟動真實的 WifiP2pManager。
 *
 * <p>生命週期由 {@link WifiDirectManager#registerReceiver} / {@link
 * WifiDirectManager#unregisterReceiver} 管理； 請在 Activity/Fragment 的 onResume/onPause 中呼叫。
 */
public class WifiDirectReceiver extends BroadcastReceiver {

    private static final String TAG = "WifiDirectReceiver";

    private final WifiDirectEventCallback callback;

    WifiDirectReceiver(WifiDirectEventCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "onReceive: " + action);

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    boolean enabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                    Log.d(TAG, "P2P enabled=" + enabled);
                    callback.onP2pStateChanged(enabled);
                    break;
                }

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                {
                    // 不直接使用 Extra（WifiP2pInfo 資料在 GO 端常常過期/不準確）。
                    // 交由 WifiDirectManager.onConnectionChanged() 呼叫
                    // requestConnectionInfo() 取最新狀態。
                    callback.onConnectionChanged();
                    break;
                }

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                {
                    WifiP2pDevice device =
                            IntentCompat.getParcelableExtra(
                                    intent,
                                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                    WifiP2pDevice.class);
                    if (device != null) callback.onThisDeviceChanged(device);
                    break;
                }

            default:
                Log.w(TAG, "Unhandled action: " + action);
        }
    }
}
