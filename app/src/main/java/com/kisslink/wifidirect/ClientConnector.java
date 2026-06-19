package com.kisslink.wifidirect;

import android.annotation.SuppressLint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.kisslink.model.GroupCredential;

import java.util.function.Consumer;

/**
 * Client 端（接收方）連線流程：靜默加入 GO 的 Wi-Fi Direct 群組、偵測群組形成、
 * 並把 P2P {@link Network} 綁定至目前 Process。
 *
 * <h3>為何用 WifiP2pManager.connect() 而非 WifiNetworkSpecifier？</h3>
 * {@code WifiNetworkSpecifier + requestNetwork()} 是「請系統幫我連到某個 Wi-Fi」的 API，
 * 系統強制顯示授權視窗（Android 10+ 安全設計，無法繞過）。<br>
 * {@code WifiP2pManager.connect()} 是 Wi-Fi Direct 原生 P2P API，
 * 加入已知 Group 不需使用者確認，完全靜默。
 */
class ClientConnector {

    private static final String TAG = "WifiDirectManager";

    private final WifiDirectCore core;

    /**
     * 連線偵測輪詢偵測到群組已形成時，回呼門面的
     * {@link WifiDirectManager#onConnectionInfoAvailable} 以共用同一條處理路徑
     * （與系統廣播觸發時的行為完全一致）。
     */
    private final Consumer<WifiP2pInfo> connectionInfoHandler;

    // ── Client 端連線物件 ──────────────────────────────────────
    private Network clientNetwork;
    private ConnectivityManager.NetworkCallback clientNetworkCallback;

    // ── Client 端連線偵測輪詢 ──────────────────────────────────
    private Runnable clientPollRunnable;

    ClientConnector(WifiDirectCore core, Consumer<WifiP2pInfo> connectionInfoHandler) {
        this.core = core;
        this.connectionInfoHandler = connectionInfoHandler;
    }

    /**
     * 以 Client 身份靜默連線至 GO 的 Wi-Fi Direct 群組（接收方在 NFC 碰觸後呼叫）。
     *
     * <p>連線成功後由 {@link WifiDirectManager#onConnectionInfoAvailable} 觸發
     * {@link #onGroupFormed}，以取得 {@link Network} 物件並綁定至目前 Process。
     */
    @SuppressLint("MissingPermission")
    void connectAsClient(@NonNull GroupCredential credential) {
        if (core.starting || core.currentState() != ConnectionState.IDLE) {
            Log.w(TAG, "connectAsClient skipped, starting=" + core.starting
                    + " state=" + core.currentState());
            return;
        }
        core.starting = true;
        core.setState(ConnectionState.CONNECTING);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Connecting as client (silent P2P) to ssid=" + credential.getSsid());
        }

        // WifiP2pConfig.Builder 指定目標 Group 的 SSID 與 Passphrase（API 29+）
        final WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(credential.getSsid())
                .setPassphrase(credential.getPassphrase())
                .build();

        // 先離開任何殘留的舊群組，否則上一次連線留下的 group membership 會讓
        // connect() 立刻回 ERROR(0)（與 GO 端 createGroupAsGO 先 removeGroup 的理由相同）。
        // removeGroup 為非同步，不論成功（有舊群組）或失敗（本來就沒有）都接著連線。
        core.p2pManager.removeGroup(core.getChannel(), new WifiP2pManager.ActionListener() {
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
        if (core.currentState() != ConnectionState.CONNECTING) {
            Log.w(TAG, "doConnectAsClient skipped, state=" + core.currentState());
            return;
        }

        core.startTimeout(() -> {
            Log.e(TAG, "connectAsClient timeout");
            core.setState(ConnectionState.ERROR);
            core.postError("連線逾時，請靠近後重試");
        });

        startClientPoll();
        attemptConnect(config, 1);
    }

    @SuppressLint("MissingPermission")
    private void attemptConnect(final WifiP2pConfig config, final int attempt) {
        if (core.currentState() != ConnectionState.CONNECTING) return;
        core.p2pManager.connect(core.getChannel(), config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // connect() 發送成功，等待 WifiDirectReceiver 的
                // WIFI_P2P_CONNECTION_CHANGED_ACTION 廣播確認群組已形成
                Log.d(TAG, "P2P connect() initiated — waiting for group formed broadcast");
            }

            @Override
            public void onFailure(int reason) {
                if (core.currentState() != ConnectionState.CONNECTING) return;

                if (WifiDirectCore.isTransientP2pError(reason)
                        && attempt < WifiDirectCore.P2P_TRANSIENT_MAX_RETRY) {
                    Log.w(TAG, "connect() failed " + WifiDirectCore.reasonToString(reason)
                            + ", transient retry " + (attempt + 1)
                            + "/" + WifiDirectCore.P2P_TRANSIENT_MAX_RETRY);
                    // 先盡力離開殘留群組讓框架沉澱，延遲後再連（整體仍受 startTimeout 保護）
                    core.p2pManager.removeGroup(core.getChannel(), core.noopListener);
                    core.mainHandler.postDelayed(() -> {
                        if (core.currentState() == ConnectionState.CONNECTING) {
                            attemptConnect(config, attempt + 1);
                        }
                    }, WifiDirectCore.P2P_TRANSIENT_RETRY_MS);
                } else {
                    core.cancelTimeout();
                    stopClientPoll();
                    core.setState(ConnectionState.ERROR);
                    core.postError("P2P 連線失敗：" + WifiDirectCore.reasonToString(reason));
                }
            }
        });
    }

    /**
     * isGroupOwner=false 且 groupFormed=true → 本裝置已成功加入 GO 的群組。
     * 由門面 {@link WifiDirectManager#onConnectionInfoAvailable} 的 Client 分支呼叫。
     */
    void onGroupFormed(@NonNull WifiP2pInfo info) {
        stopClientPoll();
        if (core.currentState() == ConnectionState.CONNECTING) {
            // P2P group 已形成，立即轉 CONNECTED 讓 PeerConnection 開始 TCP 連線。
            // bindToP2pNetwork() 僅用於優化路由（讓 socket 走 P2P 介面），
            // 部分裝置不回報 P2P 網路至 ConnectivityManager，等待會造成 timeout。
            core.cancelTimeout();
            Log.i(TAG, "Client: P2P group formed -> CONNECTED; binding network in background");
            core.setState(ConnectionState.CONNECTED);
            bindToP2pNetwork();
        }
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
                core.cancelTimeout();
                clientNetwork = network;
                core.connManager.bindProcessToNetwork(network);
                Log.i(TAG, "P2P network bound: " + network);
                core.setState(ConnectionState.CONNECTED);
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.w(TAG, "P2P network lost");
                core.connManager.bindProcessToNetwork(null);
                clientNetwork = null;
                core.setState(ConnectionState.DISCONNECTED);
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
            core.connManager.requestNetwork(req, clientNetworkCallback);
        } catch (RuntimeException e) {
            // SecurityException(受限網路權限)外,部分 OEM 對不完整 NetworkRequest 會丟
            // IllegalArgumentException。此處僅為路由優化,任何失敗都不得使 App 崩潰——
            // TCP 仍可走預設路由連到 GO(192.168.49.1)。
            Log.w(TAG, "requestNetwork failed, relying on default routing to GO", e);
            clientNetworkCallback = null;
        }
    }

    /** Client 端解除網路綁定並登出請求。 */
    void disconnectAsClient() {
        if (clientNetworkCallback != null) {
            try {
                core.connManager.unregisterNetworkCallback(clientNetworkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "unregisterNetworkCallback: " + e.getMessage());
            }
            clientNetworkCallback = null;
        }
        core.connManager.bindProcessToNetwork(null);
        clientNetwork = null;
    }

    /** Client 端啟動輪詢，主動要求連線資訊以防廣播遺失。 */
    void startClientPoll() {
        stopClientPoll();
        clientPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (core.currentState() != ConnectionState.CONNECTING) return;
                Log.d(TAG, "Client poll: requesting connection info...");
                core.p2pManager.requestConnectionInfo(core.getChannel(), info -> {
                    if (info != null && info.groupFormed) {
                        Log.i(TAG, "Client poll: group formed detected");
                        connectionInfoHandler.accept(info);
                    } else if (core.currentState() == ConnectionState.CONNECTING) {
                        core.mainHandler.postDelayed(this, 1000);
                    }
                });
            }
        };
        core.mainHandler.postDelayed(clientPollRunnable, 1000);
        Log.d(TAG, "Client poll started");
    }

    void stopClientPoll() {
        if (clientPollRunnable != null) {
            core.mainHandler.removeCallbacks(clientPollRunnable);
            clientPollRunnable = null;
            Log.d(TAG, "Client poll stopped");
        }
    }

    /**
     * Client 端連線成功後回傳已綁定的 {@link Network}，
     * 可傳給 Socket 或 OkHttp 使用，確保流量走 Wi-Fi Direct 網路。
     */
    Network getClientNetwork() {
        return clientNetwork;
    }
}
