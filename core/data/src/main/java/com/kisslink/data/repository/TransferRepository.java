package com.kisslink.data.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.kisslink.data.db.AppDatabase;
import com.kisslink.data.db.TransferDao;
import com.kisslink.data.db.TransferRecordEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 傳輸紀錄的資料存取層（Repository Pattern）。
 *
 * <p>所有寫入操作均在背景執行緒執行（Room 不允許主執行緒 DB 操作）。
 * 讀取操作透過 LiveData 自動在背景查詢並切換到主執行緒通知觀察者。
 */
public class TransferRepository {

    private static volatile TransferRepository instance;

    private final TransferDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TransferRepository(Context context) {
        dao = AppDatabase.getInstance(context).transferDao();
    }

    public static TransferRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (TransferRepository.class) {
                if (instance == null) instance = new TransferRepository(context);
            }
        }
        return instance;
    }

    // ── 寫入 ──────────────────────────────────────────────────

    public void insert(TransferRecordEntity record) {
        executor.execute(() -> dao.insert(record));
    }

    public void delete(long id) {
        executor.execute(() -> dao.deleteById(id));
    }

    public void clearAll() {
        executor.execute(dao::deleteAll);
    }

    /** 只刪某一批次（「本次接收/傳送」清單的垃圾桶用，不波及其他歷史）。 */
    public void deleteByBatch(long batchId) {
        executor.execute(() -> dao.deleteByBatch(batchId));
    }

    // ── 讀取（LiveData，主執行緒安全）────────────────────────────

    public LiveData<List<TransferRecordEntity>> getAllRecords() {
        return dao.getAllRecords();
    }

    public LiveData<List<TransferRecordEntity>> getRecentRecords(int limit) {
        return dao.getRecentRecords(limit);
    }

    public LiveData<List<TransferRecordEntity>> getByBatch(long batchId) {
        return dao.getByBatch(batchId);
    }

    /**
     * 模糊搜尋 + 可選方向過濾。
     *
     * @param query     搜尋關鍵字（自動補 % 前後綴）。
     * @param direction "SEND"、"RECEIVE"，或 null/空字串（不過濾）。
     */
    public LiveData<List<TransferRecordEntity>> search(String query, String direction) {
        return dao.search("%" + query + "%", direction);
    }

    // ── 工廠方法：從傳輸進度建立紀錄 ──────────────────────────────

    public TransferRecordEntity buildRecord(String direction, String fileName,
                                            long sizeBytes, boolean success,
                                            long avgSpeedBps, String filePath) {
        return buildRecord(direction, fileName, sizeBytes, success, avgSpeedBps,
                filePath, null, null, 0L);
    }

    public TransferRecordEntity buildRecord(String direction, String fileName,
                                            long sizeBytes, boolean success,
                                            long avgSpeedBps, String filePath,
                                            String peerName, String mimeType, long batchId) {
        TransferRecordEntity e = new TransferRecordEntity();
        e.direction     = direction;
        e.fileName      = fileName;
        e.fileSizeBytes = sizeBytes;
        e.success       = success;
        e.avgSpeedBps   = avgSpeedBps;
        e.filePath      = filePath;
        e.peerDeviceName= peerName;
        e.mimeType      = mimeType;
        e.batchId       = batchId;
        e.timestampMs   = System.currentTimeMillis();
        return e;
    }
}
