package com.kisslink.wifidirect;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.model.GroupCredential;

import java.security.SecureRandom;

/**
 * Wi-Fi Direct 核心管理器（需要 API 29 / Android 10+）。
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
@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiDirectManager implements WifiDirectEventCallback {

    private static final String TAG = "WifiDirectManager";

    /** Wi-Fi Direct GO 端的固定 IP（Android 系統保留）。 */
    public static final String GO_IP_ADDRESS = "192.168.49.1";

    /** 傳輸層 TCP 埠。 */
    public static final int TRANSFER_PORT = 47890;

    private static final String SSID_PREFIX    = "DIRECT-KL-";
    private static final int    TIMEOUT_MS     = 15_000;

    /**
     * 暫時性 P2P 失敗（BUSY/ERROR）的重試設定。切換傳輸/接收太快時，框架可能還在
     * 拆除上一場群組而回 BUSY(2)/ERROR(0)；短延遲後重試即可成功，毋須讓使用者重來。
     */
    private static final int    P2P_TRANSIENT_MAX_RETRY = 3;
    private static final long   P2P_TRANSIENT_RETRY_MS  = 1_500;
    private static final String PASSPHRASE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    // ── 系統服務 ───────────────────────────────────────────────
    private final Context appContext;
    private final WifiP2pManager p2pManager;
    private       WifiP2pManager.Channel p2pChannel;
    private final ConnectivityManager connManager;

    // ── BroadcastReceiver ──────────────────────────────────────
    private WifiDirectReceiver broadcastReceiver;

    // ── LiveData（對外） ───────────────────────────────────────
    private final MutableLiveData<ConnectionState> stateLd =
            new MutableLiveData<>(ConnectionState.IDLE);
    private final MutableLiveData<GroupCredential> credentialLd =
            new MutableLiveData<>();
    private final MutableLiveData<String> errorLd =
            new MutableLiveData<>();

    // ── Client 端連線物件 ──────────────────────────────────────
    private Network clientNetwork;
    private ConnectivityManager.NetworkCallback clientNetworkCallback;

    /** 同步重入守衛:createGroupAsGO/connectAsClient 進行中為 true,擋住重疊呼叫的競態
     *  (state 用 postValue 非同步更新,光靠 state 檢查擋不住同一輪的第二次呼叫)。 */
    private boolean starting = false;

    // ── 超時計時器 ─────────────────────────────────────────────
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    // ── GO 端 Client 偵測輪詢 ──────────────────────────────────
    /** GO 進入 HOSTING 後，每隔此毫秒數輪詢一次 requestGroupInfo() 偵測 Client 加入。 */
    private static final int GO_POLL_INTERVAL_MS = 1_000;
    private Runnable goPollRunnable;

    // ── Client 端連線偵測輪詢 ──────────────────────────────────
    private Runnable clientPollRunnable;

    // ══════════════════════════════════════════════════════════
    //  建構子
    // ══════════════════════════════════════════════════════════

    public WifiDirectManager(@NonNull Context context) {
        this.appContext  = context.getApplicationContext();
        this.p2pManager  = (WifiP2pManager)
                appContext.getSystemService(Context.WIFI_P2P_SERVICE);
        this.connManager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.p2pChannel  = p2pManager.initialize(
                appContext, Looper.getMainLooper(), this::onChannelDisconnected);
    }

    // ══════════════════════════════════════════════════════════
    //  BroadcastReceiver 生命週期
    // ══════════════════════════════════════════════════════════

    /** 在 Activity/Fragment onResume() 呼叫。 */
    public void registerReceiver(@NonNull Context ctx) {
        if (broadcastReceiver != null) return; // 防止重複註冊
        broadcastReceiver = new WifiDirectReceiver(this);
        ctx.registerReceiver(broadcastReceiver, buildIntentFilter());
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
    //  GO 端：建立 Group
    // ══════════════════════════════════════════════════════════

    /**
     * 以 Group Owner 身份建立 Wi-Fi Direct 群組（傳送方呼叫）。
     * 成功後 {@link #getCredential()} 會發射 {@link GroupCredential}，
     * 狀態進入 {@link ConnectionState#HOSTING}。
     */
    @SuppressLint("MissingPermission") // 權限在呼叫端由 PermissionHelper 確認
    public void createGroupAsGO() {
        if (starting || stateLd.getValue() != ConnectionState.IDLE) {
            Log.w(TAG, "createGroupAsGO skipped, starting=" + starting + " state=" + stateLd.getValue());
            return;
        }
        starting = true; // 同步守衛:擋住重疊 coordinator 的第二次 createGroup(state 用 postValue 非同步,擋不住競態)

        // 檢查 Wi-Fi 是否開啟
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                appContext.getSystemService(Context.WIFI_SERVICE);
        if (wm != null && !wm.isWifiEnabled()) {
            postError("請先開啟 Wi-Fi 以建立連線");
            return;
        }

        if (p2pChannel == null) {
            Log.e(TAG, "p2pChannel is null, re-initializing");
            p2pChannel = p2pManager.initialize(appContext, Looper.getMainLooper(), this::onChannelDisconnected);
            if (p2pChannel == null) {
                postError("Wi-Fi Direct 初始化失敗，請重啟 Wi-Fi");
                return;
            }
        }

        // 1. 先移除可能殘存的舊 Group，避免 BUSY 錯誤
        p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { 
                Log.d(TAG, "removeGroup success");
                doCreateGroup(); 
            }
            @Override public void onFailure(int r) { 
                Log.w(TAG, "removeGroup failed: " + r + " (expected if no group)");
                doCreateGroup(); 
            }
        });
    }

    private void doCreateGroup() {
        doCreateGroup(1);
    }

    @SuppressLint("MissingPermission")
    private void doCreateGroup(final int attempt) {
        setState(ConnectionState.CREATING_GROUP);

        final String ssid       = SSID_PREFIX + generateShortId(); // e.g. "DIRECT-KL-A3F2"
        final String passphrase = generatePassphrase();            // 16 char

        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .build();

        startTimeout(() -> {
            Log.e(TAG, "createGroup timeout");
            setState(ConnectionState.ERROR);
            postError("建立群組逾時，請確認 Wi-Fi 已開啟並重試");
        });

        Log.d(TAG, "Calling createGroup with custom config: " + ssid);
        p2pManager.createGroup(p2pChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                cancelTimeout();
                Log.d(TAG, "createGroup (custom) success → requesting group info");
                fetchGroupInfo(ssid, passphrase);
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "createGroup (custom) failed: " + reason + ", trying legacy fallback");
                // 某些裝置不支援自訂 SSID/Passphrase，嘗試使用系統預設建立
                p2pManager.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        cancelTimeout();
                        Log.d(TAG, "createGroup (legacy) success → requesting group info");
                        fetchGroupInfo(null, null);
                    }

                    @Override
                    public void onFailure(int reason2) {
                        if (stateLd.getValue() != ConnectionState.CREATING_GROUP) return;

                        if (isTransientP2pError(reason2) && attempt < P2P_TRANSIENT_MAX_RETRY) {
                            Log.w(TAG, "createGroup failed " + reasonToString(reason2)
                                    + ", transient retry " + (attempt + 1) + "/" + P2P_TRANSIENT_MAX_RETRY);
                            // 先盡力移除殘留群組讓框架沉澱，延遲後重建（startTimeout 由 doCreateGroup 重設）
                            p2pManager.removeGroup(p2pChannel, noopListener);
                            mainHandler.postDelayed(() -> {
                                if (stateLd.getValue() == ConnectionState.CREATING_GROUP) {
                                    doCreateGroup(attempt + 1);
                                }
                            }, P2P_TRANSIENT_RETRY_MS);
                        } else {
                            cancelTimeout();
                            setState(ConnectionState.ERROR);
                            postError("建立 P2P 群組失敗：" + reasonToString(reason2));
                        }
                    }
                });
            }
        });
    }

    /**
     * 呼叫 requestGroupInfo 取得系統實際採用的 SSID（可能微調）與 Passphrase，
     * 若 API 回傳 null 則 fallback 使用本機產生的值。
     */
    @SuppressLint("MissingPermission")
    private void fetchGroupInfo(String fallbackSsid, String fallbackPass) {
        p2pManager.requestGroupInfo(p2pChannel, group -> {
            final String ssid;
            final String pass;

            if (group != null
                    && group.getNetworkName() != null
                    && group.getPassphrase() != null) {
                ssid = group.getNetworkName();
                pass = group.getPassphrase();
                Log.d(TAG, "Group info from system: ssid=" + ssid);
            } else {
                // 部分裝置 requestGroupInfo 會回傳 null，使用預先產生的值
                Log.w(TAG, "requestGroupInfo returned null, using fallback credentials");
                ssid = fallbackSsid;
                pass = fallbackPass;
            }

            if (ssid == null || pass == null) {
                Log.e(TAG, "Failed to get SSID or Passphrase from system or fallback");
                setState(ConnectionState.ERROR);
                postError("無法取得連線憑證，請重試");
                return;
            }

            GroupCredential cred = new GroupCredential(ssid, pass, GO_IP_ADDRESS, TRANSFER_PORT);
            Log.i(TAG, "GroupCredential ready: " + cred);
            credentialLd.postValue(cred);
            setState(ConnectionState.HOSTING);
            startGoPoll(); // 啟動 Client 偵測輪詢（廣播可能不可靠）
        });
    }

    // ══════════════════════════════════════════════════════════
    //  Client 端：連線至 GO 的 SoftAP
    // ══════════════════════════════════════════════════════════

    /**
     * 以 Client 身份靜默連線至 GO 的 Wi-Fi Direct 群組（接收方在 NFC 碰觸後呼叫）。
     *
     * <h3>為何改用 WifiP2pManager.connect() 而非 WifiNetworkSpecifier？</h3>
     * {@code WifiNetworkSpecifier + requestNetwork()} 是「請系統幫我連到某個 Wi-Fi」的 API，
     * 系統強制顯示授權視窗（Android 10+ 安全設計，無法繞過）。<br>
     * {@code WifiP2pManager.connect()} 是 Wi-Fi Direct 原生 P2P API，
     * 加入已知 Group 不需使用者確認，完全靜默。
     *
     * <p>連線成功後由 {@link #onConnectionInfoAvailable} 觸發 {@link #bindToP2pNetwork}，
     * 以取得 {@link Network} 物件並綁定至目前 Process。
     */
    @SuppressLint("MissingPermission")
    public void connectAsClient(@NonNull GroupCredential credential) {
        if (starting || stateLd.getValue() != ConnectionState.IDLE) {
            Log.w(TAG, "connectAsClient skipped, starting=" + starting + " state=" + stateLd.getValue());
            return;
        }
        starting = true;
        setState(ConnectionState.CONNECTING);
        Log.i(TAG, "Connecting as client (silent P2P) to: " + credential);

        // WifiP2pConfig.Builder 指定目標 Group 的 SSID 與 Passphrase（API 29+）
        final WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(credential.getSsid())
                .setPassphrase(credential.getPassphrase())
                .build();

        // 先離開任何殘留的舊群組，否則上一次連線留下的 group membership 會讓
        // connect() 立刻回 ERROR(0)（與 GO 端 createGroupAsGO 先 removeGroup 的理由相同）。
        // removeGroup 為非同步，不論成功（有舊群組）或失敗（本來就沒有）都接著連線。
        p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                Log.d(TAG, "removeGroup before connect: success (left stale group)");
                doConnectAsClient(config);
            }
            @Override public void onFailure(int r) {
                Log.d(TAG, "removeGroup before connect: failed " + r + " (expected if no group)");
                doConnectAsClient(config);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void doConnectAsClient(WifiP2pConfig config) {
        if (stateLd.getValue() != ConnectionState.CONNECTING) {
            Log.w(TAG, "doConnectAsClient skipped, state=" + stateLd.getValue());
            return;
        }

        startTimeout(() -> {
            Log.e(TAG, "connectAsClient timeout");
            setState(ConnectionState.ERROR);
            postError("連線逾時，請靠近後重試");
        });

        startClientPoll();
        attemptConnect(config, 1);
    }

    @SuppressLint("MissingPermission")
    private void attemptConnect(final WifiP2pConfig config, final int attempt) {
        if (stateLd.getValue() != ConnectionState.CONNECTING) return;
        p2pManager.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // connect() 發送成功，等待 WifiDirectReceiver 的
                // WIFI_P2P_CONNECTION_CHANGED_ACTION 廣播確認群組已形成
                Log.d(TAG, "P2P connect() initiated — waiting for group formed broadcast");
            }

            @Override
            public void onFailure(int reason) {
                if (stateLd.getValue() != ConnectionState.CONNECTING) return;

                if (isTransientP2pError(reason) && attempt < P2P_TRANSIENT_MAX_RETRY) {
                    Log.w(TAG, "connect() failed " + reasonToString(reason)
                            + ", transient retry " + (attempt + 1) + "/" + P2P_TRANSIENT_MAX_RETRY);
                    // 先盡力離開殘留群組讓框架沉澱，延遲後再連（整體仍受 startTimeout 保護）
                    p2pManager.removeGroup(p2pChannel, noopListener);
                    mainHandler.postDelayed(() -> {
                        if (stateLd.getValue() == ConnectionState.CONNECTING) {
                            attemptConnect(config, attempt + 1);
                        }
                    }, P2P_TRANSIENT_RETRY_MS);
                } else {
                    cancelTimeout();
                    stopClientPoll();
                    setState(ConnectionState.ERROR);
                    postError("P2P 連線失敗：" + reasonToString(reason));
                }
            }
        });
    }

    /**
     * P2P 群組形成後，靜默取得網路物件並綁定至 Process。
     * 使用不含 NetworkSpecifier 的 requestNetwork() —— 只是「要求一個符合條件的已存在網路」，
     * 不會觸發系統 UI。
     */
    private void bindToP2pNetwork() {
        if (clientNetworkCallback != null) {
            Log.d(TAG, "bindToP2pNetwork: Already in progress, skipping duplicate request");
            return;
        }

        // 注意：不可移除 NET_CAPABILITY_NOT_RESTRICTED 或 NET_CAPABILITY_TRUSTED。
        // 移除前者會讓系統視為「請求受限網路」，需要 CONNECTIVITY_USE_RESTRICTED_NETWORKS
        // （簽章權限，一般 App 無法持有），requestNetwork() 會直接拋 SecurityException 導致閃退。
        // 移除 NET_CAPABILITY_INTERNET 是安全的標準寫法（P2P 網路無對外網路）。
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        clientNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                cancelTimeout();
                clientNetwork = network;
                connManager.bindProcessToNetwork(network);
                Log.i(TAG, "P2P network bound: " + network);
                setState(ConnectionState.CONNECTED);
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.w(TAG, "P2P network lost");
                connManager.bindProcessToNetwork(null);
                clientNetwork = null;
                setState(ConnectionState.DISCONNECTED);
            }

            @Override
            public void onUnavailable() {
                // P2P group 已形成（state=CONNECTED），此處僅代表路由綁定失敗。
                // TCP socket 仍會嘗試連線 192.168.49.1，OS 通常仍能正確路由 P2P 流量。
                Log.w(TAG, "P2P network unavailable from ConnectivityManager, routing may use default interface");
            }
        };

        // bindToP2pNetwork() 只是路由優化（讓 socket 走 P2P 介面）。狀態已是 CONNECTED，
        // 即使此處被系統拒絕，TCP 仍能透過預設路由連到 192.168.49.1，因此絕不可讓它使 App 崩潰。
        try {
            connManager.requestNetwork(req, clientNetworkCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "requestNetwork denied, relying on default routing to GO", e);
            clientNetworkCallback = null;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  斷線 / 清理
    // ══════════════════════════════════════════════════════════

    /** GO 端移除群組。 */
    @SuppressLint("MissingPermission")
    public void removeGroup() {
        p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { Log.d(TAG, "Group removed"); }
            @Override public void onFailure(int r) { Log.w(TAG, "removeGroup failed: " + r); }
        });
    }

    /** Client 端解除網路綁定並登出請求。 */
    public void disconnectAsClient() {
        if (clientNetworkCallback != null) {
            try {
                connManager.unregisterNetworkCallback(clientNetworkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "unregisterNetworkCallback: " + e.getMessage());
            }
            clientNetworkCallback = null;
        }
        connManager.bindProcessToNetwork(null);
        clientNetwork = null;
    }

    /**
     * 完整重置：停止所有操作，回到 {@link ConnectionState#IDLE}。
     * 在 ViewModel.onCleared() 呼叫。
     */
    public void reset() {
        cancelTimeout();
        stopGoPoll();
        stopClientPoll();
        // 注意：不在此處呼叫 removeGroup()，因為 PairingActivity 結束時會觸發 reset，
        // 但此時傳輸可能才剛要開始。Group 的生命週期應由系統或使用者手動結束。
        disconnectAsClient();
        setState(ConnectionState.IDLE);
        Log.d(TAG, "WifiDirectManager reset");
    }

    // ══════════════════════════════════════════════════════════
    //  BroadcastReceiver 回調（package-private，由 WifiDirectReceiver 呼叫）
    // ══════════════════════════════════════════════════════════

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
        p2pManager.requestConnectionInfo(p2pChannel, info -> {
            if (info != null && info.groupFormed) {
                onConnectionInfoAvailable(info);
            } else {
                // 只有在「穩定後」收到 groupFormed=false 才視為斷線。
                // 連線/建群/等待對方(HOSTING)階段都可能收到暫時性的 false：
                //  - GO 剛進入 HOSTING、群組仍在成形時，系統會送出 groupFormed=false 的暫時廣播，
                //    若據此轉 DISCONNECTED 會讓 UI 誤報「連線失敗」並停掉 GO poll。
                ConnectionState cs = stateLd.getValue();
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
            ConnectionState cs = stateLd.getValue();
            if (cs == ConnectionState.CONNECTED || cs == ConnectionState.HOSTING) {
                setState(ConnectionState.DISCONNECTED);
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
            // 因此 GO 端 **不** 在此處轉 CONNECTED，改由 startGoPoll() 透過
            // requestGroupInfo().getClientList() 確認有 Client 後再轉。
            Log.d(TAG, "GO: groupFormed=true (group exists, waiting for client via poll)");

        } else {
            // ── Client 端 ────────────────────────────────────────
            // isGroupOwner=false 且 groupFormed=true → 本裝置已成功加入 GO 的群組
            stopGoPoll();
            stopClientPoll();
            ConnectionState cs = stateLd.getValue();
            if (cs == ConnectionState.CONNECTING) {
                // P2P group 已形成，立即轉 CONNECTED 讓 TransferClient 開始 TCP 連線。
                // bindToP2pNetwork() 僅用於優化路由（讓 socket 走 P2P 介面），
                // 部分裝置不回報 P2P 網路至 ConnectivityManager，等待會造成 timeout。
                cancelTimeout();
                Log.i(TAG, "Client: P2P group formed -> CONNECTED; binding network in background");
                setState(ConnectionState.CONNECTED);
                bindToP2pNetwork();
            }
        }
    }

    @Override
    public void onDisconnected() {
        ConnectionState cs = stateLd.getValue();
        if (cs == ConnectionState.CONNECTED || cs == ConnectionState.HOSTING || cs == ConnectionState.CONNECTING) {
            Log.w(TAG, "P2P connection dropped or failed while connecting");
            stopGoPoll();
            stopClientPoll();
            setState(ConnectionState.DISCONNECTED);
        }
    }

    @Override
    public void onThisDeviceChanged(WifiP2pDevice device) {
        Log.d(TAG, "ThisDevice: " + device.deviceName + " status=" + device.status);
    }

    private void onChannelDisconnected() {
        Log.w(TAG, "P2P channel lost, reinitializing...");
        p2pChannel = p2pManager.initialize(
                appContext, Looper.getMainLooper(), this::onChannelDisconnected);
        setState(ConnectionState.DISCONNECTED);
    }

    // ══════════════════════════════════════════════════════════
    //  公開 Getters
    // ══════════════════════════════════════════════════════════

    /** 是否仍在群組/連線中(非 IDLE)。供上層判斷是否需要先拆除等沉澱再重建。 */
    public boolean isActive() {
        ConnectionState s = stateLd.getValue();
        return s != null && s != ConnectionState.IDLE;
    }

    public LiveData<ConnectionState> getState()       { return stateLd; }
    public LiveData<GroupCredential> getCredential()  { return credentialLd; }
    public LiveData<String>          getError()       { return errorLd; }

    /**
     * Client 端連線成功後回傳已綁定的 {@link Network}，
     * 可傳給 Socket 或 OkHttp 使用，確保流量走 Wi-Fi Direct 網路。
     */
    public Network getClientNetwork() { return clientNetwork; }

    // ══════════════════════════════════════════════════════════
    //  私有工具
    // ══════════════════════════════════════════════════════════

    private void setState(ConnectionState s) {
        // 到達任何終態 → 釋放重入守衛,允許下一場 createGroup/connect。
        if (s == ConnectionState.CONNECTED || s == ConnectionState.ERROR
                || s == ConnectionState.DISCONNECTED || s == ConnectionState.IDLE) {
            starting = false;
        }
        Log.d(TAG, "State: " + stateLd.getValue() + " → " + s);
        stateLd.postValue(s);
    }

    private void postError(String msg) {
        Log.e(TAG, "Error: " + msg);
        errorLd.postValue(msg);
    }

    // ── GO 端 Client 偵測輪詢 ──────────────────────────────────

    /**
     * GO 進入 HOSTING 後啟動輪詢，每 {@value #GO_POLL_INTERVAL_MS} ms
     * 呼叫 requestGroupInfo() 檢查是否有 Client 加入群組。
     *
     * <p>這是對廣播不可靠的防禦措施：當 CLIENT 使用 WifiP2pConfig.Builder
     * 連線至 Autonomous GO 時，部分裝置/Android 版本不觸發 GO 端廣播。
     */
    @SuppressLint("MissingPermission")
    private void startGoPoll() {
        stopGoPoll();
        goPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (stateLd.getValue() != ConnectionState.HOSTING) return;
                p2pManager.requestGroupInfo(p2pChannel, group -> {
                    if (group != null
                            && group.getClientList() != null
                            && !group.getClientList().isEmpty()) {
                        Log.i(TAG, "Poll: client detected ("
                                + group.getClientList().size() + " peer(s))");
                        cancelTimeout();
                        stopGoPoll();
                        setState(ConnectionState.CONNECTED);
                    } else {
                        // 尚未有 Client，繼續輪詢
                        if (stateLd.getValue() == ConnectionState.HOSTING) {
                            mainHandler.postDelayed(this, GO_POLL_INTERVAL_MS);
                        }
                    }
                });
            }
        };
        mainHandler.postDelayed(goPollRunnable, GO_POLL_INTERVAL_MS);
        Log.d(TAG, "GO poll started (interval=" + GO_POLL_INTERVAL_MS + "ms)");
    }

    private void stopGoPoll() {
        if (goPollRunnable != null) {
            mainHandler.removeCallbacks(goPollRunnable);
            goPollRunnable = null;
            Log.d(TAG, "GO poll stopped");
        }
    }

    /** Client 端啟動輪詢，主動要求連線資訊以防廣播遺失。 */
    private void startClientPoll() {
        stopClientPoll();
        clientPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (stateLd.getValue() != ConnectionState.CONNECTING) return;
                Log.d(TAG, "Client poll: requesting connection info...");
                p2pManager.requestConnectionInfo(p2pChannel, info -> {
                    if (info != null && info.groupFormed) {
                        Log.i(TAG, "Client poll: group formed detected");
                        onConnectionInfoAvailable(info);
                    } else if (stateLd.getValue() == ConnectionState.CONNECTING) {
                        mainHandler.postDelayed(this, 1000);
                    }
                });
            }
        };
        mainHandler.postDelayed(clientPollRunnable, 1000);
        Log.d(TAG, "Client poll started");
    }

    private void stopClientPoll() {
        if (clientPollRunnable != null) {
            mainHandler.removeCallbacks(clientPollRunnable);
            clientPollRunnable = null;
            Log.d(TAG, "Client poll stopped");
        }
    }

    // ── 超時計時器 ─────────────────────────────────────────────

    private void startTimeout(Runnable onTimeout) {
        cancelTimeout();
        timeoutRunnable = onTimeout;
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /** 產生 4 字元隨機十六進位 ID，用於 SSID 尾綴。 */
    private static String generateShortId() {
        byte[] b = new byte[2];
        new SecureRandom().nextBytes(b);
        return String.format("%02X%02X", b[0] & 0xFF, b[1] & 0xFF);
    }

    /** 產生 16 字元英數字 Passphrase（符合 WPA2 最短 8 字元要求）。 */
    private static String generatePassphrase() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PASSPHRASE_CHARS.charAt(rng.nextInt(PASSPHRASE_CHARS.length())));
        }
        return sb.toString();
    }

    /** BUSY / ERROR 視為暫時性失敗，值得短延遲後重試（多半是框架尚未沉澱）。 */
    private static boolean isTransientP2pError(int reason) {
        return reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR;
    }

    /** 空的 ActionListener，用於「盡力而為」的清理呼叫（成敗皆不影響後續流程）。 */
    private final WifiP2pManager.ActionListener noopListener =
            new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int r) {}
            };

    private static String reasonToString(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "裝置不支援 Wi-Fi P2P (1)";
            case WifiP2pManager.ERROR:           return "內部錯誤 (0)";
            case WifiP2pManager.BUSY:            return "系統繁忙，請稍後再試 (2)";
            default:                             return "未知錯誤 (code=" + reason + ")";
        }
    }
}
