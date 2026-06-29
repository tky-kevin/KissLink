package com.kisslink.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room 資料庫的傳輸紀錄實體，對應 {@code transfer_records} 表。
 *
 * <p>列名以 camelCase 保持既有結構（避免 Migration）。 {@code @ColumnInfo} 作顯式文件用途，不改變既有列名。
 */
@Entity(
        tableName = "transfer_records",
        indices = {@Index("batchId"), @Index("timestampMs")})
public class TransferRecordEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 傳送 / 接收（"SEND" or "RECEIVE"） */
    @ColumnInfo(name = "direction")
    public String direction;

    /** 對方裝置名稱 */
    @ColumnInfo(name = "peerDeviceName")
    public String peerDeviceName;

    /** 檔案名稱 */
    @ColumnInfo(name = "fileName")
    public String fileName;

    /** 檔案大小（bytes） */
    @ColumnInfo(name = "fileSizeBytes")
    public long fileSizeBytes;

    /** 完成時間（Unix ms） */
    @ColumnInfo(name = "timestampMs")
    public long timestampMs;

    /** 是否成功完成 */
    @ColumnInfo(name = "success")
    public boolean success;

    /** 平均傳輸速度（bytes/sec） */
    @ColumnInfo(name = "avgSpeedBps")
    public long avgSpeedBps;

    /** 可開啟位置：接收方為 content uri、傳送方為來源 uri */
    @ColumnInfo(name = "filePath")
    public String filePath;

    /** MIME 類型 */
    @ColumnInfo(name = "mimeType")
    public String mimeType;

    /** 同一批次識別（0 表示未知） */
    @ColumnInfo(name = "batchId")
    public long batchId;
}
