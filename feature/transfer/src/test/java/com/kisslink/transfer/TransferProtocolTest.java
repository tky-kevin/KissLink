package com.kisslink.transfer;

import static org.junit.Assert.*;

import org.junit.Test;

public class TransferProtocolTest {

    // ── encode / decode round-trip ────────────────────────────

    @Test
    public void encodeDecodeHandshake_roundTrip() throws Exception {
        TransferProtocol.Header out = TransferProtocol.makeHandshake();
        byte[] raw = TransferProtocol.encodeHeader(out);
        assertEquals(TransferProtocol.HEADER_SIZE, raw.length);

        TransferProtocol.Header in = TransferProtocol.decodeHeader(raw);
        assertEquals(TransferProtocol.MAGIC, in.magic);
        assertEquals(TransferProtocol.VERSION, in.version);
        assertEquals(TransferProtocol.TYPE_HANDSHAKE, in.type);
    }

    @Test
    public void encodeDecodeFileMeta_preservesFields() throws Exception {
        TransferProtocol.Header out = TransferProtocol.makeFileMeta(3, 10, 1_048_576L, 512);
        byte[] raw = TransferProtocol.encodeHeader(out);
        TransferProtocol.Header in = TransferProtocol.decodeHeader(raw);

        assertEquals(TransferProtocol.TYPE_FILE_META, in.type);
        assertEquals(3, in.fileId);
        assertEquals(10, in.fileCount);
        assertEquals(1_048_576L, in.totalSize);
        assertEquals(512, in.metaLen);
    }

    @Test
    public void encodeDecodeDataChunk_preservesOffsetAndCrc() throws Exception {
        byte[] payload = "hello world".getBytes();
        int crc = TransferProtocol.crc32(payload, 0, payload.length);
        TransferProtocol.Header out =
                TransferProtocol.makeDataChunk(1, 65536L, payload.length, crc);
        byte[] raw = TransferProtocol.encodeHeader(out);
        TransferProtocol.Header in = TransferProtocol.decodeHeader(raw);

        assertEquals(TransferProtocol.TYPE_DATA_CHUNK, in.type);
        assertEquals(1, in.fileId);
        assertEquals(65536L, in.offset);
        assertEquals(payload.length, in.chunkLen);
        assertEquals(crc, in.crc32);
    }

    @Test
    public void encodeDecodeHelloWithProfile_preservesChunkLen() throws Exception {
        TransferProtocol.Header out = TransferProtocol.makeHelloWithProfile(4096);
        byte[] raw = TransferProtocol.encodeHeader(out);
        TransferProtocol.Header in = TransferProtocol.decodeHeader(raw);

        assertEquals(TransferProtocol.TYPE_HELLO, in.type);
        assertEquals(4096, in.chunkLen);
    }

    @Test
    public void encodeDecodeItemMeta_preservesItemType() throws Exception {
        TransferProtocol.Header out =
                TransferProtocol.makeItemMeta(0, TransferProtocol.ITEM_PHOTO, 2_000_000L, 128);
        byte[] raw = TransferProtocol.encodeHeader(out);
        TransferProtocol.Header in = TransferProtocol.decodeHeader(raw);

        assertEquals(TransferProtocol.ITEM_PHOTO, in.itemType);
        assertEquals(2_000_000L, in.totalSize);
    }

    // ── validation guards ─────────────────────────────────────

    @Test(expected = TransferProtocol.InvalidPacketException.class)
    public void decodeHeader_nullThrows() throws Exception {
        TransferProtocol.decodeHeader(null);
    }

    @Test(expected = TransferProtocol.InvalidPacketException.class)
    public void decodeHeader_tooShortThrows() throws Exception {
        TransferProtocol.decodeHeader(new byte[16]);
    }

    @Test(expected = TransferProtocol.InvalidPacketException.class)
    public void decodeHeader_badMagicThrows() throws Exception {
        byte[] raw = TransferProtocol.encodeHeader(TransferProtocol.makeHandshake());
        raw[0] = 0x00;
        TransferProtocol.decodeHeader(raw);
    }

    @Test(expected = TransferProtocol.InvalidPacketException.class)
    public void decodeHeader_chunkLenOverMaxPayloadThrows() throws Exception {
        TransferProtocol.Header h = TransferProtocol.makeHandshake();
        h.chunkLen = TransferProtocol.MAX_PAYLOAD + 1;
        TransferProtocol.decodeHeader(TransferProtocol.encodeHeader(h));
    }

    @Test(expected = TransferProtocol.InvalidPacketException.class)
    public void decodeHeader_metaLenOverLimitThrows() throws Exception {
        TransferProtocol.Header h = TransferProtocol.makeHandshake();
        h.metaLen = (short) (TransferProtocol.MAX_META_LEN + 1);
        TransferProtocol.decodeHeader(TransferProtocol.encodeHeader(h));
    }

    // ── crc32 helper ──────────────────────────────────────────

    @Test
    public void crc32_sameInputSameOutput() {
        byte[] data = "KissLink protocol test".getBytes();
        int c1 = TransferProtocol.crc32(data, 0, data.length);
        int c2 = TransferProtocol.crc32(data, 0, data.length);
        assertEquals(c1, c2);
    }

    @Test
    public void crc32_differentInputDifferentOutput() {
        byte[] a = "aaa".getBytes();
        byte[] b = "bbb".getBytes();
        assertNotEquals(
                TransferProtocol.crc32(a, 0, a.length), TransferProtocol.crc32(b, 0, b.length));
    }

    // ── itemTypeForMime ───────────────────────────────────────

    @Test
    public void itemTypeForMime_imageIsPhoto() {
        assertEquals(TransferProtocol.ITEM_PHOTO, TransferProtocol.itemTypeForMime("image/jpeg"));
    }

    @Test
    public void itemTypeForMime_videoIsPhoto() {
        assertEquals(TransferProtocol.ITEM_PHOTO, TransferProtocol.itemTypeForMime("video/mp4"));
    }

    @Test
    public void itemTypeForMime_pdfIsFile() {
        assertEquals(
                TransferProtocol.ITEM_FILE, TransferProtocol.itemTypeForMime("application/pdf"));
    }

    @Test
    public void itemTypeForMime_nullIsFile() {
        assertEquals(TransferProtocol.ITEM_FILE, TransferProtocol.itemTypeForMime(null));
    }
}
