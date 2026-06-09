package com.kisslink.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.kisslink.data.repository.TransferRepository;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.pairing.PairingCoordinator;
import com.kisslink.pairing.PairingToken;
import com.kisslink.ui.transfer.TransferActivity;
import com.kisslink.wifidirect.WifiDirectManager;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * 檔案傳輸前景 Service —— 整個 session 的「單一擁有者」（碰觸配對 + 雙向傳輸）。
 *
 * <h3>新模型（A+B2 + peer 雙向）</h3>
 * <pre>
 *   onCreate → WifiDirectManager + PairingCoordinator（產生本機 token）
 *   前景畫面主控 NFC 切換，latch 後經 binder 餵入：
 *      onNfcLatchedAsReader(peerToken) / onNfcLatchedAsTag()
 *   Coordinator：BLE 換 token → GO 選舉 → GO 建群組+BLE 送憑證 / 非GO 收憑證後連線
 *   onPaired(isGO) → 建 TCP socket（GO accept / client connect）→ PeerConnection（雙向）
 *   之後雙方皆可 sendItems(...)（檔案/名片/照片），多輪互傳
 * </pre>
 * 角色（GO/client）只是建立連線當下的技術身分；連上後對等。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class FileTransferService extends Service {

    private static final String TAG = "FileTransferService";

    private static final String CHANNEL_ID = "kisslink_transfer";
    private static final int    NOTIF_ID   = 1001;

    // ── Session 核心 ───────────────────────────────────────────
    private WifiDirectManager  wifi;
    private PairingCoordinator coordinator;
    @Nullable private PeerConnection peer;
    private boolean isGroupOwner = false;
    private volatile boolean peerStarting = false;
    /** session 世代:每次重置 +1。舊 Coordinator 的回呼以世代不符被忽略,杜絕「殘留場以錯誤角色觸發 onPaired → GO 連到自己」。 */
    private int sessionGen = 0;

    /** 重連:拆除舊場後等 Android Wi-Fi/BLE stack 非同步清理沉澱的時間,再重建新場。 */
    private static final long RESET_SETTLE_MS = 1800;
    private boolean pendingReset = false;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Nullable private LiveData<TransferProgress> peerProgressSrc;
    @Nullable private Observer<TransferProgress> peerProgressObs;

    /** UI 唯一觀察點。 */
    private final MutableLiveData<SessionState> sessionLd =
            new MutableLiveData<>(SessionState.idle());

    private final TransferBinder binder = new TransferBinder();

    // ══════════════════════════════════════════════════════════
    //  Intent Factory（角色中立）
    // ══════════════════════════════════════════════════════════

    public static Intent intent(Context ctx) {
        return new Intent(ctx, FileTransferService.class);
    }

    // ══════════════════════════════════════════════════════════
    //  Binder
    // ══════════════════════════════════════════════════════════

    public class TransferBinder extends Binder {

        public LiveData<SessionState> getSessionState() { return sessionLd; }

        /** 本機這場配對的 token（前景畫面寫進 NFC HCE 用）。 */
        public PairingToken localToken() { return coordinator.localToken(); }

        /** NFC：reader 相位讀到對方 token（本機當 BLE central）。 */
        public void onNfcLatchedAsReader(@NonNull PairingToken peerToken) {
            onLatch(() -> coordinator.onLatchedAsReader(peerToken));
        }

        /** NFC：自己 HCE 被讀（本機當 BLE peripheral）。 */
        public void onNfcLatchedAsTag() {
            onLatch(() -> coordinator.onLatchedAsTag());
        }

        /** 連上後送出內容（任一端皆可、可多輪）。 */
        public void sendItems(@NonNull List<SendItem> items) {
            if (peer != null) peer.sendItems(items);
            else Log.w(TAG, "sendItems before connected");
        }

        public void cancel() {
            teardownPeer();
            stopSelf();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        wifi = new WifiDirectManager(this);
        wifi.registerReceiver(this);
        createCoordinator();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("準備配對…", 0));
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 使用者把 App 從最近清單滑掉 → 結束整個 session(配對畫面不再負責停服務)。
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null); // 取消尚未觸發的重置重建
        teardownPeer();
        if (coordinator != null) coordinator.reset();
        if (wifi != null) { wifi.unregisterReceiver(this); wifi.reset(); }
        KissLinkHCEService.clearToken();
        Log.d(TAG, "FileTransferService destroyed");
    }

    // ══════════════════════════════════════════════════════════
    //  配對協調
    // ══════════════════════════════════════════════════════════

    private void createCoordinator() {
        final int gen = ++sessionGen;
        coordinator = new PairingCoordinator(this, wifi, new PairingCoordinator.Listener() {
            @Override public void onPhase(@NonNull PairingCoordinator.Phase phase) {
                if (gen != sessionGen) return;
                sessionLd.postValue(mapPhase(phase));
            }
            @Override public void onPaired(boolean groupOwner) {
                if (gen != sessionGen) return; // 殘留場 → 忽略,避免錯誤角色 establishPeer
                isGroupOwner = groupOwner;
                sessionLd.postValue(SessionState.of(SessionState.Phase.CONNECTING));
                establishPeer(groupOwner);
            }
            @Override public void onError(@NonNull String message) {
                if (gen != sessionGen) return;
                sessionLd.postValue(SessionState.error(message));
            }
        });
    }

    private static SessionState mapPhase(PairingCoordinator.Phase p) {
        switch (p) {
            case LATCHED:    return SessionState.of(SessionState.Phase.PAIRING_LATCHED);
            case LINKING:    return SessionState.of(SessionState.Phase.PAIRING_LINKING);
            case ELECTING:   return SessionState.of(SessionState.Phase.PAIRING_ELECTING);
            case CONNECTING: return SessionState.of(SessionState.Phase.CONNECTING);
            case CONNECTED:  return SessionState.of(SessionState.Phase.CONNECTED);
            case IDLE:
            default:         return SessionState.of(SessionState.Phase.IDLE);
        }
    }

    /**
     * NFC latch 進來時的單一決策點(集中所有「是否重置」邏輯,避免散落多處互相打架):
     * <ul>
     *   <li>連線存活中(peer != null)→ 回 false:不打擾,讓 UI 觀察者把使用者帶回傳輸畫面。</li>
     *   <li>上一場已結束/不存在(coordinator finished/null)→ 重置出全新 Coordinator 再 latch。</li>
     *   <li>正在配對中(coordinator 未 finished)→ 直接 latch;coordinator 自身的 finished/peerToken
     *       旗標會擋掉重複,不會產生重疊的 coordinator。</li>
     * </ul>
     */
    /**
     * NFC latch 的單一序列化入口。所有「一場配對」的開始/重連決策都在這裡,避免散落多處互相打架。
     * <ul>
     *   <li><b>配對進行中</b>(coordinator 已 started 且未結束)→ 忽略;含「同一次貼合射頻雙向
     *       讀取造成的反向 latch」與重複觸碰。</li>
     *   <li><b>重置排程中</b> → 忽略額外觸碰。</li>
     *   <li><b>乾淨</b>(Wi-Fi IDLE、無 peer、coordinator 未 started)→ 直接交付,瞬間配對。</li>
     *   <li><b>髒</b>(剛斷線 / 已連線 / Wi-Fi 還在群組)→ 先徹底拆除,等 {@link #RESET_SETTLE_MS}
     *       讓 Android Wi-Fi/BLE 非同步清理沉澱(否則立即重建會 GATT 卡死、群組殘留),
     *       再建新 coordinator 並交付 latch。期間顯示 RESETTING。</li>
     * </ul>
     */
    @androidx.annotation.MainThread
    private void onLatch(@NonNull Runnable deliver) {
        if (coordinator != null && coordinator.hasStarted() && !coordinator.isFinished()) return;
        if (pendingReset) return;

        boolean dirty = peer != null
                || (coordinator != null && coordinator.isFinished())
                || (wifi != null && wifi.isActive());

        if (!dirty) {
            if (coordinator == null) createCoordinator();
            deliver.run();
            return;
        }

        pendingReset = true;
        teardownSession();
        sessionLd.setValue(SessionState.of(SessionState.Phase.RESETTING));
        Log.i(TAG, "Dirty state → teardown + settle " + RESET_SETTLE_MS + "ms before re-pair");
        mainHandler.postDelayed(() -> {
            pendingReset = false;
            createCoordinator();
            sessionLd.setValue(SessionState.idle());
            deliver.run(); // 讀取的是當前(新)coordinator
        }, RESET_SETTLE_MS);
    }

    /** 拆除目前 session(peer + coordinator + Wi-Fi 群組 + BLE),但不建立新 coordinator。 */
    private void teardownSession() {
        teardownPeer();
        if (coordinator != null) coordinator.reset();
        if (wifi != null) { wifi.removeGroup(); wifi.reset(); }
        KissLinkHCEService.clearToken();
        isGroupOwner = false;
        Log.i(TAG, "Session torn down");
    }

    // ══════════════════════════════════════════════════════════
    //  建立雙向 socket → PeerConnection
    // ══════════════════════════════════════════════════════════

    private void establishPeer(boolean groupOwner) {
        if (peerStarting || peer != null) return;
        peerStarting = true;
        new Thread(() -> {
            Socket socket = groupOwner ? acceptAsServer() : connectAsClient();
            if (socket == null) {
                peerStarting = false;
                sessionLd.postValue(SessionState.error("建立傳輸通道失敗"));
                return;
            }
            startPeer(socket);
            peerStarting = false;
        }, "peer-setup").start();
    }

    @Nullable
    private Socket acceptAsServer() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true); // 避免上一場 socket 殘留 TIME_WAIT → "Address already in use"
            ss.bind(new InetSocketAddress(WifiDirectManager.TRANSFER_PORT));
            ss.setSoTimeout(20_000);
            Log.i(TAG, "GO: waiting for peer socket…");
            return ss.accept();
        } catch (Exception e) {
            Log.e(TAG, "acceptAsServer failed", e);
            return null;
        }
    }

    @Nullable
    private Socket connectAsClient() {
        for (int i = 0; i < 30; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(
                        WifiDirectManager.GO_IP_ADDRESS, WifiDirectManager.TRANSFER_PORT), 2_000);
                Log.i(TAG, "Client: socket connected on attempt " + (i + 1));
                return s;
            } catch (Exception e) {
                try { Thread.sleep(400); } catch (InterruptedException ie) { return null; }
            }
        }
        Log.e(TAG, "connectAsClient: all attempts failed");
        return null;
    }

    private void startPeer(@NonNull Socket socket) {
        peer = new PeerConnection(this, socket, new PeerConnection.Listener() {
            @Override public void onItemCompleted(boolean sent, String name, long size,
                                                  long avgSpeedBps, boolean success, byte itemType) {
                String dir = sent ? "SEND" : "RECEIVE";
                TransferRepository repo = TransferRepository.getInstance(FileTransferService.this);
                repo.insert(repo.buildRecord(dir, name, size, success, avgSpeedBps, null));
            }
            @Override public void onDisconnected() {
                Log.i(TAG, "Peer disconnected");
                new android.os.Handler(getMainLooper()).post(() -> {
                    sessionLd.postValue(SessionState.error("與對方斷開連線"));
                    teardownPeer();
                });
            }
        });

        // 橋接進度 → SessionState
        peerProgressSrc = peer.getProgress();
        peerProgressObs = tp -> { if (tp != null) sessionLd.postValue(SessionState.fromTransfer(tp)); };
        // observeForever 須在主執行緒
        new android.os.Handler(getMainLooper()).post(() -> {
            if (peerProgressSrc != null && peerProgressObs != null)
                peerProgressSrc.observeForever(peerProgressObs);
        });

        peer.start();
        updateNotification("已連線", 0);
        sessionLd.postValue(SessionState.of(SessionState.Phase.CONNECTED));
        Log.i(TAG, "PeerConnection established (groupOwner=" + isGroupOwner + ")");
    }

    private void teardownPeer() {
        if (peerProgressSrc != null && peerProgressObs != null) {
            final LiveData<TransferProgress> src = peerProgressSrc;
            final Observer<TransferProgress> obs = peerProgressObs;
            new android.os.Handler(getMainLooper()).post(() -> src.removeObserver(obs));
        }
        peerProgressSrc = null;
        peerProgressObs = null;
        if (peer != null) { peer.close(); peer = null; }
    }

    // ══════════════════════════════════════════════════════════
    //  通知
    // ══════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "KissLink 傳輸", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("顯示配對與檔案傳輸進度");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text, int progress) {
        Intent tap = new Intent(this, TransferActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("KissLink")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true);
        if (progress > 0 && progress < 100) b.setProgress(100, progress, false);
        return b.build();
    }

    private void updateNotification(String text, int progress) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text, progress));
    }
}
