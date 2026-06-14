package com.kisslink.transfer;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.kisslink.data.repository.TransferRepository;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.pairing.PairingCoordinator;
import com.kisslink.pairing.PairingToken;
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
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

    /** 目前連線對方的 token——用於「同對象 resume」與「新對象切換」判別。 */
    @Nullable private PairingToken connectedPeerToken;
    /** 對方 HELLO 帶來的名片（名稱 + 頭像縮圖），優先於 token deviceName 顯示。 */
    @Nullable private volatile String peerNameFromHello;
    @Nullable private volatile byte[] peerAvatarBytes;
    /** 傳輸中碰到新對象時排隊,等本場傳輸結束再切換。 */
    @Nullable private PairingToken pendingSwitchPeer;

    // ── Extracted managers ─────────────────────────────────────
    private IdleTeardownManager idleManager;
    private ServiceNotificationHelper notificationHelper;
    private WakeLockManager wakeLockManager;

    /** 是否正在傳檔(由進度橋接更新)。 */
    private boolean transferring = false;

    @Nullable private LiveData<TransferProgress> peerProgressSrc;
    @Nullable private Observer<TransferProgress> peerProgressObs;

    /** UI 唯一觀察點。 */
    private final MutableLiveData<SessionState> sessionLd =
            new MutableLiveData<>(SessionState.idle());

    /** 收到名片(vCard)事件——帶 bytes，供 UI 以名片介面開啟；消費後置 null。 */
    private final MutableLiveData<byte[]> incomingCardLd = new MutableLiveData<>(null);

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

        /** 目前已連線對方的顯示名稱（HELLO 名片優先，其次 token；未連線回 null）。 */
        @Nullable public String connectedPeerName() { return currentPeerName(); }

        /** 對方頭像縮圖 bytes（HELLO 交換；無則 null）。 */
        @Nullable public byte[] connectedPeerAvatar() { return peerAvatarBytes; }

        /** 收到名片事件（bytes 非 null 即代表有新名片可開啟）。 */
        public LiveData<byte[]> getIncomingCard() { return incomingCardLd; }

        /** UI 消費名片後清空，避免重綁定重複開啟。 */
        public void clearIncomingCard() { incomingCardLd.postValue(null); }

        /** NFC：reader 相位讀到對方 token（本機當 BLE central；帶對方 token 可辨識同/新對象）。 */
        public void onNfcLatchedAsReader(@NonNull PairingToken peerToken) {
            handleLatch(peerToken, () -> coordinator.onLatchedAsReader(peerToken));
        }

        /** NFC：自己 HCE 被讀（本機當 BLE peripheral；無法當下辨識對方身分）。 */
        public void onNfcLatchedAsTag() {
            handleLatch(null, () -> coordinator.onLatchedAsTag());
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

        /**
         * 使用者手動中斷連線：走與「對方自然斷線」相同的輕量路徑（只拆 peer、回 IDLE），
         * 不在此清 HCE token／重置 coordinator／拆 Wi-Fi 群組——那些交給下一次 latch 的
         * proceedWithLatch（dirty 重建）處理。如此手動斷線後的重新配對與自然斷線完全一致，可正常重連。
         */
        public void disconnect() {
            mainHandler.post(() -> {
                if (pendingReset) return; // 重置沉澱中 → 忽略
                teardownSession();
                createCoordinator();
                sessionLd.setValue(SessionState.idle());
                Log.i(TAG, "User disconnect → full teardown (Wi-Fi group removed)");
            });
        }

        /**
         * 使用者點配對/連線階段文字「中斷重來」:走與手動斷線相同的輕量路徑——
         * 停掉進行中的配對 BLE({@link PairingCoordinator#cancelLightweight})、拆 peer(若已建立)、回 IDLE。
         * 刻意<b>不</b>走 {@link #teardownSession}(不清 HCE token、不拆 Wi-Fi 群組),
         * 那些交給下一次貼合的 {@link #proceedWithLatch} dirty 重建。如此中斷後可立即再貼合重連。
         */
        public void interruptPairing() {
            mainHandler.post(() -> {
                if (pendingReset) return;
                connectedPeerToken = null;
                peerNameFromHello = null;
                peerAvatarBytes = null;
                setTransferring(false);
                peerStarting = false;
                teardownPeer();
                if (coordinator != null) coordinator.cancelLightweight();
                sessionLd.setValue(SessionState.idle());
                Log.i(TAG, "Pairing interrupted (lightweight) → IDLE; rebuild deferred to next tap");
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        idleManager.setUiBound(true);
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        idleManager.setUiBound(false);
        idleManager.scheduleIfIdle();
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        idleManager.setUiBound(true);
    }

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        idleManager = new IdleTeardownManager(mainHandler, this::stopSelf);
        notificationHelper = new ServiceNotificationHelper(this);
        wakeLockManager = new WakeLockManager(this);
        wifi = new WifiDirectManager(this);
        wifi.registerReceiver(this);
        wifi.removeGroup();
        createCoordinator();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ServiceNotificationHelper.NOTIF_ID,
                notificationHelper.build("準備配對…", 0));
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        teardownPeer();
        if (coordinator != null) coordinator.reset();
        if (wifi != null) {
            wifi.removeGroup();
            wifi.unregisterReceiver(this);
            wifi.reset();
        }
        wakeLockManager.release();
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
                if (gen != sessionGen) return;
                isGroupOwner = groupOwner;
                connectedPeerToken = coordinator.peerToken();
                sessionLd.postValue(SessionState.of(SessionState.Phase.CONNECTING));
                establishPeer(groupOwner);
            }
            @Override public void onError(@NonNull String message) {
                if (gen != sessionGen) return;
                sessionLd.postValue(SessionState.error(message));
            }
        });
        KissLinkHCEService.setActiveToken(coordinator.localToken());
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
     * NFC latch 單一序列化入口,先處理「已連線」情境,再決定是否重新配對。
     *
     * @param tappedPeer reader 端能立刻知道對方 token(辨識同/新對象);tag 端為 null(無法辨識)。
     */
    @androidx.annotation.MainThread
    private void handleLatch(@Nullable PairingToken tappedPeer, @NonNull Runnable deliver) {
        if (coordinator != null && coordinator.hasStarted() && !coordinator.isFinished()) return;
        if (pendingReset) return;

        boolean alive = (peer != null && peer.isAlive());
        if (alive) {
            boolean samePeer = (tappedPeer == null) || tappedPeer.sameSession(connectedPeerToken);
            if (samePeer) {
                sessionLd.setValue(SessionState.of(SessionState.Phase.CONNECTED));
                Log.i(TAG, "Same-peer tap while connected → resume (no teardown)");
                return;
            }
            if (transferring) {
                pendingSwitchPeer = tappedPeer;
                mainHandler.post(() -> android.widget.Toast.makeText(
                        FileTransferService.this, "傳輸完成後切換至新裝置",
                        android.widget.Toast.LENGTH_LONG).show());
                Log.i(TAG, "New peer while transferring → queued switch");
                return;
            }
        }
        proceedWithLatch(deliver);
    }

    @androidx.annotation.MainThread
    private void proceedWithLatch(@NonNull Runnable deliver) {
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
            deliver.run();
        }, RESET_SETTLE_MS);
    }

    @androidx.annotation.MainThread
    private void maybeSwitchToPendingPeer() {
        if (pendingSwitchPeer == null) return;
        final PairingToken p = pendingSwitchPeer;
        pendingSwitchPeer = null;
        Log.i(TAG, "Transfer done → switching to queued new peer");
        handleLatch(p, () -> coordinator.onLatchedAsReader(p));
    }

    private void teardownSession() {
        teardownPeer();
        if (coordinator != null) coordinator.reset();
        if (wifi != null) { wifi.removeGroup(); wifi.reset(); }
        isGroupOwner = false;
        connectedPeerToken = null;
        peerNameFromHello = null;
        peerAvatarBytes = null;
        setTransferring(false);
        Log.i(TAG, "Session torn down");
    }

    /** Transfer-completed-driven peer switch (called from progress observer). */
    private void onTransferCompleted() {
        maybeSwitchToPendingPeer();
        idleManager.scheduleIfIdle();
    }

    /** Thread-safe setter for transferring flag that updates idle manager. */
    private void setTransferring(boolean value) {
        transferring = value;
        idleManager.setTransferring(value);
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
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(WifiDirectManager.TRANSFER_PORT));
            ss.setSoTimeout(20_000);
            Log.i(TAG, "GO: waiting for peer socket…");
            return ss.accept();
        } catch (Exception e) {
            Log.e(TAG, "acceptAsClient failed", e);
            return null;
        }
    }

    @Nullable
    private Socket connectAsClient() {
        // Fix WiFi routing: get the P2P network and bind socket to it
        // This ensures TCP traffic goes through P2P interface, not the WiFi AP
        Network p2pNetwork = wifi.getClientNetwork();
        for (int i = 0; i < 30; i++) {
            try {
                Socket s = new Socket();
                // Bind socket to P2P network if available (API 29+)
                if (p2pNetwork != null) {
                    p2pNetwork.bindSocket(s);
                    Log.d(TAG, "Client: socket bound to P2P network");
                }
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
        com.kisslink.profile.ProfileStore ps = com.kisslink.profile.ProfileStore.get(this);
        String selfName = ps.name();
        byte[] selfAvatar = ps.avatarThumbBytes();

        peer = new PeerConnection(this, socket, new PeerConnection.Listener() {
            @Override public void onItemCompleted(boolean sent, String name, long size,
                                                  long avgSpeedBps, boolean success, byte itemType,
                                                  @Nullable String contentUri, @Nullable String mime,
                                                  long batchId) {
                String dir = sent ? "SEND" : "RECEIVE";
                String peerName = currentPeerName();
                TransferRepository repo = TransferRepository.getInstance(FileTransferService.this);
                repo.insert(repo.buildRecord(dir, name, size, success, avgSpeedBps,
                        contentUri, peerName, mime, batchId));
            }
            @Override public void onDisconnected() {
                Log.i(TAG, "Peer disconnected");
                mainHandler.post(() -> {
                    connectedPeerToken = null;
                    peerNameFromHello = null;
                    peerAvatarBytes = null;
                    setTransferring(false);
                    teardownPeer();
                    sessionLd.postValue(SessionState.idle());
                });
            }
            @Override public void onPeerProfile(@Nullable String name, @Nullable byte[] avatarThumb) {
                peerNameFromHello = name;
                peerAvatarBytes = avatarThumb;
                mainHandler.post(() -> {
                    if (peer != null && peer.isAlive())
                        sessionLd.setValue(SessionState.of(SessionState.Phase.CONNECTED));
                });
            }
            @Override public void onCardReceived(@Nullable byte[] vcard, String name) {
                incomingCardLd.postValue(vcard);
            }
        }, selfName, selfAvatar);

        peerProgressSrc = peer.getProgress();
        peerProgressObs = tp -> {
            if (tp == null) return;
            sessionLd.postValue(SessionState.fromTransfer(tp));
            boolean now = (tp.phase == TransferProgress.Phase.TRANSFERRING);
            boolean ended = transferring && !now;
            setTransferring(now);
            if (ended) {
                onTransferCompleted();
            }
        };
        new Handler(getMainLooper()).post(() -> {
            if (peerProgressSrc != null && peerProgressObs != null)
                peerProgressSrc.observeForever(peerProgressObs);
        });

        peer.start();
        wakeLockManager.acquire();
        notificationHelper.update("已連線", 0);
        sessionLd.postValue(SessionState.of(SessionState.Phase.CONNECTED));
        Log.i(TAG, "PeerConnection established (groupOwner=" + isGroupOwner + ")");
    }

    @Nullable
    private String currentPeerName() {
        if (peerNameFromHello != null && !peerNameFromHello.isEmpty()) return peerNameFromHello;
        return connectedPeerToken != null && !connectedPeerToken.deviceName.isEmpty()
                ? connectedPeerToken.deviceName : null;
    }

    private void teardownPeer() {
        if (peerProgressSrc != null && peerProgressObs != null) {
            final LiveData<TransferProgress> src = peerProgressSrc;
            final Observer<TransferProgress> obs = peerProgressObs;
            new Handler(getMainLooper()).post(() -> src.removeObserver(obs));
        }
        peerProgressSrc = null;
        peerProgressObs = null;
        if (peer != null) { peer.close(); peer = null; }
        wakeLockManager.release();
    }
}
