package com.kisslink.data.repository;

import com.kisslink.data.db.TransferRecordEntity;
import com.kisslink.domain.TransferRecord;

import java.util.ArrayList;
import java.util.List;

/** 把 Room 實體映射到領域模型，反向亦然。 */
public final class TransferRecordMapper {

    private TransferRecordMapper() {}

    public static TransferRecord toDomain(TransferRecordEntity e) {
        TransferRecord.Direction dir;
        if ("SEND".equals(e.direction)) dir = TransferRecord.Direction.SEND;
        else if ("RECEIVE".equals(e.direction)) dir = TransferRecord.Direction.RECEIVE;
        else dir = TransferRecord.Direction.UNKNOWN;

        return new TransferRecord(e.id, dir, e.peerDeviceName, e.fileName,
                e.fileSizeBytes, e.timestampMs, e.success, e.avgSpeedBps,
                e.filePath, e.mimeType, e.batchId);
    }

    public static List<TransferRecord> toDomain(List<TransferRecordEntity> entities) {
        if (entities == null) return new ArrayList<>();
        List<TransferRecord> out = new ArrayList<>(entities.size());
        for (TransferRecordEntity e : entities) out.add(toDomain(e));
        return out;
    }

    public static TransferRecordEntity toEntity(TransferRecord r) {
        TransferRecordEntity e = new TransferRecordEntity();
        e.id = r.id;
        e.direction = r.direction == TransferRecord.Direction.SEND ? "SEND" : "RECEIVE";
        e.peerDeviceName = r.peerDeviceName;
        e.fileName = r.fileName;
        e.fileSizeBytes = r.fileSizeBytes;
        e.timestampMs = r.timestampMs;
        e.success = r.success;
        e.avgSpeedBps = r.avgSpeedBps;
        e.filePath = r.filePath;
        e.mimeType = r.mimeType;
        e.batchId = r.batchId;
        return e;
    }
}
