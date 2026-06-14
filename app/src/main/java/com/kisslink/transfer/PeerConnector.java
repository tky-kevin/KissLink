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
    private static final int CONNECT_MAX_ATTEMPTS = 30;
    private static final int CONNECT_ATTEMPT_TIMEOUT_MS = 2_000;
    private static final long CONNECT_RETRY_SLEEP_MS = 400L;
    private static final int ACCEPT_TIMEOUT_MS = 20_000;

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
            for (int i = 1; i <= CONNECT_MAX_ATTEMPTS; i++) {
                try {
                    Socket s = new Socket();
                    if (p2pNetwork != null) {
                        p2pNetwork.bindSocket(s);
                        Log.d(TAG, "Client: socket bound to P2P network");
                    }
                    s.connect(new InetSocketAddress(
                            WifiDirectManager.GO_IP_ADDRESS, WifiDirectManager.TRANSFER_PORT),
                            CONNECT_ATTEMPT_TIMEOUT_MS);
                    Log.i(TAG, "Client: socket connected on attempt " + i);
                    callback.onSocketReady(s);
                    return;
                } catch (Exception e) {
                    try { Thread.sleep(CONNECT_RETRY_SLEEP_MS); } catch (InterruptedException ie) {
                        callback.onError("連線中斷");
                        return;
                    }
                }
            }
            Log.e(TAG, "connectAsClient: all attempts failed");
            callback.onError("建立傳輸通道失敗");
        }, "peer-connect").start();
    }
}
