package com.kisslink.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 用 {@link LoopbackPeers} 框架把真實 {@link TransferProtocol} 幀序列推過本機 TCP，
 * 驗證 framing 在真實 stream 上的端到端行為——這是純編解碼的 {@link TransferProtocolTest}
 * 涵蓋不到的：連續多幀排序、TCP 分段下的重組、緩衝/flush 邊界、CRC 在傳輸中的偵錯。
 *
 * <p>傳/收輔助方法（{@link #sendItem}/{@link #receiveItem}）刻意鏡像生產端
 * {@code PeerSender} 的分塊 framing 與 {@code PeerReceiver} 的逐幀重組+CRC 契約，
 * 因此這裡跑過的線格式即真實 Wi-Fi Direct 通道上的線格式。
 */
public class TransferProtocolLoopbackTest {

    // ── HELLO over a real socket ──────────────────────────────

    @Test
    public void helloWithProfile_roundTripsOverSocket() throws Exception {
        byte[] profile = "{\"n\":\"Kevin\"}".getBytes(StandardCharsets.UTF_8);
        try (LoopbackPeers link = LoopbackPeers.open()) {
            link.a().sendFrame(TransferProtocol.makeHelloWithProfile(profile.length), profile);
            LoopbackPeers.Frame f = link.b().receiveFrame();
            assertEquals(TransferProtocol.TYPE_HELLO, f.header.type);
            assertArrayEquals(profile, f.payload);
        }
    }

    // ── Single file, chunked across many frames ───────────────

    @Test
    public void singleFile_chunkedAcrossFrames_reassemblesExactly() throws Exception {
        byte[] data = randomBytes(200 * 1024, 1); // 200 KB
        try (LoopbackPeers link = LoopbackPeers.open()) {
            // sender runs on its own thread; receiver pulls on the test thread (avoids socket-buffer deadlock).
            Async sendErr = runAsync(() ->
                    sendItem(link.a(), 0, 1, "photo.jpg", data, 64 * 1024));
            ReceivedItem got = receiveItem(link.b());
            rethrow(sendErr);

            assertEquals("photo.jpg", got.name);
            assertArrayEquals(data, got.bytes);
            assertFalse("no CRC mismatch expected", got.corrupt);
        }
    }

    @Test
    public void largeFile_nearChunkLimit_reassembles() throws Exception {
        // payload spanning multiple max-size chunks; stresses TCP segmentation + readFully.
        byte[] data = randomBytes(TransferProtocol.CHUNK_SIZE + 123_456, 7);
        try (LoopbackPeers link = LoopbackPeers.open()) {
            Async sendErr = runAsync(() ->
                    sendItem(link.a(), 0, 1, "blob.bin", data, TransferProtocol.CHUNK_SIZE));
            ReceivedItem got = receiveItem(link.b());
            rethrow(sendErr);
            assertArrayEquals(data, got.bytes);
        }
    }

    // ── Multi-file batch ──────────────────────────────────────

    @Test
    public void multiFileBatch_eachFileReconstructedInOrder() throws Exception {
        byte[][] files = {
                randomBytes(10_000, 11),
                randomBytes(70_000, 22),
                randomBytes(1, 33),
        };
        String[] names = {"a.txt", "b.dat", "c.bin"};
        try (LoopbackPeers link = LoopbackPeers.open()) {
            Async sendErr = runAsync(() -> {
                for (int i = 0; i < files.length; i++) {
                    sendItem(link.a(), i, files.length, names[i], files[i], 32 * 1024);
                }
            });
            for (int i = 0; i < files.length; i++) {
                ReceivedItem got = receiveItem(link.b());
                assertEquals("file " + i + " name", names[i], got.name);
                assertArrayEquals("file " + i + " bytes", files[i], got.bytes);
            }
            rethrow(sendErr);
        }
    }

    // ── Back-to-back small frames must not be mis-framed ──────

    @Test
    public void manyBackToBackFrames_keepBoundaries() throws Exception {
        int n = 500;
        try (LoopbackPeers link = LoopbackPeers.open()) {
            Async sendErr = runAsync(() -> {
                for (int i = 0; i < n; i++) {
                    byte[] p = new byte[]{(byte) i, (byte) (i >> 8)};
                    int crc = TransferProtocol.crc32(p, 0, p.length);
                    link.a().sendFrame(TransferProtocol.makeDataChunk(0, i, p.length, crc), p);
                }
            });
            for (int i = 0; i < n; i++) {
                LoopbackPeers.Frame f = link.b().receiveFrame();
                assertEquals(TransferProtocol.TYPE_DATA_CHUNK, f.header.type);
                assertEquals("offset carries frame index", i, f.header.offset);
                assertEquals((byte) i, f.payload[0]);
                assertEquals((byte) (i >> 8), f.payload[1]);
            }
            rethrow(sendErr);
        }
    }

    // ── CRC catches in-flight corruption ──────────────────────

    @Test
    public void crc32_detectsCorruptedChunkOnWire() throws Exception {
        byte[] original = randomBytes(4096, 99);
        int goodCrc = TransferProtocol.crc32(original, 0, original.length);
        byte[] tampered = original.clone();
        tampered[1000] ^= 0xFF; // bit flip during "transmission"

        try (LoopbackPeers link = LoopbackPeers.open()) {
            // header advertises the CRC of the ORIGINAL bytes, but tampered bytes go on the wire.
            link.a().sendFrame(
                    TransferProtocol.makeDataChunk(0, 0, tampered.length, goodCrc), tampered);
            LoopbackPeers.Frame f = link.b().receiveFrame();
            int recomputed = TransferProtocol.crc32(f.payload, 0, f.payload.length);
            assertFalse("CRC over received bytes must differ from advertised",
                    recomputed == f.header.crc32);
        }
    }

    // ── Resume offset contract (skip + partial-overlap trim) ──

    @Test
    public void resume_skipsFullyReceivedAndTrimsOverlap() throws Exception {
        // Mirrors PeerReceiver's resume math: receiver already has `resumeFrom` bytes on disk,
        // sender restarts the file from offset 0. Chunks entirely below resumeFrom are dropped;
        // the chunk straddling resumeFrom is trimmed by (resumeFrom - offset).
        byte[] full = randomBytes(10_000, 5);
        long resumeFrom = 4096;
        int chunk = 4096;

        try (LoopbackPeers link = LoopbackPeers.open()) {
            Async sendErr = runAsync(() -> {
                // sender naively re-sends the whole file in 4096-byte chunks from offset 0.
                for (long off = 0; off < full.length; off += chunk) {
                    int len = (int) Math.min(chunk, full.length - off);
                    byte[] part = slice(full, (int) off, len);
                    int crc = TransferProtocol.crc32(part, 0, len);
                    link.a().sendFrame(
                            TransferProtocol.makeDataChunk(0, off, len, crc), part);
                }
                link.a().sendFrame(TransferProtocol.makeComplete(0), null);
            });

            // receiver reconstructs ONLY the bytes from resumeFrom onward, applying skip+trim.
            ByteArrayOutputStream tail = new ByteArrayOutputStream();
            while (true) {
                LoopbackPeers.Frame f = link.b().receiveFrame();
                if (f.header.type == TransferProtocol.TYPE_COMPLETE) break;
                long off = f.header.offset;
                int chunkLen = f.header.chunkLen;
                long chunkEnd = off + chunkLen;
                if (chunkEnd <= resumeFrom) continue;            // fully already-received → skip
                int writeFrom = 0;
                if (off < resumeFrom) writeFrom = (int) (resumeFrom - off); // straddling → trim head
                tail.write(f.payload, writeFrom, chunkLen - writeFrom);
            }
            rethrow(sendErr);

            byte[] expectedTail = slice(full, (int) resumeFrom, full.length - (int) resumeFrom);
            assertArrayEquals(expectedTail, tail.toByteArray());
        }
    }

    // ── Defensive: malformed headers rejected ─────────────────

    @Test
    public void decodeRejectsOversizeChunkLen() throws Exception {
        TransferProtocol.Header h = TransferProtocol.makeDataChunk(0, 0, 0, 0);
        h.chunkLen = TransferProtocol.MAX_PAYLOAD + 1;
        byte[] raw = TransferProtocol.encodeHeader(h);
        try {
            TransferProtocol.decodeHeader(raw);
            org.junit.Assert.fail("expected InvalidPacketException for oversize chunkLen");
        } catch (TransferProtocol.InvalidPacketException expected) {
            assertTrue(expected.getMessage().contains("chunkLen"));
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Sender / receiver helpers — mirror PeerSender/PeerReceiver framing
    // ══════════════════════════════════════════════════════════

    /** Frame an item as PeerSender does: FILE_META(name) → DATA_CHUNK×N → COMPLETE. */
    private static void sendItem(LoopbackPeers.Peer peer, int id, int count,
                                 String name, byte[] data, int chunkSize) throws Exception {
        byte[] meta = name.getBytes(StandardCharsets.UTF_8);
        peer.sendFrame(
                TransferProtocol.makeItemMeta(id, TransferProtocol.ITEM_FILE, data.length, meta.length),
                meta);
        for (long off = 0; off < data.length; off += chunkSize) {
            int len = (int) Math.min(chunkSize, data.length - off);
            byte[] part = slice(data, (int) off, len);
            int crc = TransferProtocol.crc32(part, 0, len);
            peer.sendFrame(TransferProtocol.makeDataChunk(id, off, len, crc), part);
        }
        peer.sendFrame(TransferProtocol.makeComplete(id), null);
    }

    private static final class ReceivedItem {
        String name;
        byte[] bytes;
        boolean corrupt;
    }

    /** Reassemble one item: read FILE_META, then DATA_CHUNKs validating CRC, until COMPLETE. */
    private static ReceivedItem receiveItem(LoopbackPeers.Peer peer) throws Exception {
        ReceivedItem item = new ReceivedItem();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        while (true) {
            LoopbackPeers.Frame f = peer.receiveFrame();
            switch (f.header.type) {
                case TransferProtocol.TYPE_FILE_META:
                    item.name = new String(f.payload, StandardCharsets.UTF_8);
                    break;
                case TransferProtocol.TYPE_DATA_CHUNK:
                    int crc = TransferProtocol.crc32(f.payload, 0, f.payload.length);
                    if (crc != f.header.crc32) item.corrupt = true;
                    sink.write(f.payload, 0, f.payload.length);
                    break;
                case TransferProtocol.TYPE_COMPLETE:
                    item.bytes = sink.toByteArray();
                    return item;
                default:
                    break;
            }
        }
    }

    // ── small utilities ───────────────────────────────────────

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    /** Run a throwing task on a background thread; returns a handle to join + surface its error. */
    private interface ThrowingRunnable { void run() throws Exception; }

    private static final class Async {
        Thread thread;
        volatile Throwable error;
    }

    /**
     * The sender must run off the test thread: a full transfer can exceed the socket send buffer,
     * so writes and reads have to interleave or the writer blocks forever (deadlock).
     */
    private static Async runAsync(ThrowingRunnable task) {
        final Async async = new Async();
        async.thread = new Thread(() -> {
            try { task.run(); } catch (Throwable e) { async.error = e; }
        }, "loopback-sender");
        async.thread.setDaemon(true);
        async.thread.start();
        return async;
    }

    /** Join the async sender and rethrow anything it threw, so failures surface in the test. */
    private static void rethrow(Async async) throws Exception {
        if (async.thread != null) async.thread.join(5000);
        if (async.error instanceof Exception) throw (Exception) async.error;
        if (async.error != null) throw new RuntimeException(async.error);
    }
}
