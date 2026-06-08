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
            coordinator.onLatchedAsReader(peerToken);
        }

        /** NFC：自己 HCE 被讀（本機當 BLE peripheral）。 */
        public void onNfcLatchedAsTag() {
            coordinator.onLatchedAsTag();
        }

        /** 連上後送出內容（任一端皆可、可多輪）。 */
        public void sendItems(@NonNull List<SendItem> items) {
            if (peer != null) peer.sendItems(items);
            else Log.w(TAG, "sendItems before connected");
        }

        /** 第三台：拆掉目前 session，重開一場新配對。 */
        public void rePair() { resetForNewSession(); }

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
    public void onDestroy() {
        super.onDestroy();
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
        coordinator = new PairingCoordinator(this, wifi, Build.MODEL, new PairingCoordinator.Listener() {
            @Override public void onPhase(@NonNull PairingCoordinator.Phase phase) {
                sessionLd.postValue(mapPhase(phase));
            }
            @Override public void onPaired(boolean groupOwner) {
                isGroupOwner = groupOwner;
                sessionLd.postValue(SessionState.of(SessionState.Phase.CONNECTING));
                establishPeer(groupOwner);
            }
            @Override public void onError(@NonNull String message) {
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

    /** 第三台重連 / 取消後重置:拆 peer、reset 協調器與 Wi-Fi、重開一場新配對。 */
    private void resetForNewSession() {
        teardownPeer();
        if (coordinator != null) coordinator.reset();
        if (wifi != null) { wifi.removeGroup(); wifi.reset(); }
        KissLinkHCEService.clearToken();
        isGroupOwner = false;
        createCoordinator();
        sessionLd.postValue(SessionState.idle());
        Log.i(TAG, "Session reset for new pairing");
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
        try (ServerSocket ss = new ServerSocket(WifiDirectManager.TRANSFER_PORT)) {
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
