package com.kisslink.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room 資料庫的傳輸紀錄實體，對應 {@code transfer_records} 表。
 */
@Entity(tableName = "transfer_records", indices = {
        @Index("batchId"),
        @Index("timestampMs")
})
public class TransferRecordEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 傳送 / 接收 */
    public String direction; // "SEND" or "RECEIVE"

    /** 對方裝置名稱（暫存，M6 可擴充）*/
    public String peerDeviceName;

    /** 檔案名稱 */
    public String fileName;

    /** 檔案大小（bytes）*/
    public long fileSizeBytes;

    /** 傳輸完成 / 失敗時間（Unix timestamp ms）*/
    public long timestampMs;

    /** 是否成功完成 */
    public boolean success;

    /** 平均傳輸速度（bytes/sec）*/
    public long avgSpeedBps;

    /** 可開啟的位置：接收方為存檔 content uri、傳送方為來源 uri */
    public String filePath;

    /** MIME 類型（決定開啟方式） */
    public String mimeType;

    /** 同一次傳送/接收 burst 的批次識別（分塊用；0 表示未知） */
    public long batchId;
}
