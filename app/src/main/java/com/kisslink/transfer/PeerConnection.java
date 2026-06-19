package com.kisslink.transfer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 雙向 peer 傳輸通道——配對連線建立後,雙方各持一個 {@link Socket}。
 *
 * <p>本類別是薄協調器：持有 socket、write lock、進度 LiveData，並管理
 * {@link PeerSender}（送端執行緒）和 {@link PeerReceiver}（收端執行緒）的生命週期。
 *
 * <p>共用型別 {@link Chunk}、{@link OutItem}、{@link #STOP} 供同套件的
 * PeerSender / PeerReceiver 直接存取（package-private）。
 */
public class PeerConnection {

    private static final String TAG = "PeerConnection";

    static final Object STOP = new Object();
    static final int CHUNK_POOL = 4;

    static final class Chunk {
        final byte[] data; int len;
        Chunk(int cap) { this.data = new byte[cap]; }
    }
    static final Chunk CHUNK_EOF = new Chunk(0);

    static final class OutItem {
        final SendItem item; final long batchId; final int index; final int count;
        OutItem(SendItem item, long batchId, int index, int count) {
            this.item = item; this.batchId = batchId; this.index = index; this.count = count;
        }
    }

    public interface Listener {
        void onItemCompleted(boolean sent, String name, long size, long avgSpeedBps,
                             boolean success, byte itemType,
                             @Nullable String contentUri, @Nullable String mime, long batchId);
        void onDisconnected();
        void onPeerProfile(@Nullable String name, @Nullable byte[] avatarThumb);
        void onCardReceived(@Nullable byte[] vcard, String name);
    }

    // ── Shared state ──────────────────────────────────────────
    private final Context context;
    private final Socket socket;
    private final Listener listener;
    @Nullable private final String selfName;
    @Nullable private final byte[] selfAvatarThumb;
    private final boolean verbose;

    private final BlockingQueue<Object> outQueue = new LinkedBlockingQueue<>();
    private final MutableLiveData<TransferProgress> progressLd =
            new MutableLiveData<>(TransferProgress.connected());

    private static final int HEARTBEAT_INTERVAL_MS = 2500;
    private static final int LIVENESS_TIMEOUT_MS   = 7000;

    private volatile boolean running = false;
    private Thread senderThread, heartbeatThread;
    private PeerReceiver receiver;
    private final Object writeLock = new Object();
    @Nullable private volatile BufferedOutputStream out;

    // ── Constructors ──────────────────────────────────────────

    public PeerConnection(@NonNull Context context, @NonNull Socket socket, @NonNull Listener listener) {
        this(context, socket, listener, null, null);
    }

    public PeerConnection(@NonNull Context context, @NonNull Socket socket, @NonNull Listener listener,
                          @Nullable String selfName, @Nullable byte[] selfAvatarThumb) {
        this.context  = context.getApplicationContext();
        this.socket   = socket;
        this.listener = listener;
        this.selfName = selfName;
        this.selfAvatarThumb = selfAvatarThumb;
        this.verbose  = (this.context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    // ── Public API ────────────────────────────────────────────

    public LiveData<TransferProgress> getProgress() { return progressLd; }

    public boolean isAlive() { return running; }

    public void start() {
        if (running) return;
        running = true;
        try {
            socket.setTcpNoDelay(true);
            try {
                socket.setSendBufferSize(1 << 20);
                socket.setReceiveBufferSize(1 << 20);
            } catch (Exception ignored) {}
            out = new BufferedOutputStream(socket.getOutputStream());
            socket.setSoTimeout(LIVENESS_TIMEOUT_MS);
        } catch (IOException e) {
            Log.e(TAG, "start failed", e);
            running = false;
            listener.onDisconnected();
            return;
        }

        PeerSender.FrameWriter fw = (header, payload, off, len) -> writeFrame(header, payload, off, len);
        PeerSender sender = new PeerSender(context, outQueue, fw, progressLd, listener,
                selfName, selfAvatarThumb, verbose);
        receiver = new PeerReceiver(context, socket, progressLd, listener, verbose);
        receiver.setRunning(true); // 對稱於 close() 的 setRunning(false)；漏設會讓 run() 迴圈一次都不跑→立即 onDisconnected

        senderThread    = new Thread(sender, "peer-sender");
        Thread reader   = new Thread(receiver, "peer-reader");
        heartbeatThread = new Thread(this::heartbeatLoop, "peer-heartbeat");
        reader.start();
        senderThread.start();
        heartbeatThread.start();
        if (verbose) logLinkInfo();
        Log.i(TAG, "PeerConnection started");
    }

    public void sendItems(@NonNull List<SendItem> items) {
        long batch = System.currentTimeMillis();
        int n = items.size();
        for (int i = 0; i < n; i++) outQueue.offer(new OutItem(items.get(i), batch, i, n));
    }

    public void close() {
        running = false;
        if (receiver != null) receiver.setRunning(false);
        outQueue.offer(STOP);
        if (heartbeatThread != null) heartbeatThread.interrupt();
        try { socket.close(); } catch (IOException ignored) {}
        joinQuietly(senderThread);
        joinQuietly(heartbeatThread);
        Log.d(TAG, "PeerConnection closed");
    }

    // ── Write lock (shared by heartbeat + sender) ─────────────

    private void writeFrame(byte[] header, @Nullable byte[] payload, int off, int len) throws IOException {
        synchronized (writeLock) {
            if (out == null) throw new IOException("stream closed");
            out.write(header);
            if (payload != null && len > 0) out.write(payload, off, len);
            out.flush();
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────

    private void heartbeatLoop() {
        byte[] hb = TransferProtocol.encodeHeader(TransferProtocol.makeHeartbeat());
        while (running) {
            try { Thread.sleep(HEARTBEAT_INTERVAL_MS); } catch (InterruptedException e) { break; }
            if (!running) break;
            try { writeFrame(hb, null, 0, 0); }
            catch (IOException e) { Log.w(TAG, "heartbeat failed: " + e.getMessage()); break; }
        }
    }

    // ── Utilities ─────────────────────────────────────────────

    private static void joinQuietly(@Nullable Thread t) {
        if (t == null) return;
        try { t.join(2000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void logLinkInfo() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return;
            Log.i(TAG, "LINK linkSpeed=" + wi.getLinkSpeed() + "Mbps rssi=" + wi.getRssi()
                    + "dBm freq=" + wi.getFrequency() + "MHz");
        } catch (Exception ignored) {}
    }
}
