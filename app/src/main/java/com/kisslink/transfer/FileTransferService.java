package com.kisslink.transfer;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import com.kisslink.data.repository.TransferRepository;
import com.kisslink.diag.FlightRecorder;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.pairing.LocalPairing;
import com.kisslink.pairing.PairingCoordinator;
import com.kisslink.pairing.PairingToken;
import com.kisslink.wifidirect.WifiDirectManager;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 檔案傳輸前景 Service —— 薄殼，委派業務邏輯至 SessionManager。 */
public class FileTransferService extends Service {

    private static final String TAG = "FileTransferService";

    /**
     * Dirty-state 重新配對前的沉澱延遲（ms）：拆掉舊 session 後，需給 Wi-Fi Direct 框架 時間移除群組、釋放 P2P 介面，再建立新
     * coordinator，否則殘留狀態會干擾下一次選舉。
     */
    private static final long RESET_SETTLE_MS = 1800L;

    // ── Core components ───────────────────────────────────────
    private WifiDirectManager wifi;
    private PairingCoordinator coordinator;
    // volatile: assigned on the PeerConnector bg thread (startPeer ← onSocketReady) but read on
    // the main thread (sendItems / handleLatch / establishPeer) — without it those reads can see
    // a stale null or a half-constructed PeerConnection.
    @Nullable private volatile PeerConnection peer;
    private boolean isGroupOwner = false;
    private final AtomicBoolean peerStarting = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

    // ── Resume transfer state ─────────────────────────────────
    // Volatile: written on main thread (onDisconnected post), read on PeerConnector bg thread
    // (startPeer).
    @Nullable private volatile PeerConnection.PendingSend pendingSend;
    @Nullable private volatile PeerConnection.PendingRecv pendingRecv;

    // Set true by explicit user teardown (cancel / disconnect / interruptPairing) so the async
    // onDisconnected those calls trigger DROPS the resume state instead of saving it — otherwise a
    // user-cancelled transfer would silently auto-resume on the next pairing. Deliberately NOT set
    // by handleLatch's dirty reconnect, which must preserve resume so a same-peer re-tap after a
    // network drop can continue where it left off.
    private volatile boolean discardResumeState = false;

    // ── Extracted managers ─────────────────────────────────────
    private SessionManager sessionMgr;
    private IdleTeardownManager idleManager;
    private ServiceNotificationHelper notificationHelper;
    private WakeLockManager wakeLockManager;
    private final PeerConnector peerConnector = new PeerConnector();

    private final TransferBinder binder = new TransferBinder();

    public static Intent intent(Context ctx) {
        return new Intent(ctx, FileTransferService.class);
    }

    // ══════════════════════════════════════════════════════════
    //  Binder
    // ══════════════════════════════════════════════════════════

    public class TransferBinder extends Binder {
        public androidx.lifecycle.LiveData<SessionState> getSessionState() {
            return sessionMgr.getState();
        }

        public PairingToken localToken() {
            return coordinator.localToken();
        }

        @Nullable
        public String connectedPeerName() {
            return sessionMgr.currentPeerName();
        }

        @Nullable
        public byte[] connectedPeerAvatar() {
            return sessionMgr.getPeerAvatarBytes();
        }

        public androidx.lifecycle.LiveData<byte[]> getIncomingCard() {
            return incomingCardLd;
        }

        public void clearIncomingCard() {
            incomingCardLd.postValue(null);
        }

        /** 接收方：每收完一個檔案發射一筆（含存檔 Uri），供接收列表把該列變成可點開。 */
        public androidx.lifecycle.LiveData<ReceivedItem> getReceivedItem() {
            return receivedItemLd;
        }

        public void onNfcLatchedAsReader(@NonNull PairingToken peerToken) {
            handleLatch(peerToken, () -> coordinator.onLatchedAsReader(peerToken));
        }

        public void onNfcLatchedAsTag() {
            handleLatch(null, () -> coordinator.onLatchedAsTag());
        }

        public void sendItems(@NonNull List<SendItem> items) {
            final PeerConnection p = peer;
            if (p != null) p.sendItems(items);
            else Log.w(TAG, "sendItems before connected");
        }

        public void cancel() {
            discardResumeState = true;
            teardownPeer();
            stopSelf();
        }

        public void disconnect() {
            mainHandler.post(
                    () -> {
                        if (sessionMgr.isPendingReset()) return;
                        discardResumeState = true;
                        teardownSession();
                        refreshLocalCapabilities(); // 閒置邊界:已回待機,為下一場抓最新 5GHz 能力
                        createCoordinator();
                        sessionMgr.toIdle();
                        Log.i(TAG, "User disconnect → full teardown");
                    });
        }

        public void interruptPairing() {
            mainHandler.post(
                    () -> {
                        if (sessionMgr.isPendingReset()) return;
                        discardResumeState = true;
                        sessionMgr.resetSession();
                        peerStarting.set(false);
                        teardownPeer();
                        if (coordinator != null) coordinator.cancelLightweight();
                        sessionMgr.toIdle();
                        Log.i(TAG, "Pairing interrupted → IDLE");
                    });
        }
    }

    private final MutableLiveData<byte[]> incomingCardLd = new MutableLiveData<>(null);
    private final MutableLiveData<ReceivedItem> receivedItemLd = new MutableLiveData<>(null);

    /** 接收完成的單一檔案（供 UI 接收列表顯示與點開）。 */
    public static final class ReceivedItem {
        public final String name;
        @Nullable public final String contentUri;
        @Nullable public final String mime;
        public final long batchId;

        public ReceivedItem(
                String name, @Nullable String contentUri, @Nullable String mime, long batchId) {
            this.name = name;
            this.contentUri = contentUri;
            this.mime = mime;
            this.batchId = batchId;
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
        sessionMgr = new SessionManager();
        idleManager = new IdleTeardownManager(mainHandler, this::stopSelf);
        notificationHelper = new ServiceNotificationHelper(this);
        wakeLockManager = new WakeLockManager(this);
        startForeground(ServiceNotificationHelper.NOTIF_ID, notificationHelper.build("準備配對…", 0));
        wifi = new WifiDirectManager(this);
        wifi.registerReceiver(this);
        wifi.removeGroup();
        refreshLocalCapabilities(); // 閒置邊界:服務初建,抓一份最新 5GHz 能力供本場曝光
        createCoordinator();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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

    /**
     * 刷新本機酬載(5GHz 開群組能力)——<b>只在閒置邊界呼叫</b>(onCreate / 使用者主動斷線), 使下一場曝光的 canHost5G 反映最新 Wi-Fi 狀態。
     *
     * <p>刻意<b>不</b>放進 {@link #createCoordinator()}:dirty 重建會在「碰一下之後、配對進行中」 呼叫
     * createCoordinator,此時若刷新,canHost5G 可能與對方剛經 NFC 讀到的快照不同 → GO 選舉不反對稱。只換酬載已不影響
     * nonce/goIntent(身分恆定,見 {@link LocalPairing})。
     */
    private void refreshLocalCapabilities() {
        LocalPairing.setCanHost5G(WifiDirectManager.canHostFastGroup(this));
    }

    private void createCoordinator() {
        final int gen = sessionMgr.nextSessionGen();
        coordinator =
                new PairingCoordinator(
                        this,
                        wifi,
                        new PairingCoordinator.Listener() {
                            @Override
                            public void onPhase(@NonNull PairingCoordinator.Phase phase) {
                                sessionMgr.onPhase(phase, gen);
                            }

                            @Override
                            public void onPaired(boolean groupOwner) {
                                sessionMgr.onPaired(groupOwner, gen, coordinator);
                                isGroupOwner = groupOwner;
                                establishPeer(groupOwner);
                            }

                            @Override
                            public void onError(@NonNull String message) {
                                sessionMgr.onError(message, gen);
                            }

                            @Override
                            public void onSlowLinkWarning() {
                                android.widget.Toast.makeText(
                                                FileTransferService.this,
                                                "有裝置連在 2.4GHz Wi-Fi，傳輸會較慢；兩台都關閉 Wi-Fi 或改連 5GHz 可大幅加速",
                                                android.widget.Toast.LENGTH_LONG)
                                        .show();
                            }
                        });
        KissLinkHCEService.setActiveToken(coordinator.localToken());
    }

    @MainThread
    private void handleLatch(@Nullable PairingToken tappedPeer, @NonNull Runnable deliver) {
        // Wi-Fi 已連、PeerConnection 仍在背景建 socket 的視窗期（coordinator 已 finished、peer 尚未 alive）：
        // 此時兩機正貼著，極易再觸發 NFC latch。若放行會被判 dirty 而拆掉這條健康的連線、
        // 退回 LATCHED/LINKING（即「跳到 Wi-Fi Direct 又跳回 NFC/BLE」）。建立中一律忽略。
        if (peerStarting.get()) {
            Log.d(TAG, "Latch ignored: peer connection establishing");
            return;
        }
        boolean alive = (peer != null && peer.isAlive());
        SessionManager.LatchResult result = sessionMgr.handleLatch(tappedPeer, coordinator, alive);

        switch (result) {
            case IGNORED:
            case RESUME:
                return;
            case QUEUED_SWITCH:
                mainHandler.post(
                        () ->
                                android.widget.Toast.makeText(
                                                this,
                                                "傳輸完成後切換至新裝置",
                                                android.widget.Toast.LENGTH_LONG)
                                        .show());
                return;
            case PROCEED:
            default:
                break;
        }

        boolean dirty = alive || coordinator.isFinished() || (wifi != null && wifi.isActive());

        if (!dirty) {
            deliver.run();
            return;
        }

        sessionMgr.toResetting();
        teardownSession();
        Log.i(TAG, "Dirty state → teardown + settle before re-pair");
        mainHandler.postDelayed(
                () -> {
                    createCoordinator();
                    sessionMgr.toIdle();
                    deliver.run();
                },
                RESET_SETTLE_MS);
    }

    private void teardownSession() {
        teardownPeer();
        if (coordinator != null) coordinator.reset();
        if (wifi != null) {
            wifi.removeGroup();
            wifi.reset();
        }
        isGroupOwner = false;
        sessionMgr.resetSession();
        Log.i(TAG, "Session torn down");
    }

    // ══════════════════════════════════════════════════════════
    //  建立雙向 socket → PeerConnection
    // ══════════════════════════════════════════════════════════

    private void establishPeer(boolean groupOwner) {
        if (!peerStarting.compareAndSet(false, true)) return; // already starting — bail atomically
        if (peer != null) {
            peerStarting.set(false);
            return;
        }
        FlightRecorder.seq(TAG, "establishing peer socket (groupOwner=" + groupOwner + ")");
        PeerConnector.Callback cb =
                new PeerConnector.Callback() {
                    @Override
                    public void onSocketReady(Socket socket) {
                        if (peer != null) {
                            try {
                                socket.close();
                            } catch (IOException ignored) {
                            }
                            peerStarting.set(false);
                            return;
                        }
                        startPeer(socket);
                        peerStarting.set(false);
                    }

                    @Override
                    public void onError(String message) {
                        peerStarting.set(false);
                        FlightRecorder.event(TAG, "socket establish failed: " + message);
                        FlightRecorder.dump(FileTransferService.this, "socket establish failed");
                        sessionMgr.toError(message);
                    }
                };
        if (groupOwner) {
            peerConnector.acceptAsServer(cb);
        } else {
            // 傳 supplier(非當下值):P2P 網路是 CONNECTED 後才非同步綁定的,
            // connectAsClient 內每次重試重抓,綁定一就緒即走 bindSocket。
            peerConnector.connectAsClient(wifi::getClientNetwork, cb);
        }
    }

    private void logTransfer(
            boolean sent,
            String name,
            long size,
            long avgSpeedBps,
            boolean success,
            byte itemType,
            @Nullable String contentUri,
            @Nullable String mime,
            long batchId) {
        String dir = sent ? "SEND" : "RECEIVE";
        String peerName = sessionMgr.currentPeerName();
        TransferRepository repo = TransferRepository.getInstance(this);
        repo.insert(
                repo.buildRecord(
                        dir,
                        name,
                        size,
                        success,
                        avgSpeedBps,
                        contentUri,
                        peerName,
                        mime,
                        batchId));
    }

    private void startPeer(@NonNull Socket socket) {
        // Fresh connection — clear any stale discard flag (e.g. a user disconnect that fired with
        // no live peer) so it can't suppress this connection's first real disconnect.
        discardResumeState = false;
        com.kisslink.profile.ProfileStore ps = com.kisslink.profile.ProfileStore.get(this);
        String selfName = ps.name();
        byte[] selfAvatar = ps.avatarThumbBytes();

        // Use a box so the onDisconnected lambda can reference the specific PeerConnection
        // instance that fired the event (peer field may be null'd by teardownPeer by then).
        final PeerConnection[] box = {null};
        peer =
                new PeerConnection(
                        this,
                        socket,
                        new PeerConnection.Listener() {
                            @Override
                            public void onItemCompleted(
                                    boolean sent,
                                    String name,
                                    long size,
                                    long avgSpeedBps,
                                    boolean success,
                                    byte itemType,
                                    @Nullable String contentUri,
                                    @Nullable String mime,
                                    long batchId) {
                                logTransfer(
                                        sent,
                                        name,
                                        size,
                                        avgSpeedBps,
                                        success,
                                        itemType,
                                        contentUri,
                                        mime,
                                        batchId);
                                // 接收方收完一檔 → 通知 UI 把該列補上 Uri（可點開）。
                                if (!sent && success && contentUri != null) {
                                    receivedItemLd.postValue(
                                            new ReceivedItem(name, contentUri, mime, batchId));
                                }
                                // Transfer completed successfully — clear any stale resume state
                                mainHandler.post(
                                        () -> {
                                            pendingSend = null;
                                            pendingRecv = null;
                                        });
                            }

                            @Override
                            public void onDisconnected() {
                                // Extract resume state BEFORE teardown so the next connection can
                                // pick it up
                                PeerConnection p = box[0];
                                PeerConnection.PendingSend ps2 =
                                        p != null ? p.takePendingSend() : null;
                                PeerConnection.PendingRecv pr2 =
                                        p != null ? p.takePendingRecv() : null;
                                FlightRecorder.seq(
                                        TAG,
                                        "peer disconnected"
                                                + (ps2 != null ? " (resume send pending)" : "")
                                                + (pr2 != null ? " (resume recv pending)" : ""));
                                Log.i(TAG, "Peer disconnected");
                                mainHandler.post(
                                        () -> {
                                            if (discardResumeState) {
                                                // User-initiated teardown: drop resume state and
                                                // delete any kept partial file, rather than
                                                // auto-resuming a transfer the user cancelled.
                                                discardResumeState = false;
                                                if (pr2 != null && pr2.target != null) {
                                                    try {
                                                        getContentResolver()
                                                                .delete(pr2.target, null, null);
                                                    } catch (Exception ignored) {
                                                    }
                                                }
                                                pendingSend = null;
                                                pendingRecv = null;
                                            } else {
                                                pendingSend = ps2;
                                                pendingRecv = pr2;
                                            }
                                            sessionMgr.clearPeerIdentity();
                                            teardownPeer();
                                            sessionMgr.toIdle();
                                        });
                            }

                            @Override
                            public void onPeerProfile(
                                    @Nullable String name, @Nullable byte[] avatarThumb) {
                                sessionMgr.setPeerProfile(name, avatarThumb);
                                mainHandler.post(
                                        () -> {
                                            if (peer != null && peer.isAlive())
                                                sessionMgr.toConnected();
                                        });
                            }

                            @Override
                            public void onCardReceived(@Nullable byte[] vcard, String name) {
                                incomingCardLd.postValue(vcard);
                            }
                        },
                        selfName,
                        selfAvatar);
        box[0] = peer;

        // Inject resume state from the previous disconnected transfer (if any)
        if (pendingSend != null || pendingRecv != null) {
            peer.setPendingState(pendingSend, pendingRecv);
            pendingSend = null;
            pendingRecv = null;
            Log.i(TAG, "Resume state injected into new PeerConnection");
        }

        sessionMgr.setupProgressObserver(
                peer.getProgress(), mainHandler, this::onTransferCompleted);

        peer.start();
        wakeLockManager.acquire();
        idleManager.setTransferring(true);
        notificationHelper.update("已連線", 0);
        sessionMgr.toConnected();
        FlightRecorder.seq(TAG, "peer connection established (groupOwner=" + isGroupOwner + ")");
        Log.i(TAG, "PeerConnection established (groupOwner=" + isGroupOwner + ")");
    }

    private void onTransferCompleted() {
        if (sessionMgr.hasPendingSwitch()) {
            // TODO: implement queued peer switch
        }
        idleManager.scheduleIfIdle();
    }

    private void teardownPeer() {
        sessionMgr.teardownProgressObserver(mainHandler);
        if (peer != null) {
            peer.close();
            peer = null;
        }
        wakeLockManager.release();
        idleManager.setTransferring(false);
    }
}
