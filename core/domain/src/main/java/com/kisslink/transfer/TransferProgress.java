package com.kisslink.transfer;

import java.util.Locale;

/** 傳輸進度快照，由 PeerConnection 透過 LiveData 發射。 不可變（所有欄位 final），可安全跨執行緒傳遞。 */
public final class TransferProgress {

    public enum Phase {
        /** 等待 TCP 連線 */
        WAITING,
        /** TCP 已連線，握手完成 */
        CONNECTED,
        /** 傳輸中 */
        TRANSFERRING,
        /** 單檔完成 */
        FILE_DONE,
        /** 全部完成 */
        ALL_DONE,
        /** 已取消 */
        CANCELLED,
        /** 發生錯誤 */
        ERROR
    }

    public final Phase phase;
    public final String fileName; // 當前傳輸的檔案名稱（不含方向前綴）
    public final long totalBytes; // 當前檔案大小 (bytes)
    public final long doneBytes; // 已傳輸位元組
    public final long speedBps; // 即時速度 bytes/sec
    public final int fileIndex; // 當前檔案序號（0-based）
    public final int fileCount; // 本次 session 總檔案數
    public final String errorMessage; // 僅 phase==ERROR 時有值
    public final boolean outgoing; // true=本端傳送中；false=接收中
    public final byte itemType; // TransferProtocol.ITEM_*
    public final long batchId; // 該項所屬批次（分塊用）

    private TransferProgress(Builder b) {
        this.phase = b.phase;
        this.fileName = b.fileName;
        this.totalBytes = b.totalBytes;
        this.doneBytes = b.doneBytes;
        this.speedBps = b.speedBps;
        this.fileIndex = b.fileIndex;
        this.fileCount = b.fileCount;
        this.errorMessage = b.errorMessage;
        this.outgoing = b.outgoing;
        this.itemType = b.itemType;
        this.batchId = b.batchId;
    }

    /** 進度百分比 0–100，若 totalBytes 未知回傳 -1。 */
    public int percentInt() {
        if (totalBytes <= 0) return -1;
        return (int) (doneBytes * 100L / totalBytes);
    }

    /** 格式化速度字串，例如「12.3 MB/s」。 */
    public String speedLabel() {
        if (speedBps < 1024) return speedBps + " B/s";
        if (speedBps < 1024 * 1024)
            return String.format(Locale.getDefault(), "%.1f KB/s", speedBps / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB/s", speedBps / (1024.0 * 1024));
    }

    /** 格式化剩餘時間，例如「剩餘 00:42」。 */
    public String etaLabel() {
        if (speedBps <= 0 || totalBytes <= 0) return "";
        long remaining = (totalBytes - doneBytes) / speedBps;
        return String.format(Locale.getDefault(), "剩餘 %02d:%02d", remaining / 60, remaining % 60);
    }

    // ── 靜態工廠 ──────────────────────────────────────────────

    public static TransferProgress waiting() {
        return new Builder().phase(Phase.WAITING).build();
    }

    public static TransferProgress connected() {
        return new Builder().phase(Phase.CONNECTED).build();
    }

    public static TransferProgress allDone(int fileCount) {
        return new Builder().phase(Phase.ALL_DONE).fileCount(fileCount).build();
    }

    public static TransferProgress cancelled() {
        return new Builder().phase(Phase.CANCELLED).build();
    }

    public static TransferProgress error(String msg) {
        return new Builder().phase(Phase.ERROR).errorMessage(msg).build();
    }

    // ── Builder ────────────────────────────────────────────────

    public static class Builder {
        Phase phase = Phase.WAITING;
        String fileName = "";
        long totalBytes;
        long doneBytes;
        long speedBps;
        int fileIndex;
        int fileCount;
        String errorMessage = "";
        boolean outgoing;
        byte itemType;
        long batchId;

        public Builder phase(Phase p) {
            this.phase = p;
            return this;
        }

        public Builder fileName(String n) {
            this.fileName = n;
            return this;
        }

        public Builder totalBytes(long t) {
            this.totalBytes = t;
            return this;
        }

        public Builder doneBytes(long d) {
            this.doneBytes = d;
            return this;
        }

        public Builder speedBps(long s) {
            this.speedBps = s;
            return this;
        }

        public Builder fileIndex(int i) {
            this.fileIndex = i;
            return this;
        }

        public Builder fileCount(int c) {
            this.fileCount = c;
            return this;
        }

        public Builder errorMessage(String m) {
            this.errorMessage = m;
            return this;
        }

        public Builder outgoing(boolean o) {
            this.outgoing = o;
            return this;
        }

        public Builder itemType(byte t) {
            this.itemType = t;
            return this;
        }

        public Builder batchId(long b) {
            this.batchId = b;
            return this;
        }

        public TransferProgress build() {
            return new TransferProgress(this);
        }
    }
}
