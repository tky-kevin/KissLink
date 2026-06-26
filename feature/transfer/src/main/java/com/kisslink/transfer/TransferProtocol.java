package com.kisslink.transfer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * KissLink 二進位傳輸協定（版本 1）。
 *
 * <h3>封包結構（Header 固定 64 bytes，Big-Endian）</h3>
 * <pre>
 *  Offset │ Size │ Field
 *  ───────┼──────┼─────────────────────────────────
 *    0    │  4   │ MAGIC        0x4B4C494E ("KLIN")
 *    4    │  1   │ VERSION      0x01
 *    5    │  1   │ TYPE         見 TYPE_* 常數
 *    6    │  2   │ FILE_COUNT   此 session 總檔案數
 *    8    │  4   │ FILE_ID      當前檔案序號（0-based）
 *   12    │  8   │ TOTAL_SIZE   檔案總位元組數
 *   20    │  8   │ OFFSET       本 chunk 在檔案中的起始位置
 *   28    │  4   │ CHUNK_LEN    本封包 payload 長度
 *   32    │  2   │ META_LEN     FILE_META 封包的 JSON 長度
 *   34    │  4   │ CRC32        payload 的 CRC32
 *   38    │ 26   │ RESERVED     保留
 *  ───────┴──────┴─────────────────────────────────
 *  Total  64 bytes
 * </pre>
 *
 * <h3>交握流程</h3>
 * <pre>
 *  CLIENT (接收方 / 非 GO)         SERVER (傳送方 / GO)
 *  ─────────────────────           ────────────────────────
 *  → connect TCP                   accept() →
 *
 *  雙方各自送出 HELLO（帶名片 profile）：
 *  HELLO (+profile)  ──────────►
 *                    ◄──────────   HELLO (+profile)
 *
 *  （對每個檔案重複）
 *                    ◄──────────   FILE_META + JSON
 *                    ◄──────────   DATA_CHUNK × N
 *                    ◄──────────   COMPLETE
 * </pre>
 */
public final class TransferProtocol {

    private TransferProtocol() {}

    // ── Magic & Version ────────────────────────────────────────
    public static final int  MAGIC   = 0x4B4C494E;
    public static final byte VERSION = 0x01;

    // ── Header Size ────────────────────────────────────────────
    public static final int HEADER_SIZE = 64;

    // ── Chunk Size ─────────────────────────────────────────────
    /** 每次讀寫的資料塊大小（512 KB），在 Wi-Fi Direct 上平衡記憶體與吞吐量。 */
    public static final int CHUNK_SIZE = 512 * 1024;

    /**
     * 任一封包 payload（CHUNK / HELLO profile）的上限——等於 {@link #CHUNK_SIZE}。
     * 對端宣告的 chunkLen 必須落在 [0, MAX_PAYLOAD]，否則視為惡意/損毀封包並斷線；
     * 這同時保護接收端 pooled buffer（容量 = CHUNK_SIZE）不被超長宣告溢位。
     */
    public static final int MAX_PAYLOAD = CHUNK_SIZE;
    /** FILE_META 的 JSON 長度上限（檔名/MIME 等小欄位，遠小於此）。 */
    public static final int MAX_META_LEN = 32 * 1024;

    // ── Packet Types ───────────────────────────────────────────
    public static final byte TYPE_HANDSHAKE     = 0x01;
    public static final byte TYPE_HANDSHAKE_ACK = 0x02;
    public static final byte TYPE_FILE_META     = 0x03;
    public static final byte TYPE_READY_ACK     = 0x04;
    public static final byte TYPE_DATA_CHUNK    = 0x05;
    public static final byte TYPE_PROGRESS_ACK  = 0x06;
    public static final byte TYPE_COMPLETE      = 0x07;
    public static final byte TYPE_COMPLETE_ACK  = 0x08;
    public static final byte TYPE_CANCEL        = 0x09;
    public static final byte TYPE_ERROR         = 0x0A;
    /** peer 雙向模型的開場握手(連上後雙方各送一次)。 */
    public static final byte TYPE_HELLO         = 0x0B;
    /** 心跳:雙方各自定期送,讓對端據此判斷連線存活(SO_TIMEOUT 內沒收到任何封包即視為斷線)。 */
    public static final byte TYPE_HEARTBEAT     = 0x0C;

    // ── Item Types（雙向 peer：一條 socket 可送多種內容）─────────
    public static final byte ITEM_FILE  = 0;
    public static final byte ITEM_VCARD = 1; // 名片（vCard）
    public static final byte ITEM_PHOTO = 2;
    public static final byte ITEM_TEXT  = 3; // 純文字/連結

    /** 由 MIME 判定 item 型別：圖片/影片 → {@link #ITEM_PHOTO}（可顯示縮圖）；其餘 → {@link #ITEM_FILE}。 */
    public static byte itemTypeForMime(@androidx.annotation.Nullable String mime) {
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
            return ITEM_PHOTO;
        }
        return ITEM_FILE;
    }

    // ══════════════════════════════════════════════════════════
    //  Header POJO
    // ══════════════════════════════════════════════════════════

    public static class Header {
        public int   magic     = MAGIC;
        public byte  version   = VERSION;
        public byte  type;
        public short fileCount;
        public int   fileId;
        public long  totalSize;
        public long  offset;
        public int   chunkLen;
        public short metaLen;
        public int   crc32;
        public byte  itemType;   // 見 ITEM_* 常數（僅 FILE_META 有意義）
    }

    // ══════════════════════════════════════════════════════════
    //  Encode / Decode
    // ══════════════════════════════════════════════════════════

    public static byte[] encodeHeader(Header h) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(h.magic);
        buf.put(h.version);
        buf.put(h.type);
        buf.putShort(h.fileCount);
        buf.putInt(h.fileId);
        buf.putLong(h.totalSize);
        buf.putLong(h.offset);
        buf.putInt(h.chunkLen);
        buf.putShort(h.metaLen);
        buf.putInt(h.crc32);
        buf.put(h.itemType);
        // remaining 25 bytes stay 0
        return buf.array();
    }

    public static Header decodeHeader(byte[] raw) throws InvalidPacketException {
        if (raw == null || raw.length < HEADER_SIZE)
            throw new InvalidPacketException("Header too short: " + (raw == null ? 0 : raw.length));
        ByteBuffer buf = ByteBuffer.wrap(raw, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        Header h = new Header();
        h.magic     = buf.getInt();
        h.version   = buf.get();
        h.type      = buf.get();
        h.fileCount = buf.getShort();
        h.fileId    = buf.getInt();
        h.totalSize = buf.getLong();
        h.offset    = buf.getLong();
        h.chunkLen  = buf.getInt();
        h.metaLen   = buf.getShort();
        h.crc32     = buf.getInt();
        h.itemType  = buf.get();
        if (h.magic != MAGIC)
            throw new InvalidPacketException("Bad magic: 0x" + Integer.toHexString(h.magic));
        // 對端宣告的長度一律驗證上限：杜絕惡意/損毀 header 觸發 `new byte[巨值]`（OOM）
        // 或讓 readFully 寫爆固定大小的 pooled buffer（chunkLen > CHUNK_SIZE）。
        if (h.chunkLen < 0 || h.chunkLen > MAX_PAYLOAD)
            throw new InvalidPacketException("chunkLen out of range: " + h.chunkLen);
        if (h.metaLen < 0 || h.metaLen > MAX_META_LEN)
            throw new InvalidPacketException("metaLen out of range: " + h.metaLen);
        return h;
    }

    // ══════════════════════════════════════════════════════════
    //  Header Factory Methods
    // ══════════════════════════════════════════════════════════

    public static Header makeHandshake() {
        Header h = new Header(); h.type = TYPE_HANDSHAKE; return h;
    }
    public static Header makeHandshakeAck() {
        Header h = new Header(); h.type = TYPE_HANDSHAKE_ACK; return h;
    }
    public static Header makeFileMeta(int fileId, int fileCount, long totalSize, int metaLen) {
        Header h = new Header();
        h.type = TYPE_FILE_META;
        h.fileId = fileId;
        h.fileCount = fileCount > Short.MAX_VALUE ? Short.MAX_VALUE : (short) fileCount;
        h.totalSize = totalSize;
        h.metaLen = metaLen > Short.MAX_VALUE ? Short.MAX_VALUE : (short) metaLen;
        return h;
    }
    public static Header makeHello() {
        Header h = new Header(); h.type = TYPE_HELLO; return h;
    }
    /** 帶 profile payload 的 HELLO：payload 長度放在 chunkLen（int，足以容納頭像縮圖）。 */
    public static Header makeHelloWithProfile(int payloadLen) {
        Header h = new Header(); h.type = TYPE_HELLO; h.chunkLen = payloadLen; return h;
    }
    public static Header makeHeartbeat() {
        Header h = new Header(); h.type = TYPE_HEARTBEAT; return h;
    }
    /** 雙向 peer 的項目 meta：帶 itemType。 */
    public static Header makeItemMeta(int itemId, byte itemType, long totalSize, int metaLen) {
        Header h = new Header();
        h.type = TYPE_FILE_META;
        h.fileId = itemId; h.itemType = itemType;
        h.totalSize = totalSize;
        h.metaLen = metaLen > Short.MAX_VALUE ? Short.MAX_VALUE : (short) metaLen;
        return h;
    }
    public static Header makeReadyAck(int fileId) {
        Header h = new Header(); h.type = TYPE_READY_ACK; h.fileId = fileId; return h;
    }
    public static Header makeDataChunk(int fileId, long offset, int chunkLen, int crc32) {
        Header h = new Header();
        h.type = TYPE_DATA_CHUNK;
        h.fileId = fileId; h.offset = offset;
        h.chunkLen = chunkLen; h.crc32 = crc32;
        return h;
    }
    public static Header makeComplete(int fileId) {
        Header h = new Header(); h.type = TYPE_COMPLETE; h.fileId = fileId; return h;
    }
    public static Header makeCompleteAck(int fileId) {
        Header h = new Header(); h.type = TYPE_COMPLETE_ACK; h.fileId = fileId; return h;
    }
    public static Header makeCancel() {
        Header h = new Header(); h.type = TYPE_CANCEL; return h;
    }

    // ══════════════════════════════════════════════════════════
    //  CRC32
    // ══════════════════════════════════════════════════════════

    public static int crc32(byte[] data, int off, int len) {
        CRC32 c = new CRC32();
        c.update(data, off, len);
        return (int) c.getValue();
    }

    // ══════════════════════════════════════════════════════════
    //  Exception
    // ══════════════════════════════════════════════════════════

    public static class InvalidPacketException extends Exception {
        public InvalidPacketException(String msg) { super(msg); }
    }
}
