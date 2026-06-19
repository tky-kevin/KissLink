package com.kisslink.transfer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Receiver thread: reads frames from the socket, writes received files to MediaStore
 * via a pipelined writer thread per {@link ReceivingItem}.
 *
 * <p>Created by {@link PeerConnection#start()} and runs on its own thread.
 * Exits on EOF (peer closed) or socket timeout (liveness failure).
 */
final class PeerReceiver implements Runnable {

    private static final String TAG = "PeerReceiver";
    private static final String SAVE_DIR = Environment.DIRECTORY_DOWNLOADS + "/KissLink";
    private static final long RECV_BATCH_GAP_MS = 4000;

    private final Context context;
    private final Socket socket;
    private final MutableLiveData<TransferProgress> progressLd;
    private final PeerConnection.Listener listener;
    private final boolean verbose;
    private volatile boolean running;

    private long recvBatchId = 0;
    private long lastRecvActivity = 0;

    PeerReceiver(@NonNull Context context,
                 @NonNull Socket socket,
                 @NonNull MutableLiveData<TransferProgress> progressLd,
                 @NonNull PeerConnection.Listener listener,
                 boolean verbose) {
        this.context = context;
        this.socket = socket;
        this.progressLd = progressLd;
        this.listener = listener;
        this.verbose = verbose;
    }

    void setRunning(boolean running) { this.running = running; }

    @Override
    public void run() {
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
                        long now = System.currentTimeMillis();
                        if (now - lastRecvActivity > RECV_BATCH_GAP_MS) recvBatchId = now;
                        lastRecvActivity = now;
                        cur = new ReceivingItem(name, mime, h.totalSize, h.itemType, fi, fc);
                        emitProgress(false, name, h.totalSize, 0, cur.started, cur.itemType, recvBatchId, fi, fc);
                        break;
                    }

                    case TransferProtocol.TYPE_DATA_CHUNK: {
                        if (cur != null) {
                            PeerConnection.Chunk c = cur.wfree.take();
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
                            discard(in, h.chunkLen);
                        }
                        break;
                    }

                    case TransferProtocol.TYPE_COMPLETE: {
                        if (cur != null) {
                            boolean ok = cur.finish();
                            if (verbose) PeerSender.logThroughput("RECV", cur.name, cur.received, cur.started);
                            long avg = PeerSender.avgSpeed(cur.size, cur.started);
                            lastRecvActivity = System.currentTimeMillis();
                            String uri = cur.target != null ? cur.target.toString() : null;
                            listener.onItemCompleted(false, cur.name, cur.size, avg, ok, cur.itemType,
                                    uri, cur.mime, recvBatchId);
                            if (cur.itemType == TransferProtocol.ITEM_VCARD && cur.cardBytes() != null) {
                                listener.onCardReceived(cur.cardBytes(), cur.name);
                            }
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
            if (verbose) Log.w(TAG, "readLoop ended", e);
            else Log.w(TAG, "readLoop ended: " + e.getMessage());
        } finally {
            if (cur != null) cur.abort();
            running = false;
            listener.onDisconnected();
        }
    }

    // ── ReceivingItem ──

    private final class ReceivingItem {
        final String name, mime;
        final long size;
        final byte itemType;
        final int fileIndex, fileCount;
        final long started = System.currentTimeMillis();
        long received = 0;
        boolean corrupt = false;
        @Nullable Uri target;
        @Nullable OutputStream out;
        @Nullable java.io.ByteArrayOutputStream cardBuf;

        final ArrayBlockingQueue<PeerConnection.Chunk> wfree = new ArrayBlockingQueue<>(PeerConnection.CHUNK_POOL);
        final ArrayBlockingQueue<PeerConnection.Chunk> wqueue = new ArrayBlockingQueue<>(PeerConnection.CHUNK_POOL + 1);
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
            for (int i = 0; i < PeerConnection.CHUNK_POOL; i++) wfree.add(new PeerConnection.Chunk(TransferProtocol.CHUNK_SIZE));
            writer = new Thread(this::writeLoop, "peer-recv-write");
            writer.start();
        }

        private void writeLoop() {
            try {
                while (true) {
                    PeerConnection.Chunk c = wqueue.take();
                    if (c == PeerConnection.CHUNK_EOF) return;
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

        private void drainWriter() {
            try { wqueue.put(PeerConnection.CHUNK_EOF); writer.join(); }
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

    // ── Profile parsing ──

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

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

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
