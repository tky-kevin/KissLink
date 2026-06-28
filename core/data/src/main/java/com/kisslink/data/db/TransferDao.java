package com.kisslink.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 傳輸紀錄的 Room DAO。
 */
@Dao
public interface TransferDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(TransferRecordEntity record);

    /** 取得所有紀錄，按時間降序。 */
    @Query("SELECT * FROM transfer_records ORDER BY timestampMs DESC")
    LiveData<List<TransferRecordEntity>> getAllRecords();

    /** 取得最近 N 筆。 */
    @Query("SELECT * FROM transfer_records ORDER BY timestampMs DESC LIMIT :limit")
    LiveData<List<TransferRecordEntity>> getRecentRecords(int limit);

    /** 取得某一批次的所有紀錄（時間正序，符合傳輸順序）。 */
    @Query("SELECT * FROM transfer_records WHERE batchId = :batchId ORDER BY timestampMs ASC")
    LiveData<List<TransferRecordEntity>> getByBatch(long batchId);

    /**
     * 全文搜尋：在 fileName / peerDeviceName 中模糊匹配，可選擇限定方向。
     *
     * @param query     LIKE 匹配字串，呼叫端需自行加 % 前後綴。
     * @param direction 過濾方向（"SEND"/"RECEIVE"），傳 null 或 "" 表示不過濾。
     */
    @Query("SELECT * FROM transfer_records "
            + "WHERE (fileName LIKE :query OR peerDeviceName LIKE :query) "
            + "  AND (:direction IS NULL OR :direction = '' OR direction = :direction) "
            + "ORDER BY timestampMs DESC")
    LiveData<List<TransferRecordEntity>> search(String query, String direction);

    @Query("DELETE FROM transfer_records WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM transfer_records WHERE batchId = :batchId")
    void deleteByBatch(long batchId);

    @Query("DELETE FROM transfer_records")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM transfer_records")
    int getCount();
}
