package com.kisslink.wifidirect;

import android.annotation.SuppressLint;
import android.util.Log;

/**
 * GO 端 Client 偵測輪詢。
 *
 * <p>GO 進入 {@link ConnectionState#HOSTING} 後，每隔 {@value #GO_POLL_INTERVAL_MS} ms
 * 呼叫 {@code requestGroupInfo()} 檢查是否有 Client 加入群組，偵測到即轉
 * {@link ConnectionState#CONNECTED}。
 *
 * <p>這是對廣播不可靠的防禦措施：當 CLIENT 使用 WifiP2pConfig.Builder 連線至
 * Autonomous GO 時，部分裝置/Android 版本不觸發 GO 端的連線變更廣播。
 */
class GoDetectionPoller {

    private static final String TAG = "WifiDirectManager";

    /** GO 進入 HOSTING 後，每隔此毫秒數輪詢一次 requestGroupInfo() 偵測 Client 加入。 */
    private static final int GO_POLL_INTERVAL_MS = 1_000;

    private final WifiDirectCore core;
    private Runnable goPollRunnable;

    GoDetectionPoller(WifiDirectCore core) {
        this.core = core;
    }

    @SuppressLint("MissingPermission")
    void start() {
        stop();
        goPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (core.currentState() != ConnectionState.HOSTING) return;
                core.p2pManager.requestGroupInfo(core.getChannel(), group -> {
                    if (group != null
                            && group.getClientList() != null
                            && !group.getClientList().isEmpty()) {
                        Log.i(TAG, "Poll: client detected ("
                                + group.getClientList().size() + " peer(s))");
                        core.cancelTimeout();
                        stop();
                        core.dispatch(WifiDirectEvent.GO_CLIENT_DETECTED);
                    } else {
                        // 尚未有 Client，繼續輪詢
                        if (core.currentState() == ConnectionState.HOSTING) {
                            core.mainHandler.postDelayed(this, GO_POLL_INTERVAL_MS);
                        }
                    }
                });
            }
        };
        core.mainHandler.postDelayed(goPollRunnable, GO_POLL_INTERVAL_MS);
        Log.d(TAG, "GO poll started (interval=" + GO_POLL_INTERVAL_MS + "ms)");
    }

    void stop() {
        if (goPollRunnable != null) {
            core.mainHandler.removeCallbacks(goPollRunnable);
            goPollRunnable = null;
            Log.d(TAG, "GO poll stopped");
        }
    }
}
