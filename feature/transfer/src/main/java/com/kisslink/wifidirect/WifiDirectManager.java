package com.kisslink.wifidirect;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Network;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;

import com.kisslink.model.GroupCredential;

/**
 * Wi-Fi Direct 核心管理器門面（需要 API 29 / Android 10+）。
 *
 * <p>本類別是<b>門面（facade）</b>：對外維持穩定的公開介面，內部把工作委派給各角色控制器，
 * 並負責共享資源與 BroadcastReceiver 的接線：
 * <ul>
 *   <li>{@link WifiDirectCore} —— 共享底層（P2P Channel、狀態機、超時、重入守衛、失敗碼工具）。</li>
 *   <li>{@link GroupOwnerController} —— GO 端建群 + 憑證產生 + 進入 HOSTING。</li>
 *   <li>{@link GoDetectionPoller} —— GO 端 Client 偵測輪詢（廣播不可靠時的防禦）。</li>
 *   <li>{@link ClientConnector} —— Client 端連線 + 連線偵測輪詢 + P2P 網路綁定。</li>
 * </ul>
 *
 * <h3>GO 端（傳送方）流程</h3>
 * <ol>
 *   <li>呼叫 {@link #createGroupAsGO()}；監聽 {@link #getCredential()} LiveData。</li>
 *   <li>憑證就緒後狀態進入 {@link ConnectionState#HOSTING}，
 *       此時 M3 將憑證注入 HCEService 等待 NFC 碰觸。</li>
 *   <li>對方連線後狀態進入 {@link ConnectionState#CONNECTED}。</li>
 * </ol>
 *
 * <h3>Client 端（接收方）流程</h3>
 * <ol>
 *   <li>M3 透過 NFC 拿到 {@link GroupCredential} 後呼叫
 *       {@link #connectAsClient(GroupCredential)}。</li>
 *   <li>狀態由 CONNECTING → CONNECTED；Network 綁定至當前 process。</li>
 * </ol>
 *
 * <h3>生命週期</h3>
 * <pre>
 *   onResume:  wifiDirectManager.registerReceiver(activity)
 *   onPause:   wifiDirectManager.unregisterReceiver(activity)
 *   onCleared: wifiDirectManager.reset()
 * </pre>
 */
public class WifiDirectManager implements WifiDirectEventCallback {

    private static final String TAG = "WifiDirectManager";

    /** Wi-Fi Direct GO 端的固定 IP（Android 系統保留）。 */
    public static final String GO_IP_ADDRESS = "192.168.49.1";

    /** 傳輸層 TCP 埠。 */
    public static final int TRANSFER_PORT = 47890;

    // ── 共享底層與角色控制器 ───────────────────────────────────
    private final WifiDirectCore core;
    private final GoDetectionPoller goPoller;
    private final ClientConnector clientConnector;
    private final GroupOwnerController groupOwner;

    // ── BroadcastReceiver ──────────────────────────────────────
    private WifiDirectReceiver broadcastReceiver;

    // ══════════════════════════════════════════════════════════
    //  建構子
    // ══════════════════════════════════════════════════════════

    public WifiDirectManager(@NonNull Context context) {
        this.core            = new WifiDirectCore(context);
        this.goPoller        = new GoDetectionPoller(core);
        this.clientConnector = new ClientConnector(core, this::onConnectionInfoAvailable);
        this.groupOwner      = new GroupOwnerController(core, goPoller);
    }

    // ══════════════════════════════════════════════════════════
    //  BroadcastReceiver 生命週期
    // ══════════════════════════════════════════════════════════

    /** 在 Activity/Fragment onResume() 呼叫。 */
    public void registerReceiver(@NonNull Context ctx) {
        if (broadcastReceiver != null) return; // 防止重複註冊
        broadcastReceiver = new WifiDirectReceiver(this);
        // ContextCompat 在所有 API 上一致套用 NOT_EXPORTED(API 33+ 強制旗標,舊版向下相容)。
        ContextCompat.registerReceiver(ctx, broadcastReceiver, buildIntentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "BroadcastReceiver registered");
    }

    /** 在 Activity/Fragment onPause() 呼叫。 */
    public void unregisterReceiver(@NonNull Context ctx) {
        if (broadcastReceiver == null) return;
        try {
            ctx.unregisterReceiver(broadcastReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Receiver was not registered", e);
        }
        broadcastReceiver = null;
        Log.d(TAG, "BroadcastReceiver unregistered");
    }

    private static IntentFilter buildIntentFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        return f;
    }

    // ══════════════════════════════════════════════════════════
    //  角色入口（委派）
    // ══════════════════════════════════════════════════════════

    /**
     * 以 Group Owner 身份建立 Wi-Fi Direct 群組（傳送方呼叫）。
     * 成功後 {@link #getCredential()} 會發射 {@link GroupCredential}，
     * 狀態進入 {@link ConnectionState#HOSTING}。
     */
    public void createGroupAsGO() {
        groupOwner.createGroupAsGO();
    }

    /**
     * 以 Client 身份靜默連線至 GO 的 Wi-Fi Direct 群組（接收方在 NFC 碰觸後呼叫）。
     */
    public void connectAsClient(@NonNull GroupCredential credential) {
        clientConnector.connectAsClient(credential);
    }

    // ══════════════════════════════════════════════════════════
    //  斷線 / 清理
    // ══════════════════════════════════════════════════════════

    /** GO 端移除群組。 */
    @SuppressLint("MissingPermission")
    public void removeGroup() {
        core.p2pManager.removeGroup(core.getChannel(), new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { Log.d(TAG, "Group removed"); }
            @Override public void onFailure(int r) { Log.w(TAG, "removeGroup failed: " + r); }
        });
    }

    /** Client 端解除網路綁定並登出請求。 */
    public void disconnectAsClient() {
        clientConnector.disconnectAsClient();
    }

    /**
     * 完整重置：停止所有操作，回到 {@link ConnectionState#IDLE}。
     * 在 ViewModel.onCleared() 呼叫。
     */
    public void reset() {
        core.cancelTimeout();
        goPoller.stop();
        clientConnector.stopClientPoll();
        // 注意：不在此處呼叫 removeGroup()，因為 PairingActivity 結束時會觸發 reset，
        // 但此時傳輸可能才剛要開始。Group 的生命週期應由系統或使用者手動結束。
        clientConnector.disconnectAsClient();
        core.dispatch(WifiDirectEvent.RESET);
        Log.d(TAG, "WifiDirectManager reset");
    }

    // ══════════════════════════════════════════════════════════
    //  BroadcastReceiver 回調（由 WifiDirectReceiver 呼叫）
    // ══════════════════════════════════════════════════════════

    /**
     * WIFI_P2P_CONNECTION_CHANGED_ACTION 廣播觸發。
     *
     * <p>不從廣播 Extra 讀取 WifiP2pInfo（常常過期），
     * 改呼叫 {@code requestConnectionInfo()} 取最新狀態後
     * 再交由 {@link #onConnectionInfoAvailable} 或 {@link #onDisconnected} 處理。
     */
    @Override
    public void onConnectionChanged() {
        Log.d(TAG, "onConnectionChanged → calling requestConnectionInfo()");
        core.p2pManager.requestConnectionInfo(core.getChannel(), info -> {
            if (info != null && info.groupFormed) {
                onConnectionInfoAvailable(info);
            } else {
                // 只有在「穩定後」收到 groupFormed=false 才視為斷線。
                // 連線/建群/等待對方(HOSTING)階段都可能收到暫時性的 false：
                //  - GO 剛進入 HOSTING、群組仍在成形時，系統會送出 groupFormed=false 的暫時廣播，
                //    若據此轉 DISCONNECTED 會讓 UI 誤報「連線失敗」並停掉 GO poll。
                ConnectionState cs = core.currentState();
                if (cs == ConnectionState.CONNECTING
                        || cs == ConnectionState.CREATING_GROUP
                        || cs == ConnectionState.HOSTING) {
                    Log.d(TAG, "onConnectionChanged: groupFormed=false ignored while state=" + cs);
                } else {
                    onDisconnected();
                }
            }
        });
    }

    @Override
    public void onP2pStateChanged(boolean enabled) {
        if (!enabled) {
            Log.w(TAG, "Wi-Fi P2P disabled");
            ConnectionState cs = core.currentState();
            if (cs == ConnectionState.CONNECTED || cs == ConnectionState.HOSTING) {
                core.dispatch(WifiDirectEvent.P2P_DISABLED);
            }
        }
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        Log.d(TAG, "P2P Info: groupFormed=" + info.groupFormed
                + " isGO=" + info.isGroupOwner
                + " goAddr=" + info.groupOwnerAddress);

        if (!info.groupFormed) {
            // 群組解散或尚未形成
            onDisconnected();
            return;
        }

        if (info.isGroupOwner) {
            // ── GO 端 ─────────────────────────────────────────────
            // 重要：WifiP2pInfo.groupFormed=true 在 GO 端只代表「群組本身存在」，
            // 不代表有 Client 連入。即使沒有任何 Client，這裡也會是 true。
            // 因此 GO 端 **不** 在此處轉 CONNECTED，改由 GoDetectionPoller 透過
            // requestGroupInfo().getClientList() 確認有 Client 後再轉。
            Log.d(TAG, "GO: groupFormed=true (group exists, waiting for client via poll)");

        } else {
            // ── Client 端 ────────────────────────────────────────
            // isGroupOwner=false 且 groupFormed=true → 本裝置已成功加入 GO 的群組
            goPoller.stop();
            clientConnector.onGroupFormed(info);
        }
    }

    @Override
    public void onDisconnected() {
        ConnectionState cs = core.currentState();
        if (cs == ConnectionState.CONNECTED || cs == ConnectionState.HOSTING || cs == ConnectionState.CONNECTING) {
            Log.w(TAG, "P2P connection dropped or failed while connecting");
            goPoller.stop();
            clientConnector.stopClientPoll();
            core.dispatch(WifiDirectEvent.CONNECTION_DROPPED);
        }
    }

    @Override
    public void onThisDeviceChanged(WifiP2pDevice device) {
        Log.d(TAG, "ThisDevice: " + device.deviceName + " status=" + device.status);
    }

    // ══════════════════════════════════════════════════════════
    //  公開 Getters
    // ══════════════════════════════════════════════════════════

    /** 是否仍在群組/連線中(非 IDLE)。供上層判斷是否需要先拆除等沉澱再重建。 */
    public boolean isActive() {
        ConnectionState s = core.currentState();
        return s != null && s != ConnectionState.IDLE;
    }

    /**
     * 本機若當 GO 是否能在 5GHz 開 Wi-Fi Direct 群組——供 GO 選舉偏置用
     * （見 {@link com.kisslink.pairing.PairingToken#shouldBeGroupOwner}）。
     *
     * <p>SCC：P2P 群組頻段 = GO 的 STA 頻段。STA 掛在 2.4GHz AP → 群組被鎖 2.4GHz
     * （實測 ~13Mbps）；未連 STA 或 STA 在 5GHz → GO 可在 5GHz 開群組（數百 Mbps）。
     * 任何不確定一律樂觀回 {@code true}（退回原 goIntent 選舉，絕不比舊版差）。
     */
    // getConnectionInfo() 已棄用,但其替代品 NetworkCapabilities.getTransportInfo() 需 API 31+
    // 且只能透過 network callback 非同步取得;minSdk 29 下仍需此同步查詢。
    @SuppressWarnings("deprecation")
    public static boolean canHostFastGroup(@NonNull Context ctx) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return true;
            if (!wm.is5GHzBandSupported()) return false;          // 硬體不支援 5GHz → 當 GO 必慢
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            if (wi == null || wi.getNetworkId() == -1) return true; // 未連 STA → GO 可自選 5GHz
            int freq = wi.getFrequency();
            return freq <= 0 || freq >= 4900;                     // 5GHz/未知 → 可；2.4GHz → 不可
        } catch (Exception e) {
            return true;
        }
    }

    public LiveData<ConnectionState> getState()       { return core.stateLd; }
    public LiveData<GroupCredential> getCredential()  { return core.credentialLd; }
    public LiveData<String>          getError()       { return core.errorLd; }

    /**
     * Client 端連線成功後回傳已綁定的 {@link Network}，
     * 可傳給 Socket 或 OkHttp 使用，確保流量走 Wi-Fi Direct 網路。
     */
    public Network getClientNetwork() { return clientConnector.getClientNetwork(); }
}
