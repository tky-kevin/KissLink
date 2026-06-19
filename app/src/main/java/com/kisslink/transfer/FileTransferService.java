package com.kisslink.transfer;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.MainThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.data.repository.TransferRepository;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.pairing.LocalPairing;
import com.kisslink.pairing.PairingCoordinator;
import com.kisslink.pairing.PairingToken;
import com.kisslink.wifidirect.WifiDirectManager;

import java.net.Socket;
import java.util.List;

/**
 * 檔案傳輸前景 Service —— 薄殼，委派業務邏輯至 SessionManager。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class FileTransferService extends Service {

    private static final String TAG = "FileTransferService";

    /**
     * Dirty-state 重新配對前的沉澱延遲（ms）：拆掉舊 session 後，需給 Wi-Fi Direct 框架
     * 時間移除群組、釋放 P2P 介面，再建立新 coordinator，否則殘留狀態會干擾下一次選舉。
     */
    private static final long RESET_SETTLE_MS = 1800L;

    // ── Core components ───────────────────────────────────────
    private WifiDirectManager wifi;
    private PairingCoordinator coordinator;
    @Nullable private PeerConnection peer;
    private boolean isGroupOwner = false;
    private volatile boolean peerStarting = false;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

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

        public PairingToken localToken() { return coordinator.localToken(); }

        @Nullable public String connectedPeerName() { return sessionMgr.currentPeerName(); }

        @Nullable public byte[] connectedPeerAvatar() { return sessionMgr.getPeerAvatarBytes(); }

        public androidx.lifecycle.LiveData<byte[]> getIncomingCard() { return incomingCardLd; }

        public void clearIncomingCard() { incomingCardLd.postValue(null); }

        public void onNfcLatchedAsReader(@NonNull PairingToken peerToken) {
            handleLatch(peerToken, () -> coordinator.onLatchedAsReader(peerToken));
        }

        public void onNfcLatchedAsTag() {
            handleLatch(null, () -> coordinator.onLatchedAsTag());
        }

        public void sendItems(@NonNull List<SendItem> items) {
            if (peer != null) peer.sendItems(items);
            else Log.w(TAG, "sendItems before connected");
        }

        public void cancel() {
            teardownPeer();
            stopSelf();
        }

        public void disconnect() {
            mainHandler.post(() -> {
                if (sessionMgr.isPendingReset()) return;
                teardownSession();
                createCoordinator();
                sessionMgr.transitionTo(SessionState.idle());
                Log.i(TAG, "User disconnect → full teardown");
            });
        }

        public void interruptPairing() {
            mainHandler.post(() -> {
                if (sessionMgr.isPendingReset()) return;
                sessionMgr.resetSession();
                peerStarting = false;
                teardownPeer();
                if (coordinator != null) coordinator.cancelLightweight();
                sessionMgr.transitionTo(SessionState.idle());
                Log.i(TAG, "Pairing interrupted → IDLE");
            });
        }
    }

    private final MutableLiveData<byte[]> incomingCardLd = new MutableLiveData<>(null);

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
        final int gen = sessionMgr.nextSessionGen();
        // 在建 coordinator（讀 LocalPairing.current()）與設 HCE token 前，刷新本機 5GHz
        // 開群組能力旗標，讓對方碰一下讀到的 token 反映最新 Wi-Fi 狀態，GO 選舉才準。
        LocalPairing.setCanHost5G(WifiDirectManager.canHostFastGroup(this));
        coordinator = new PairingCoordinator(this, wifi, new PairingCoordinator.Listener() {
            @Override public void onPhase(@NonNull PairingCoordinator.Phase phase) {
                sessionMgr.onPhase(phase, gen);
            }
            @Override public void onPaired(boolean groupOwner) {
                sessionMgr.onPaired(groupOwner, gen, coordinator);
                isGroupOwner = groupOwner;
                establishPeer(groupOwner);
            }
            @Override public void onError(@NonNull String message) {
                sessionMgr.onError(message, gen);
            }
            @Override public void onSlowLinkWarning() {
                android.widget.Toast.makeText(FileTransferService.this,
                        "有裝置連在 2.4GHz Wi-Fi，傳輸會較慢；兩台都關閉 Wi-Fi 或改連 5GHz 可大幅加速",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        KissLinkHCEService.setActiveToken(coordinator.localToken());
    }

    @MainThread
    private void handleLatch(@Nullable PairingToken tappedPeer, @NonNull Runnable deliver) {
        // Wi-Fi 已連、PeerConnection 仍在背景建 socket 的視窗期（coordinator 已 finished、peer 尚未 alive）：
        // 此時兩機正貼著，極易再觸發 NFC latch。若放行會被判 dirty 而拆掉這條健康的連線、
        // 退回 LATCHED/LINKING（即「跳到 Wi-Fi Direct 又跳回 NFC/BLE」）。建立中一律忽略。
        if (peerStarting) {
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
                mainHandler.post(() -> android.widget.Toast.makeText(
                        this, "傳輸完成後切換至新裝置",
                        android.widget.Toast.LENGTH_LONG).show());
                return;
            case PROCEED:
            default:
                break;
        }

        boolean dirty = alive
                || coordinator.isFinished()
                || (wifi != null && wifi.isActive());

        if (!dirty) {
            deliver.run();
            return;
        }

        sessionMgr.transitionTo(SessionState.of(SessionState.Phase.RESETTING));
        teardownSession();
        Log.i(TAG, "Dirty state → teardown + settle before re-pair");
        mainHandler.postDelayed(() -> {
            createCoordinator();
            sessionMgr.transitionTo(SessionState.idle());
            deliver.run();
        }, RESET_SETTLE_MS);
    }

    private void teardownSession() {
        teardownPeer();
        if (coordinator != null) coordinator.reset();
        if (wifi != null) { wifi.removeGroup(); wifi.reset(); }
        isGroupOwner = false;
        sessionMgr.resetSession();
        Log.i(TAG, "Session torn down");
    }

    // ══════════════════════════════════════════════════════════
    //  建立雙向 socket → PeerConnection
    // ══════════════════════════════════════════════════════════

    private void establishPeer(boolean groupOwner) {
        if (peerStarting || peer != null) return;
        peerStarting = true;
        PeerConnector.Callback cb = new PeerConnector.Callback() {
            @Override public void onSocketReady(Socket socket) {
                startPeer(socket);
                peerStarting = false;
            }
            @Override public void onError(String message) {
                peerStarting = false;
                sessionMgr.transitionTo(SessionState.error(message));
            }
        };
        if (groupOwner) {
            peerConnector.acceptAsServer(cb);
        } else {
            peerConnector.connectAsClient(wifi.getClientNetwork(), cb);
        }
    }

    private void logTransfer(boolean sent, String name, long size, long avgSpeedBps,
                             boolean success, byte itemType, @Nullable String contentUri,
                             @Nullable String mime, long batchId) {
        String dir = sent ? "SEND" : "RECEIVE";
        String peerName = sessionMgr.currentPeerName();
        TransferRepository repo = TransferRepository.getInstance(this);
        repo.insert(repo.buildRecord(dir, name, size, success, avgSpeedBps,
                contentUri, peerName, mime, batchId));
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
                logTransfer(sent, name, size, avgSpeedBps, success, itemType,
                        contentUri, mime, batchId);
            }
            @Override public void onDisconnected() {
                Log.i(TAG, "Peer disconnected");
                mainHandler.post(() -> {
                    sessionMgr.clearPeerIdentity();
                    teardownPeer();
                    sessionMgr.transitionTo(SessionState.idle());
                });
            }
            @Override public void onPeerProfile(@Nullable String name, @Nullable byte[] avatarThumb) {
                sessionMgr.setPeerProfile(name, avatarThumb);
                mainHandler.post(() -> {
                    if (peer != null && peer.isAlive())
                        sessionMgr.transitionTo(SessionState.of(SessionState.Phase.CONNECTED));
                });
            }
            @Override public void onCardReceived(@Nullable byte[] vcard, String name) {
                incomingCardLd.postValue(vcard);
            }
        }, selfName, selfAvatar);

        sessionMgr.setupProgressObserver(peer.getProgress(), mainHandler, this::onTransferCompleted);

        peer.start();
        wakeLockManager.acquire();
        idleManager.setTransferring(true);
        notificationHelper.update("已連線", 0);
        sessionMgr.transitionTo(SessionState.of(SessionState.Phase.CONNECTED));
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
        if (peer != null) { peer.close(); peer = null; }
        wakeLockManager.release();
        idleManager.setTransferring(false);
    }
}
