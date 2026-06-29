package com.kisslink.wifidirect;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.InetAddress;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@link WifiDirectReceiver} 的裝置端 Instrumented 測試。
 *
 * <p><b>執行方式</b>：需連接實體裝置或啟動模擬器。 Android Studio → 右鍵此檔案 → Run 'WifiDirectReceiverTest' 或終端機：{@code
 * ./gradlew connectedAndroidTest}
 *
 * <p>此測試使用 {@link FakeWifiDirectEventCallback} 替代真實的 {@link WifiDirectManager}， 僅驗證 Receiver 對廣播
 * Intent 的解析邏輯，不觸碰 Wi-Fi 硬體。
 */
@RunWith(AndroidJUnit4.class)
public class WifiDirectReceiverTest {

    // ── 假實作：記錄所有被呼叫的方法與參數 ──────────────────────

    /** 作為 {@link WifiDirectEventCallback} 的測試替身（Test Double）。 */
    static class FakeWifiDirectEventCallback implements WifiDirectEventCallback {

        boolean lastP2pEnabled = false;
        boolean p2pStateChangedCalled = false;
        boolean connectionChangedCalled = false; // 新增：onConnectionChanged()
        boolean connectionInfoCalled = false;
        boolean disconnectedCalled = false;
        boolean deviceChangedCalled = false;
        WifiP2pInfo lastInfo = null;
        WifiP2pDevice lastDevice = null;

        @Override
        public void onConnectionChanged() {
            connectionChangedCalled = true;
            // 測試中不呼叫真實的 requestConnectionInfo()，僅記錄被呼叫
        }

        @Override
        public void onP2pStateChanged(boolean enabled) {
            p2pStateChangedCalled = true;
            lastP2pEnabled = enabled;
        }

        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo info) {
            connectionInfoCalled = true;
            lastInfo = info;
        }

        @Override
        public void onDisconnected() {
            disconnectedCalled = true;
        }

        @Override
        public void onThisDeviceChanged(WifiP2pDevice device) {
            deviceChangedCalled = true;
            lastDevice = device;
        }

        void reset() {
            lastP2pEnabled = false;
            connectionChangedCalled = false;
            p2pStateChangedCalled =
                    connectionInfoCalled = disconnectedCalled = deviceChangedCalled = false;
            lastInfo = null;
            lastDevice = null;
        }
    }

    // ── 測試夾具 ──────────────────────────────────────────────

    private Context ctx;
    private FakeWifiDirectEventCallback fake;
    private WifiDirectReceiver receiver;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fake = new FakeWifiDirectEventCallback();
        receiver = new WifiDirectReceiver(fake);
    }

    // ══════════════════════════════════════════════════════════
    //  WIFI_P2P_STATE_CHANGED_ACTION
    // ══════════════════════════════════════════════════════════

    @Test
    public void p2pStateEnabled_callsCallback_withTrue() {
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_ENABLED);

        receiver.onReceive(ctx, intent);

        assertTrue("p2pStateChanged 應被呼叫", fake.p2pStateChangedCalled);
        assertTrue("enabled 應為 true", fake.lastP2pEnabled);
    }

    @Test
    public void p2pStateDisabled_callsCallback_withFalse() {
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED);

        receiver.onReceive(ctx, intent);

        assertTrue("p2pStateChanged 應被呼叫", fake.p2pStateChangedCalled);
        assertFalse("enabled 應為 false", fake.lastP2pEnabled);
    }

    @Test
    public void p2pStateMissingExtra_callsCallback_withFalse() {
        // Extra 缺失時 getIntExtra 預設回傳 -1，不等於 ENABLED
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);

        receiver.onReceive(ctx, intent);

        assertTrue(fake.p2pStateChangedCalled);
        assertFalse(fake.lastP2pEnabled);
    }

    // ══════════════════════════════════════════════════════════
    //  WIFI_P2P_CONNECTION_CHANGED_ACTION
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    //  WIFI_P2P_CONNECTION_CHANGED_ACTION
    //  （修正後：Receiver 只呼叫 onConnectionChanged()，
    //    不再依賴 Extra 中的 WifiP2pInfo）
    // ══════════════════════════════════════════════════════════

    @Test
    public void connectionChanged_anyIntent_callsOnConnectionChanged() throws Exception {
        // groupFormed=true 的廣播
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, buildP2pInfo(true));

        receiver.onReceive(ctx, intent);

        assertTrue("onConnectionChanged 應被呼叫", fake.connectionChangedCalled);
        // connectionInfo / disconnected 由 WifiDirectManager 內部在 requestConnectionInfo() 回呼後決定
        assertFalse("connectionInfo 不應由 Receiver 直接呼叫", fake.connectionInfoCalled);
        assertFalse("disconnected 不應由 Receiver 直接呼叫", fake.disconnectedCalled);
    }

    @Test
    public void connectionChanged_groupNotFormed_stillCallsOnConnectionChanged() throws Exception {
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, buildP2pInfo(false));

        receiver.onReceive(ctx, intent);

        assertTrue("onConnectionChanged 應被呼叫（不論 groupFormed 值）", fake.connectionChangedCalled);
    }

    @Test
    public void connectionChanged_noExtra_stillCallsOnConnectionChanged() {
        // 廣播完全沒有帶 Extra
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        receiver.onReceive(ctx, intent);

        assertTrue("onConnectionChanged 應被呼叫", fake.connectionChangedCalled);
    }

    // ══════════════════════════════════════════════════════════
    //  WIFI_P2P_THIS_DEVICE_CHANGED_ACTION
    // ══════════════════════════════════════════════════════════

    @Test
    public void deviceChanged_withDevice_callsOnThisDeviceChanged() {
        WifiP2pDevice device = new WifiP2pDevice();
        device.deviceName = "TestPhone";
        device.deviceAddress = "aa:bb:cc:dd:ee:ff";

        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, device);

        receiver.onReceive(ctx, intent);

        assertTrue("deviceChanged 應被呼叫", fake.deviceChangedCalled);
        assertNotNull(fake.lastDevice);
        assertEquals("TestPhone", fake.lastDevice.deviceName);
    }

    @Test
    public void deviceChanged_nullDevice_doesNotCallCallback() {
        // EXTRA_WIFI_P2P_DEVICE 為 null 時不應 crash，也不應呼叫 callback
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        // 不加 device extra

        receiver.onReceive(ctx, intent);

        assertFalse("deviceChanged 不應被呼叫", fake.deviceChangedCalled);
    }

    // ══════════════════════════════════════════════════════════
    //  無效 / 未知 Action
    // ══════════════════════════════════════════════════════════

    @Test
    public void nullAction_doesNotCrash_andCallsNothing() {
        Intent intent = new Intent((String) null);

        receiver.onReceive(ctx, intent); // 不應丟出任何例外

        assertFalse(fake.p2pStateChangedCalled);
        assertFalse(fake.connectionInfoCalled);
        assertFalse(fake.disconnectedCalled);
        assertFalse(fake.deviceChangedCalled);
    }

    @Test
    public void unknownAction_doesNotCrash_andCallsNothing() {
        Intent intent = new Intent("com.kisslink.UNKNOWN_ACTION");

        receiver.onReceive(ctx, intent);

        assertFalse(fake.p2pStateChangedCalled);
        assertFalse(fake.connectionInfoCalled);
        assertFalse(fake.disconnectedCalled);
    }

    // ══════════════════════════════════════════════════════════
    //  Helper
    // ══════════════════════════════════════════════════════════

    /**
     * 使用 reflection 建立 {@link WifiP2pInfo} 並設定 groupFormed 欄位。 （WifiP2pInfo 的建構子是
     * package-private，只能透過 reflection 注入測試資料。）
     */
    private static WifiP2pInfo buildP2pInfo(boolean groupFormed) throws Exception {
        WifiP2pInfo info = new WifiP2pInfo();
        // groupFormed 是 public field
        info.groupFormed = groupFormed;
        info.isGroupOwner = groupFormed; // 測試假設本機是 GO
        if (groupFormed) {
            info.groupOwnerAddress = InetAddress.getByName("192.168.49.1");
        }
        return info;
    }
}
