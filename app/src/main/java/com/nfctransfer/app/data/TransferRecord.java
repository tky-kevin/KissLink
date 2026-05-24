package com.nfctransfer.app.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "transfer_records")
public class TransferRecord {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "file_name")
    public String fileName;

    @ColumnInfo(name = "file_size")
    public long fileSize;

    @ColumnInfo(name = "file_path")
    public String filePath;

    @ColumnInfo(name = "transfer_time")
    public long transferTime;

    /** "SENT" or "RECEIVED" */
    @ColumnInfo(name = "direction")
    public String direction;

    /** "SUCCESS" or "FAILED" */
    @ColumnInfo(name = "status")
    public String status;

    @ColumnInfo(name = "peer_device")
    public String peerDevice;

    public TransferRecord() {}

    public TransferRecord(String fileName, long fileSize, String direction,
                          long transferTime, String status) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.direction = direction;
        this.transferTime = transferTime;
        this.status = status;
    }

    public TransferRecord(String fileName, long fileSize, String filePath,
                          long transferTime, String direction, String status, String peerDevice) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.filePath = filePath;
        this.transferTime = transferTime;
        this.direction = direction;
        this.status = status;
        this.peerDevice = peerDevice;
    }

    // Convenience getters used by existing code

    public long getId()          { return id; }
    public String getFileName()  { return fileName; }
    public long getFileSize()    { return fileSize; }
    public String getFilePath()  { return filePath; }
    public long getTimestamp()   { return transferTime; }
    public String getDirection() { return direction; }
    public String getStatus()    { return status; }
    public String getPeerDevice(){ return peerDevice; }
}
