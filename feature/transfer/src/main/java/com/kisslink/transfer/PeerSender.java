package com.kisslink.transfer;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sender thread: takes {@link SendItem}s from a queue, writes HELLO + META + CHUNK×N + COMPLETE
 * to the socket via pipelined prefetch I/O.
 *
 * <p>Created by {@link PeerConnection#start()} and runs on its own thread.
 * Exits when it encounters {@link PeerConnection#STOP} in the queue.
 */
final class PeerSender implements Runnable {

    private static final String TAG = "PeerSender";

    interface FrameWriter {
        void writeFrame(byte[] header, @Nullable byte[] payload, int off, int len) throws IOException;
    }

    private final Context context;
    private final BlockingQueue<Object> outQueue;
    private final FrameWriter frameWriter;
    private final MutableLiveData<TransferProgress> progressLd;
    private final PeerConnection.Listener listener;
    private final String selfName;
    @Nullable private final byte[] selfAvatarThumb;
    private final boolean verbose;

    PeerSender(@NonNull Context context,
               @NonNull BlockingQueue<Object> outQueue,
               @NonNull FrameWriter frameWriter,
               @NonNull MutableLiveData<TransferProgress> progressLd,
               @NonNull PeerConnection.Listener listener,
               @Nullable String selfName,
               @Nullable byte[] selfAvatarThumb,
               boolean verbose) {
        this.context = context;
        this.outQueue = outQueue;
        this.frameWriter = frameWriter;
        this.progressLd = progressLd;
        this.listener = listener;
        this.selfName = selfName;
        this.selfAvatarThumb = selfAvatarThumb;
        this.verbose = verbose;
    }

    @Override
    public void run() {
        try {
            sendHello();
            while (true) {
                Object o = outQueue.take();
                if (o == PeerConnection.STOP) break;
                if (!(o instanceof PeerConnection.OutItem)) continue;
                PeerConnection.OutItem oi = (PeerConnection.OutItem) o;
                sendOne(oi.item, oi.batchId, oi.index, oi.count);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.w(TAG, "sendLoop ended: " + e.getMessage());
        }
    }

    private void sendHello() throws IOException {
        boolean hasProfile = (selfName != null && !selfName.isEmpty()) || selfAvatarThumb != null;
        if (!hasProfile) {
            frameWriter.writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeHello()), null, 0, 0);
            return;
        }
        try {
            JSONObject p = new JSONObject();
            if (selfName != null) p.put("n", selfName);
            if (selfAvatarThumb != null)
                p.put("a", android.util.Base64.encodeToString(selfAvatarThumb, android.util.Base64.NO_WRAP));
            byte[] payload = p.toString().getBytes(StandardCharsets.UTF_8);
            frameWriter.writeFrame(TransferProtocol.encodeHeader(
                    TransferProtocol.makeHelloWithProfile(payload.length)), payload, 0, payload.length);
        } catch (Exception e) {
            frameWriter.writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeHello()), null, 0, 0);
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
            meta.put("i", index);
            meta.put("c", count);
            byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);
            long size = item.size >= 0 ? item.size : 0;
            TransferProtocol.Header mh =
                    TransferProtocol.makeItemMeta(0, item.itemType, size, metaBytes.length);
            frameWriter.writeFrame(TransferProtocol.encodeHeader(mh), metaBytes, 0, metaBytes.length);

            // CHUNKS — pipelined prefetch
            long sent = 0, offset = 0;
            final ArrayBlockingQueue<PeerConnection.Chunk> free = new ArrayBlockingQueue<>(PeerConnection.CHUNK_POOL);
            final ArrayBlockingQueue<PeerConnection.Chunk> ready = new ArrayBlockingQueue<>(PeerConnection.CHUNK_POOL + 1);
            for (int i = 0; i < PeerConnection.CHUNK_POOL; i++) free.add(new PeerConnection.Chunk(TransferProtocol.CHUNK_SIZE));
            final AtomicReference<IOException> readErr = new AtomicReference<>();
            Thread prefetch = new Thread(() -> {
                try (InputStream in = openItemInput(item)) {
                    while (true) {
                        PeerConnection.Chunk c = free.take();
                        int r = in.read(c.data);
                        if (r <= 0) { ready.put(PeerConnection.CHUNK_EOF); return; }
                        c.len = r;
                        ready.put(c);
                    }
                } catch (IOException e) {
                    readErr.set(e);
                    try { ready.put(PeerConnection.CHUNK_EOF); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "peer-send-read");
            prefetch.start();
            try {
                while (true) {
                    PeerConnection.Chunk c = ready.take();
                    if (c == PeerConnection.CHUNK_EOF) break;
                    int crc = TransferProtocol.crc32(c.data, 0, c.len);
                    TransferProtocol.Header ch = TransferProtocol.makeDataChunk(0, offset, c.len, crc);
                    frameWriter.writeFrame(TransferProtocol.encodeHeader(ch), c.data, 0, c.len);
                    offset += c.len; sent += c.len;
                    emitProgress(true, item.name, size, sent, started, item.itemType, batchId, index, count);
                    free.put(c);
                }
            } finally {
                prefetch.interrupt();
            }
            IOException re = readErr.get();
            if (re != null) throw re;
            if (verbose) PeerSender.logThroughput("SEND", item.name, sent, started);
            // COMPLETE
            frameWriter.writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeComplete(0)), null, 0, 0);
            ok = true;
            if (index >= count - 1) emitAllDone(true, item.name, size, item.itemType, batchId, count);
            else emitDone(true, item.name, size, item.itemType, batchId, index, count);
        } catch (Exception e) {
            Log.e(TAG, "sendOne failed: " + item.name, e);
        } finally {
            long avg = PeerSender.avgSpeed(item.size, started);
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

    // ── Progress emission ──

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

    private void emitAllDone(boolean sending, String name, long size, byte itemType, long batchId, int count) {
        progressLd.postValue(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.ALL_DONE)
                .fileName(name).outgoing(sending).itemType(itemType).batchId(batchId)
                .totalBytes(size).doneBytes(size)
                .fileIndex(Math.max(0, count - 1)).fileCount(count)
                .build());
    }

    // ── Utilities ──

    static void logThroughput(String dir, String name, long bytes, long startedMs) {
        long wallMs = System.currentTimeMillis() - startedMs;
        if (bytes <= 0 || wallMs <= 0) return;
        double mb = bytes / 1048576.0;
        double mbps = (bytes * 8.0 / 1e6) / (wallMs / 1000.0);
        Log.i("PeerConnection", String.format(java.util.Locale.US,
                "PERF %s %.1fMB wall=%.2fs (%.0f Mbps / %.1f MB/s) | %s",
                dir, mb, wallMs / 1000.0, mbps, mb / (wallMs / 1000.0),
                name.length() > 24 ? name.substring(0, 24) : name));
    }

    static long avgSpeed(long size, long startedMs) {
        long elapsed = System.currentTimeMillis() - startedMs;
        if (size <= 0 || elapsed <= 0) return 0;
        return size * 1000L / elapsed;
    }
}
