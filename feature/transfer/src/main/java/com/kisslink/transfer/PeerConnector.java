package com.kisslink.transfer;

import android.net.Network;
import android.util.Log;
import androidx.annotation.Nullable;
import com.kisslink.wifidirect.WifiDirectManager;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Encapsulates TCP socket establishment: accept (GO) or connect-with-retry (client). Extracted from
 * FileTransferService to isolate networking concerns.
 */
public final class PeerConnector {

    private static final String TAG = "PeerConnector";
    // client 連線預算改用牆鐘上限（非固定次數）：每次嘗試耗時不定（拒絕→秒回、逾時→2s），
    // 固定次數會讓總時長在 12~72s 間飄。改成總預算後與 GO 的 accept 逾時可精準對齊，
    // 避免「GO 20s 放棄、client 還在試 72s → 一台連上、另一台卡在連線中」的單邊卡死。
    private static final int CONNECT_BUDGET_MS = 20_000; // client 連線總預算（牆鐘）
    private static final int CONNECT_ATTEMPT_TIMEOUT_MS = 2_000;
    private static final long CONNECT_RETRY_SLEEP_MS = 400L;
    private static final int ACCEPT_TIMEOUT_MS = 22_000; // GO 比 client 預算稍長，確保 client 最後一搏時仍在聽

    public interface Callback {
        void onSocketReady(Socket socket);

        void onError(String message);
    }

    /** Accept a TCP connection as GO (group owner). */
    public void acceptAsServer(Callback callback) {
        new Thread(
                        () -> {
                            try (ServerSocket ss = new ServerSocket()) {
                                ss.setReuseAddress(true);
                                ss.bind(new InetSocketAddress(WifiDirectManager.TRANSFER_PORT));
                                ss.setSoTimeout(ACCEPT_TIMEOUT_MS);
                                Log.i(TAG, "GO: waiting for peer socket…");
                                Socket socket = ss.accept();
                                callback.onSocketReady(socket);
                            } catch (Exception e) {
                                Log.e(TAG, "acceptAsServer failed", e);
                                callback.onError("建立傳輸通道失敗");
                            }
                        },
                        "peer-accept")
                .start();
    }

    /**
     * Connect to GO as client with retry loop.
     *
     * <p>P2P 網路是在 P2P 群組形成({@code CONNECTED})<b>之後</b>才非同步綁定的, 故不可在迴圈外抓一次 {@link Network}(那時必為
     * null)。改傳 {@code networkSupplier} 每次重試重抓:背景綁定一就緒即走 {@link Network#bindSocket} 強制走 P2P 介面。
     * 這是必要的——當裝置同時連著 Wi-Fi AP 時,預設路由走 AP,{@code 192.168.49.1} 在 P2P 介面上不可達(見 LESSONS_LEARNED
     * 坑9/坑11),僅靠 process 綁定並不可靠。
     */
    public void connectAsClient(
            java.util.function.Supplier<Network> networkSupplier, Callback callback) {
        new Thread(
                        () -> {
                            final long deadline = System.currentTimeMillis() + CONNECT_BUDGET_MS;
                            int attempt = 0;
                            boolean loggedBind = false;
                            while (System.currentTimeMillis() < deadline) {
                                attempt++;
                                Socket s = null;
                                try {
                                    s = new Socket();
                                    Network p2pNetwork =
                                            networkSupplier != null ? networkSupplier.get() : null;
                                    if (p2pNetwork != null) {
                                        p2pNetwork.bindSocket(s);
                                        if (!loggedBind) {
                                            Log.i(
                                                    TAG,
                                                    "Client: socket bound to P2P network "
                                                            + p2pNetwork
                                                            + " (attempt "
                                                            + attempt
                                                            + ")");
                                            loggedBind = true;
                                        }
                                    } else if (attempt == 1) {
                                        Log.w(
                                                TAG,
                                                "Client: P2P network not ready yet → retry will rebind once bound");
                                    }
                                    s.connect(
                                            new InetSocketAddress(
                                                    WifiDirectManager.GO_IP_ADDRESS,
                                                    WifiDirectManager.TRANSFER_PORT),
                                            CONNECT_ATTEMPT_TIMEOUT_MS);
                                    Log.i(TAG, "Client: socket connected on attempt " + attempt);
                                    callback.onSocketReady(s); // 所有權移交給呼叫端，不在此 close
                                    return;
                                } catch (Exception e) {
                                    // 失敗原因留診斷:逾時(SocketTimeoutException)=路由不通、
                                    // 被拒(ConnectException)=GO 尚未 listen,兩者排查方向不同。
                                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                                        Log.d(
                                                TAG,
                                                "Client connect attempt "
                                                        + attempt
                                                        + " failed: "
                                                        + e.getClass().getSimpleName()
                                                        + " "
                                                        + e.getMessage());
                                    }
                                    // 連線失敗務必關閉本次 socket，否則每次重試都洩漏一個 native FD
                                    // （bind 後 connect 逾時／被拒的 Socket 仍持有未釋放的描述子）。
                                    closeQuietly(s);
                                    if (System.currentTimeMillis() >= deadline) break;
                                    try {
                                        Thread.sleep(CONNECT_RETRY_SLEEP_MS);
                                    } catch (InterruptedException ie) {
                                        callback.onError("連線中斷");
                                        return;
                                    }
                                }
                            }
                            Log.e(
                                    TAG,
                                    "connectAsClient: budget exhausted after "
                                            + attempt
                                            + " attempts");
                            callback.onError("建立傳輸通道失敗");
                        },
                        "peer-connect")
                .start();
    }

    private static void closeQuietly(@Nullable Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
