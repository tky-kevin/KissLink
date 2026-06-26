package com.kisslink.domain;

/**
 * 傳輸紀錄的領域模型（純 Java，無 Room 依賴）。
 * 由 :core:data 層的 {@code TransferRecordMapper} 從 {@code TransferRecordEntity} 轉換而來。
 */
public final class TransferRecord {

    public enum Direction { SEND, RECEIVE, UNKNOWN }

    public final long id;
    public final Direction direction;
    public final String peerDeviceName;
    public final String fileName;
    public final long fileSizeBytes;
    public final long timestampMs;
    public final boolean success;
    public final long avgSpeedBps;
    public final String filePath;
    public final String mimeType;
    public final long batchId;

    public TransferRecord(long id, Direction direction, String peerDeviceName,
                          String fileName, long fileSizeBytes, long timestampMs,
                          boolean success, long avgSpeedBps,
                          String filePath, String mimeType, long batchId) {
        this.id = id;
        this.direction = direction;
        this.peerDeviceName = peerDeviceName;
        this.fileName = fileName;
        this.fileSizeBytes = fileSizeBytes;
        this.timestampMs = timestampMs;
        this.success = success;
        this.avgSpeedBps = avgSpeedBps;
        this.filePath = filePath;
        this.mimeType = mimeType;
        this.batchId = batchId;
    }
}
