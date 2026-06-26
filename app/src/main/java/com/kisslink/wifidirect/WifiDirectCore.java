package com.kisslink.wifidirect;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.diag.FlightRecorder;
import com.kisslink.model.GroupCredential;

/**
 * Wi-Fi Direct 各角色控制器共用的底層基礎設施（package-private）。
 *
 * <p>{@link WifiDirectManager} 門面與 {@link GroupOwnerController}、{@link ClientConnector}、
 * {@link GoDetectionPoller} 都透過此物件存取共享的可變狀態：系統服務、P2P Channel
 * （會在斷線時重建）、連線狀態機（LiveData）、超時計時器與重入守衛。
 *
 * <p>把這些共享資源集中於此，是為了讓上述控制器各自只負責單一角色流程，
 * 而不需互相持有對方的欄位。本類別<b>不</b>含任何 GO/Client 角色邏輯。
 *
 * <p>呼叫端 {@link WifiDirectManager} 已宣告 {@code @RequiresApi(Q)}；本模組 minSdk 即為 29，
 * 故各 package-private 控制器不再重複標註（避免 lint {@code ObsoleteSdkInt}）。
 */
class WifiDirectCore {

    private static final String TAG = "WifiDirectManager";

    private static final int TIMEOUT_MS = 15_000;

    /**
     * 暫時性 P2P 失敗（BUSY/ERROR）的重試設定。切換傳輸/接收太快時，框架可能還在
     * 拆除上一場群組而回 BUSY(2)/ERROR(0)；短延遲後重試即可成功，毋須讓使用者重來。
     */
    static final int  P2P_TRANSIENT_MAX_RETRY = 3;
    static final long P2P_TRANSIENT_RETRY_MS  = 1_500;

    // ── 系統服務 ───────────────────────────────────────────────
    final Context appContext;
    final WifiP2pManager p2pManager;
    final ConnectivityManager connManager;
    private WifiP2pManager.Channel p2pChannel;

    // ── LiveData（對外，由門面轉發） ───────────────────────────
    final MutableLiveData<ConnectionState> stateLd =
            new MutableLiveData<>(ConnectionState.IDLE);
    final MutableLiveData<GroupCredential> credentialLd =
            new MutableLiveData<>();
    final MutableLiveData<String> errorLd =
            new MutableLiveData<>();

    /** 同步重入守衛:createGroupAsGO/connectAsClient 進行中為 true,擋住重疊呼叫的競態
     *  (state 用 postValue 非同步更新,光靠 state 檢查擋不住同一輪的第二次呼叫)。 */
    volatile boolean starting = false;

    // ── 超時計時器 ─────────────────────────────────────────────
    final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    WifiDirectCore(@NonNull Context context) {
        this.appContext  = context.getApplicationContext();
        this.p2pManager  = (WifiP2pManager)
                appContext.getSystemService(Context.WIFI_P2P_SERVICE);
        this.connManager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.p2pChannel  = p2pManager.initialize(
                appContext, Looper.getMainLooper(), this::onChannelDisconnected);
    }

    // ── P2P Channel ────────────────────────────────────────────

    /** 目前的 P2P Channel（可能為 null，呼叫端需自行判斷或先 {@link #ensureChannel()}）。 */
    WifiP2pManager.Channel getChannel() {
        return p2pChannel;
    }

    /** Channel 為 null 時嘗試重新初始化；回傳當前 Channel（仍可能為 null 代表失敗）。 */
    WifiP2pManager.Channel ensureChannel() {
        if (p2pChannel == null) {
            Log.e(TAG, "p2pChannel is null, re-initializing");
            p2pChannel = p2pManager.initialize(
                    appContext, Looper.getMainLooper(), this::onChannelDisconnected);
        }
        return p2pChannel;
    }

    private void onChannelDisconnected() {
        Log.w(TAG, "P2P channel lost, reinitializing...");
        p2pChannel = p2pManager.initialize(
                appContext, Looper.getMainLooper(), this::onChannelDisconnected);
        setState(ConnectionState.DISCONNECTED);
    }

    // ── 狀態機 ─────────────────────────────────────────────────

    ConnectionState currentState() {
        return stateLd.getValue();
    }

    void setState(ConnectionState s) {
        // 到達任何終態 → 釋放重入守衛,允許下一場 createGroup/connect。
        if (s == ConnectionState.CONNECTED || s == ConnectionState.ERROR
                || s == ConnectionState.DISCONNECTED || s == ConnectionState.IDLE) {
            starting = false;
        }
        FlightRecorder.seq(TAG, "wifi state " + stateLd.getValue() + " → " + s);
        stateLd.postValue(s);
    }

    void postError(String msg) {
        FlightRecorder.event(TAG, "wifi error: " + msg);
        errorLd.postValue(msg);
    }

    // ── 超時計時器 ─────────────────────────────────────────────

    void startTimeout(Runnable onTimeout) {
        cancelTimeout();
        timeoutRunnable = onTimeout;
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS);
    }

    void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    // ── P2P 失敗碼工具 ─────────────────────────────────────────

    /** BUSY / ERROR 視為暫時性失敗，值得短延遲後重試（多半是框架尚未沉澱）。 */
    static boolean isTransientP2pError(int reason) {
        return reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR;
    }

    static String reasonToString(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "裝置不支援 Wi-Fi P2P (1)";
            case WifiP2pManager.ERROR:           return "內部錯誤 (0)";
            case WifiP2pManager.BUSY:            return "系統繁忙，請稍後再試 (2)";
            default:                             return "未知錯誤 (code=" + reason + ")";
        }
    }

    /** 空的 ActionListener，用於「盡力而為」的清理呼叫（成敗皆不影響後續流程）。 */
    final WifiP2pManager.ActionListener noopListener =
            new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int r) {}
            };
}
