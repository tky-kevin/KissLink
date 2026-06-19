package com.kisslink.transfer;

import android.net.Network;
import android.util.Log;

import com.kisslink.wifidirect.WifiDirectManager;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Encapsulates TCP socket establishment: accept (GO) or connect-with-retry (client).
 * Extracted from FileTransferService to isolate networking concerns.
 */
public final class PeerConnector {

    private static final String TAG = "PeerConnector";
    // client 連線預算改用牆鐘上限（非固定次數）：每次嘗試耗時不定（拒絕→秒回、逾時→2s），
    // 固定次數會讓總時長在 12~72s 間飄。改成總預算後與 GO 的 accept 逾時可精準對齊，
    // 避免「GO 20s 放棄、client 還在試 72s → 一台連上、另一台卡在連線中」的單邊卡死。
    private static final int  CONNECT_BUDGET_MS = 20_000;          // client 連線總預算（牆鐘）
    private static final int  CONNECT_ATTEMPT_TIMEOUT_MS = 2_000;
    private static final long CONNECT_RETRY_SLEEP_MS = 400L;
    private static final int  ACCEPT_TIMEOUT_MS = 22_000;          // GO 比 client 預算稍長，確保 client 最後一搏時仍在聽

    public interface Callback {
        void onSocketReady(Socket socket);
        void onError(String message);
    }

    /**
     * Accept a TCP connection as GO (group owner).
     */
    public void acceptAsServer(Callback callback) {
        new Thread(() -> {
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
        }, "peer-accept").start();
    }

    /**
     * Connect to GO as client with retry loop.
     */
    public void connectAsClient(Network p2pNetwork, Callback callback) {
        new Thread(() -> {
            final long deadline = System.currentTimeMillis() + CONNECT_BUDGET_MS;
            int attempt = 0;
            while (System.currentTimeMillis() < deadline) {
                attempt++;
                try {
                    Socket s = new Socket();
                    if (p2pNetwork != null) {
                        p2pNetwork.bindSocket(s);
                        Log.d(TAG, "Client: socket bound to P2P network");
                    }
                    s.connect(new InetSocketAddress(
                            WifiDirectManager.GO_IP_ADDRESS, WifiDirectManager.TRANSFER_PORT),
                            CONNECT_ATTEMPT_TIMEOUT_MS);
                    Log.i(TAG, "Client: socket connected on attempt " + attempt);
                    callback.onSocketReady(s);
                    return;
                } catch (Exception e) {
                    if (System.currentTimeMillis() >= deadline) break;
                    try { Thread.sleep(CONNECT_RETRY_SLEEP_MS); } catch (InterruptedException ie) {
                        callback.onError("連線中斷");
                        return;
                    }
                }
            }
            Log.e(TAG, "connectAsClient: budget exhausted after " + attempt + " attempts");
            callback.onError("建立傳輸通道失敗");
        }, "peer-connect").start();
    }
}
