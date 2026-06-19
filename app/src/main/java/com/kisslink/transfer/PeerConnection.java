package com.kisslink.transfer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 雙向 peer 傳輸通道——配對連線建立後,雙方各持一個 {@link Socket}。
 *
 * <p>TCP 為全雙工:本端 {@code out} → 對端 {@code in} 是一條獨立位元流,反向亦然,
 * 互不干擾。因此只需:
 * <ul>
 *   <li><b>sender thread</b>:從佇列取 {@link SendItem},寫 META + CHUNK×N + COMPLETE 到 {@code out}。</li>
 *   <li><b>reader thread</b>:從 {@code in} 讀對方送來的項目,存到 下載/KissLink。</li>
 * </ul>
 * 任一端隨時可送、可多輪,真正對等。無 ACK——TCP 保證順序送達,CRC32 驗每個 chunk。
 *
 * <p>歷史紀錄走同步回呼 {@link Listener#onItemCompleted}(不經會合併的 LiveData),
 * 保證一項一次、不漏不重;UI 進度走 {@link #getProgress()} LiveData。
 */
public class PeerConnection {

    private static final String TAG = "PeerConnection";
    private static final String SAVE_DIR = Environment.DIRECTORY_DOWNLOADS + "/KissLink";

    public interface Listener {
        /**
         * 一個項目傳輸結束(成功或失敗)時同步呼叫一次。
         * @param contentUri 可開啟的位置：接收端為存檔 content uri、傳送端為來源 uri；無則 null
         * @param mime       MIME 類型
         * @param batchId    同一次傳送/接收 burst 的批次識別（分塊用）
         */
        void onItemCompleted(boolean sent, String name, long size, long avgSpeedBps,
                             boolean success, byte itemType,
                             @Nullable String contentUri, @Nullable String mime, long batchId);
        /** 連線中斷(對方關閉 / 錯誤)。 */
        void onDisconnected();
        /** 收到對方 HELLO 帶來的名片資料（名稱 + 頭像縮圖，皆可空）。 */
        void onPeerProfile(@Nullable String name, @Nullable byte[] avatarThumb);
        /** 收到名片(vCard)項目，帶完整 vCard bytes（供 UI 以名片介面開啟）。 */
        void onCardReceived(@Nullable byte[] vcard, String name);
    }

    private final Context context;
    private final Socket socket;
    private final Listener listener;

    /** 本端名片（連上後經 HELLO 送給對方顯示）。 */
    @Nullable private final String selfName;
    @Nullable private final byte[] selfAvatarThumb;

    /** 是否輸出 PERF/LINK 量測 log——僅 debuggable build，正式版不輸出。 */
    private final boolean verbose;

    private final BlockingQueue<Object> outQueue = new LinkedBlockingQueue<>();
    private static final Object STOP = new Object();

    // ── Pipelined I/O：可重用緩衝持有者 + 池大小 ──────────────────
    /** 送/收 pipeline 的 bounded buffer pool 大小（×CHUNK_SIZE）。4 足以讓磁碟與網路重疊。 */
    private static final int CHUNK_POOL = 4;
    /** 可重用緩衝持有者（含實際長度），避免每 chunk 配置 512KB 造成 GC 壓力。 */
    private static final class Chunk {
        final byte[] data; int len;
        Chunk(int cap) { this.data = new byte[cap]; }
    }
    /** 送端 prefetch 佇列的「讀畢」哨兵。 */
    private static final Chunk CHUNK_EOF = new Chunk(0);

    // 接收端批次：META 到達時若距上次活動超過此間隔即視為新批次
    private static final long RECV_BATCH_GAP_MS = 4000;
    private long recvBatchId = 0;
    private long lastRecvActivity = 0;

    /** 一筆待送：項目 + 其所屬批次 id + 在批次中的序號/總數（供整包進度與「最後一筆=ALL_DONE」判定）。 */
    private static final class OutItem {
        final SendItem item; final long batchId; final int index; final int count;
        OutItem(SendItem item, long batchId, int index, int count) {
            this.item = item; this.batchId = batchId; this.index = index; this.count = count;
        }
    }

    private final MutableLiveData<TransferProgress> progressLd =
            new MutableLiveData<>(TransferProgress.connected());

    private static final int HEARTBEAT_INTERVAL_MS = 2500;
    private static final int LIVENESS_TIMEOUT_MS   = 7000;

    private volatile boolean running = false;
    private Thread readerThread, senderThread, heartbeatThread;
    private final Object writeLock = new Object();
    // volatile：於 start()（thread.start() 之前）寫入一次，writeFrame 在 writeLock 內讀取。
    // happens-before 已涵蓋初始可見性；volatile 為廉價的明示防禦，避免日後若有人在執行緒
    // 啟動後重設 out 時悄然引入 race。
    @Nullable private volatile BufferedOutputStream out;

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

    public LiveData<TransferProgress> getProgress() { return progressLd; }

    /** 連線是否仍存活(心跳/資料在 {@value #LIVENESS_TIMEOUT_MS}ms 內有往來)。 */
    public boolean isAlive() { return running; }

    public void start() {
        if (running) return;
        running = true;
        try {
            socket.setTcpNoDelay(true); // 標頭等小封包不被 Nagle 延遲,改善吞吐爬升
            try {
                socket.setSendBufferSize(1 << 20);    // 1MB:讓 TCP 視窗更快爬到滿速
                socket.setReceiveBufferSize(1 << 20);
            } catch (Exception ignored) {}
            out = new BufferedOutputStream(socket.getOutputStream());
            socket.setSoTimeout(LIVENESS_TIMEOUT_MS); // 逾時內沒收到任何封包(含心跳)→ 視為斷線
        } catch (IOException e) {
            Log.e(TAG, "start failed", e);
            running = false;
            listener.onDisconnected();
            return;
        }
        readerThread    = new Thread(this::readLoop, "peer-reader");
        senderThread    = new Thread(this::sendLoop, "peer-sender");
        heartbeatThread = new Thread(this::heartbeatLoop, "peer-heartbeat");
        readerThread.start();
        senderThread.start();
        heartbeatThread.start();
        if (verbose) logLinkInfo();
        Log.i(TAG, "PeerConnection started");
    }

    /** 所有 socket 寫入的唯一出口:同步序列化,確保心跳不會插進資料封包中間造成錯位。 */
    private void writeFrame(byte[] header, @Nullable byte[] payload, int off, int len) throws IOException {
        synchronized (writeLock) {
            if (out == null) throw new IOException("stream closed");
            out.write(header);
            if (payload != null && len > 0) out.write(payload, off, len);
            out.flush();
        }
    }

    /** 心跳:每 {@value #HEARTBEAT_INTERVAL_MS}ms 送一個,讓對端的 SO_TIMEOUT 不會誤判斷線。 */
    private void heartbeatLoop() {
        byte[] hb = TransferProtocol.encodeHeader(TransferProtocol.makeHeartbeat());
        while (running) {
            try { Thread.sleep(HEARTBEAT_INTERVAL_MS); } catch (InterruptedException e) { break; }
            if (!running) break;
            try { writeFrame(hb, null, 0, 0); }
            catch (IOException e) { Log.w(TAG, "heartbeat failed: " + e.getMessage()); break; }
        }
    }

    /** 排入待送項目(任一時刻、可多次)；同一次呼叫的所有項目歸為同一批次。 */
    public void sendItems(@NonNull List<SendItem> items) {
        long batch = System.currentTimeMillis();
        int n = items.size();
        for (int i = 0; i < n; i++) outQueue.offer(new OutItem(items.get(i), batch, i, n));
    }

    public void close() {
        running = false;
        outQueue.offer(STOP);
        if (heartbeatThread != null) heartbeatThread.interrupt();
        try { socket.close(); } catch (IOException ignored) {}
        Log.d(TAG, "PeerConnection closed");
    }

    // ══════════════════════════════════════════════════════════
    //  傳送
    // ══════════════════════════════════════════════════════════

    private void sendLoop() {
        try {
            // HELLO 開場（帶本端名片：名稱 + 頭像縮圖）
            sendHello();

            while (running) {
                Object o = outQueue.take();
                if (o == STOP) break;
                if (!(o instanceof OutItem)) continue;
                OutItem oi = (OutItem) o;
                sendOne(oi.item, oi.batchId, oi.index, oi.count);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.w(TAG, "sendLoop ended: " + e.getMessage());
        }
    }

    /** 送出 HELLO：若有名片資料則帶 JSON payload {n:name, a:base64(jpeg縮圖)}。 */
    private void sendHello() throws IOException {
        boolean hasProfile = (selfName != null && !selfName.isEmpty()) || selfAvatarThumb != null;
        if (!hasProfile) {
            writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeHello()), null, 0, 0);
            return;
        }
        try {
            JSONObject p = new JSONObject();
            if (selfName != null) p.put("n", selfName);
            if (selfAvatarThumb != null)
                p.put("a", android.util.Base64.encodeToString(selfAvatarThumb, android.util.Base64.NO_WRAP));
            byte[] payload = p.toString().getBytes(StandardCharsets.UTF_8);
            writeFrame(TransferProtocol.encodeHeader(
                    TransferProtocol.makeHelloWithProfile(payload.length)), payload, 0, payload.length);
        } catch (Exception e) {
            writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeHello()), null, 0, 0);
        }
    }

    private void sendOne(SendItem item, long batchId, int index, int count) {
        long started = System.currentTimeMillis();
        boolean ok = false;
        try {
            // META
            JSONObject meta = new JSONObject();
            meta.put("n", item.name);
            meta.put("m", item.mime);
            meta.put("i", index);   // 批次序號（供接收端整包進度）
            meta.put("c", count);   // 批次總數
            byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);
            long size = item.size >= 0 ? item.size : 0;
            TransferProtocol.Header mh =
                    TransferProtocol.makeItemMeta(0, item.itemType, size, metaBytes.length);
            writeFrame(TransferProtocol.encodeHeader(mh), metaBytes, 0, metaBytes.length);

            // CHUNKS — pipelined：prefetch 執行緒先把磁碟讀進 bounded buffer pool，本執行緒
            // 同時做 CRC + socket 寫入,讓磁碟讀取與網路寫入重疊（wire 變快後可再榨 ~15-20%）。
            long sent = 0, offset = 0;
            final java.util.concurrent.ArrayBlockingQueue<Chunk> free  = new java.util.concurrent.ArrayBlockingQueue<>(CHUNK_POOL);
            final java.util.concurrent.ArrayBlockingQueue<Chunk> ready = new java.util.concurrent.ArrayBlockingQueue<>(CHUNK_POOL + 1);
            for (int i = 0; i < CHUNK_POOL; i++) free.add(new Chunk(TransferProtocol.CHUNK_SIZE));
            final java.util.concurrent.atomic.AtomicReference<IOException> readErr = new java.util.concurrent.atomic.AtomicReference<>();
            Thread prefetch = new Thread(() -> {
                try (InputStream in = openItemInput(item)) {
                    while (true) {
                        Chunk c = free.take();
                        int r = in.read(c.data);
                        if (r <= 0) { ready.put(CHUNK_EOF); return; }
                        c.len = r;
                        ready.put(c);
                    }
                } catch (IOException e) {
                    readErr.set(e);
                    try { ready.put(CHUNK_EOF); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "peer-send-read");
            prefetch.start();
            try {
                while (true) {
                    Chunk c = ready.take();
                    if (c == CHUNK_EOF) break;
                    int crc = TransferProtocol.crc32(c.data, 0, c.len);
                    TransferProtocol.Header ch = TransferProtocol.makeDataChunk(0, offset, c.len, crc);
                    writeFrame(TransferProtocol.encodeHeader(ch), c.data, 0, c.len);
                    offset += c.len; sent += c.len;
                    emitProgress(true, item.name, size, sent, started, item.itemType, batchId, index, count);
                    free.put(c); // 歸還緩衝供 prefetch 重用，避免每 chunk 配置
                }
            } finally {
                prefetch.interrupt();
            }
            IOException re = readErr.get();
            if (re != null) throw re;
            if (verbose) logThroughput("SEND", item.name, sent, started);
            // COMPLETE
            writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeComplete(0)), null, 0, 0);
            ok = true;
            // 批次最後一筆 → ALL_DONE（UI 顯示「傳輸完成」）；否則 FILE_DONE。
            if (index >= count - 1) emitAllDone(true, item.name, size, item.itemType, batchId, count);
            else emitDone(true, item.name, size, item.itemType, batchId, index, count);
        } catch (Exception e) {
            Log.e(TAG, "sendOne failed: " + item.name, e);
        } finally {
            long avg = avgSpeed(item.size, started);
            String uri = item.uri != null ? item.uri.toString() : null;
            listener.onItemCompleted(true, item.name, Math.max(item.size, 0), avg, ok,
                    item.itemType, uri, item.mime, batchId);
        }
    }

    private InputStream openItemInput(SendItem item) throws IOException {
        if (item.bytes != null) return new java.io.ByteArrayInputStream(item.bytes);
        if (item.uri != null) {
            InputStream in = context.getContentResolver().openInputStream(item.uri);
            if (in == null) throw new IOException("openInputStream null: " + item.uri);
            return in;
        }
        throw new IOException("SendItem has no source");
    }

    // ══════════════════════════════════════════════════════════
    //  接收
    // ══════════════════════════════════════════════════════════

    private void readLoop() {
        ReceivingItem cur = null;
        try {
            InputStream in = socket.getInputStream();
            byte[] header = new byte[TransferProtocol.HEADER_SIZE];

            while (running) {
                readFully(in, header, header.length);
                TransferProtocol.Header h = TransferProtocol.decodeHeader(header);

                switch (h.type) {
                    case TransferProtocol.TYPE_HELLO:
                        if (h.chunkLen > 0) {
                            byte[] pb = new byte[h.chunkLen];
                            readFully(in, pb, pb.length);
                            parsePeerProfile(pb);
                        }
                        break;

                    case TransferProtocol.TYPE_FILE_META: {
                        byte[] mb = new byte[h.metaLen];
                        readFully(in, mb, mb.length);
                        JSONObject meta = new JSONObject(new String(mb, StandardCharsets.UTF_8));
                        String name = meta.optString("n", "received_" + System.currentTimeMillis());
                        String mime = meta.optString("m", "application/octet-stream");
                        int fi = meta.optInt("i", 0);
                        int fc = meta.optInt("c", 0);
                        if (cur != null) cur.abort();
                        // 批次：距上次活動超過間隔 → 新批次
                        long now = System.currentTimeMillis();
                        if (now - lastRecvActivity > RECV_BATCH_GAP_MS) recvBatchId = now;
                        lastRecvActivity = now;
                        cur = new ReceivingItem(name, mime, h.totalSize, h.itemType, fi, fc);
                        emitProgress(false, name, h.totalSize, 0, cur.started, cur.itemType, recvBatchId, fi, fc);
                        break;
                    }

                    case TransferProtocol.TYPE_DATA_CHUNK: {
                        if (cur != null) {
                            // pipelined：讀進池中緩衝（take 提供背壓）→ CRC → 交給 writer 執行緒寫 MediaStore，
                            // 讓 socket 讀取與磁碟寫入重疊。
                            Chunk c = cur.wfree.take();
                            try {
                                readFully(in, c.data, h.chunkLen);
                            } catch (IOException e) {
                                cur.wfree.put(c);
                                throw e;
                            }
                            c.len = h.chunkLen;
                            int crc = TransferProtocol.crc32(c.data, 0, c.len);
                            if (crc != h.crc32) { Log.w(TAG, "CRC mismatch"); cur.corrupt = true; }
                            cur.received += c.len;
                            cur.wqueue.put(c);
                            emitProgress(false, cur.name, cur.size, cur.received, cur.started, cur.itemType,
                                    recvBatchId, cur.fileIndex, cur.fileCount);
                        } else {
                            // 無進行中項目：仍須把這塊 payload 從 socket 讀掉以維持框架對齊。
                            // 以小型暫存緩衝分段丟棄，避免在此異常路徑每次配置 512KB 造成 GC 壓力。
                            discard(in, h.chunkLen);
                        }
                        break;
                    }

                    case TransferProtocol.TYPE_COMPLETE: {
                        if (cur != null) {
                            boolean ok = cur.finish();
                            if (verbose) logThroughput("RECV", cur.name, cur.received, cur.started);
                            long avg = avgSpeed(cur.size, cur.started);
                            lastRecvActivity = System.currentTimeMillis();
                            String uri = cur.target != null ? cur.target.toString() : null;
                            listener.onItemCompleted(false, cur.name, cur.size, avg, ok, cur.itemType,
                                    uri, cur.mime, recvBatchId);
                            // 名片：把完整 vCard bytes 交給 UI 以名片介面開啟
                            if (cur.itemType == TransferProtocol.ITEM_VCARD && cur.cardBytes() != null) {
                                listener.onCardReceived(cur.cardBytes(), cur.name);
                            }
                            // 批次最後一筆 → ALL_DONE（UI 顯示「傳輸完成」）；否則 FILE_DONE。
                            if (cur.fileCount > 0 && cur.fileIndex >= cur.fileCount - 1)
                                emitAllDone(false, cur.name, cur.size, cur.itemType, recvBatchId, cur.fileCount);
                            else
                                emitDone(false, cur.name, cur.size, cur.itemType, recvBatchId, cur.fileIndex, cur.fileCount);
                            cur = null;
                        }
                        break;
                    }

                    case TransferProtocol.TYPE_CANCEL:
                        if (cur != null) { cur.abort(); cur = null; }
                        break;

                    default:
                        break;
                }
            }
        } catch (EOFException e) {
            Log.i(TAG, "Peer closed the connection");
        } catch (Exception e) {
            // 保留 stack：在 debuggable build 印出完整 throwable，正式版維持精簡訊息。
            // 否則 NPE/JSONException 等程式錯誤會被 getMessage() 吞成 "null"，難以定位。
            if (verbose) Log.w(TAG, "readLoop ended", e);
            else Log.w(TAG, "readLoop ended: " + e.getMessage());
        } finally {
            // 中途斷線：清掉進行中項目的 writer 執行緒與半成品檔，避免執行緒/串流洩漏。
            if (cur != null) cur.abort();
            running = false;
            listener.onDisconnected();
        }
    }

    /** 一個接收中的項目:邊收邊寫入 下載/KissLink(MediaStore,IS_PENDING)。 */
    private final class ReceivingItem {
        final String name, mime;
        final long size;
        final byte itemType;
        final int fileIndex, fileCount;
        final long started = System.currentTimeMillis();
        long received = 0;          // 已從 socket 讀入的位元組（進度用，reader 執行緒更新）
        boolean corrupt = false;
        @Nullable Uri target;
        @Nullable OutputStream out;
        @Nullable java.io.ByteArrayOutputStream cardBuf; // 名片：另存記憶體供 UI 開啟

        // ── Pipelined 磁碟寫入：reader 交付緩衝、writer 執行緒寫 MediaStore ──
        final java.util.concurrent.ArrayBlockingQueue<Chunk> wfree  = new java.util.concurrent.ArrayBlockingQueue<>(CHUNK_POOL);
        final java.util.concurrent.ArrayBlockingQueue<Chunk> wqueue = new java.util.concurrent.ArrayBlockingQueue<>(CHUNK_POOL + 1);
        @Nullable volatile IOException writeErr;
        final Thread writer;

        @Nullable byte[] cardBytes() { return cardBuf != null ? cardBuf.toByteArray() : null; }

        ReceivingItem(String name, String mime, long size, byte itemType, int fileIndex, int fileCount) {
            this.name = name; this.mime = mime; this.size = size; this.itemType = itemType;
            this.fileIndex = fileIndex; this.fileCount = fileCount;
            if (itemType == TransferProtocol.ITEM_VCARD) cardBuf = new java.io.ByteArrayOutputStream();
            try {
                ContentResolver cr = context.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.put(MediaStore.Downloads.RELATIVE_PATH, SAVE_DIR);
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                }
                target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (target != null) out = cr.openOutputStream(target);
            } catch (Exception e) {
                Log.e(TAG, "open receive output failed: " + name, e);
            }
            for (int i = 0; i < CHUNK_POOL; i++) wfree.add(new Chunk(TransferProtocol.CHUNK_SIZE));
            writer = new Thread(this::writeLoop, "peer-recv-write");
            writer.start();
        }

        /** writer 執行緒：把交付的緩衝寫入 MediaStore，寫完歸還池中（出錯仍歸還以免 reader 卡死）。 */
        private void writeLoop() {
            try {
                while (true) {
                    Chunk c = wqueue.take();
                    if (c == CHUNK_EOF) return;
                    if (writeErr == null && out != null) {
                        try {
                            out.write(c.data, 0, c.len);
                            if (cardBuf != null) cardBuf.write(c.data, 0, c.len);
                        } catch (IOException e) {
                            writeErr = e; corrupt = true;
                        }
                    }
                    wfree.put(c);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** 送 EOF 哨兵並等 writer 排空所有已交付的緩衝。 */
        private void drainWriter() {
            try { wqueue.put(CHUNK_EOF); writer.join(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        boolean finish() {
            drainWriter();
            try { if (out != null) { out.flush(); out.close(); } } catch (IOException ignored) {}
            out = null;
            try {
                if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(target, v, null, null);
                }
            } catch (Exception ignored) {}
            return !corrupt && writeErr == null && target != null;
        }

        void abort() {
            drainWriter();
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            out = null;
            try { if (target != null) context.getContentResolver().delete(target, null, null); }
            catch (Exception ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════

    private void emitProgress(boolean sending, String name, long total, long done, long started,
                              byte itemType, long batchId, int fileIndex, int fileCount) {
        long speed = 0;
        long elapsed = System.currentTimeMillis() - started;
        if (elapsed > 0) speed = done * 1000L / elapsed;
        progressLd.postValue(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .fileName(name).outgoing(sending).itemType(itemType).batchId(batchId)
                .totalBytes(total).doneBytes(done).speedBps(speed)
                .fileIndex(fileIndex).fileCount(fileCount)
                .build());
    }

    private void emitDone(boolean sending, String name, long size, byte itemType, long batchId,
                          int fileIndex, int fileCount) {
        progressLd.postValue(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.FILE_DONE)
                .fileName(name).outgoing(sending).itemType(itemType).batchId(batchId)
                .totalBytes(size).doneBytes(size)
                .fileIndex(fileIndex).fileCount(fileCount)
                .build());
    }

    /** 整批最後一筆完成 → ALL_DONE，UI 據此顯示「傳輸完成」。 */
    private void emitAllDone(boolean sending, String name, long size, byte itemType, long batchId, int count) {
        progressLd.postValue(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.ALL_DONE)
                .fileName(name).outgoing(sending).itemType(itemType).batchId(batchId)
                .totalBytes(size).doneBytes(size)
                .fileIndex(Math.max(0, count - 1)).fileCount(count)
                .build());
    }

    /** 解析對方 HELLO 帶來的名片 JSON，回呼 {@link Listener#onPeerProfile}。 */
    private void parsePeerProfile(byte[] payload) {
        try {
            JSONObject p = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            String name = p.has("n") ? p.optString("n", null) : null;
            byte[] avatar = null;
            if (p.has("a")) {
                try { avatar = android.util.Base64.decode(p.optString("a", ""), android.util.Base64.NO_WRAP); }
                catch (Exception ignored) {}
            }
            listener.onPeerProfile(name, avatar);
        } catch (Exception e) {
            Log.w(TAG, "parsePeerProfile failed: " + e.getMessage());
        }
    }

    /** 單一項目的牆鐘吞吐量（pipelined 後磁碟與網路重疊，per-leg 拆解已無意義，只記總吞吐）。 */
    private static void logThroughput(String dir, String name, long bytes, long startedMs) {
        long wallMs = System.currentTimeMillis() - startedMs;
        if (bytes <= 0 || wallMs <= 0) return;
        double mb = bytes / 1048576.0;
        double mbps = (bytes * 8.0 / 1e6) / (wallMs / 1000.0);
        Log.i(TAG, String.format(java.util.Locale.US,
                "PERF %s %.1fMB wall=%.2fs (%.0f Mbps / %.1f MB/s) | %s",
                dir, mb, wallMs / 1000.0, mbps, mb / (wallMs / 1000.0),
                name.length() > 24 ? name.substring(0, 24) : name));
    }

    /** 連線建立時記錄 Wi-Fi 連結速率/訊號（best-effort；部分裝置回報的是 STA 而非 P2P 介面）。 */
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

    private static long avgSpeed(long size, long startedMs) {
        long elapsed = System.currentTimeMillis() - startedMs;
        if (size <= 0 || elapsed <= 0) return 0;
        return size * 1000L / elapsed;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    /** 從 socket 讀掉並丟棄 {@code len} 個位元組，以維持框架對齊。用小型暫存緩衝分段讀，
     *  避免為一塊待丟棄資料配置最大可達 512KB 的緩衝。 */
    private static void discard(InputStream in, int len) throws IOException {
        byte[] tmp = new byte[Math.min(len, 8192)];
        int remaining = len;
        while (remaining > 0) {
            int r = in.read(tmp, 0, Math.min(remaining, tmp.length));
            if (r < 0) throw new EOFException();
            remaining -= r;
        }
    }
}
