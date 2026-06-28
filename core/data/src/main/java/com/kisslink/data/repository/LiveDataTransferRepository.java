package com.kisslink.data.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.kisslink.data.db.AppDatabase;
import com.kisslink.data.db.TransferDao;
import com.kisslink.data.db.TransferRecordEntity;
import com.kisslink.domain.TransferRecord;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link ITransferRepository} 的 Room + LiveData 實作。
 *
 * <p>所有 LiveData 查詢由 Room 在背景自動執行後切回主執行緒；
 * 寫入操作由單一執行緒的 executor 排隊執行。
 * Entity ↔ DomainModel 轉換由 {@link TransferRecordMapper} 負責。
 */
public class LiveDataTransferRepository implements ITransferRepository {

    private static volatile LiveDataTransferRepository instance;

    private final TransferDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LiveDataTransferRepository(Context context) {
        dao = AppDatabase.getInstance(context).transferDao();
    }

    public static LiveDataTransferRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (LiveDataTransferRepository.class) {
                if (instance == null) instance = new LiveDataTransferRepository(context);
            }
        }
        return instance;
    }

    // ── 讀取 ──────────────────────────────────────────────────

    @Override
    public LiveData<List<TransferRecord>> getAllRecords() {
        return mapEntities(dao.getAllRecords());
    }

    @Override
    public LiveData<List<TransferRecord>> getByBatch(long batchId) {
        return mapEntities(dao.getByBatch(batchId));
    }

    @Override
    public LiveData<List<TransferRecord>> search(String query, String direction) {
        String like = "%" + (query == null ? "" : query) + "%";
        return mapEntities(dao.search(like, direction));
    }

    // ── 寫入 ──────────────────────────────────────────────────

    @Override
    public void insert(TransferRecord record) {
        executor.execute(() -> dao.insert(TransferRecordMapper.toEntity(record)));
    }

    @Override
    public void delete(long id) {
        executor.execute(() -> dao.deleteById(id));
    }

    @Override
    public void deleteByBatch(long batchId) {
        executor.execute(() -> dao.deleteByBatch(batchId));
    }

    @Override
    public void clearAll() {
        executor.execute(dao::deleteAll);
    }

    // ── 輔助：MediatorLiveData 映射（避免 deprecated Transformations.map()）──

    private static LiveData<List<TransferRecord>> mapEntities(
            LiveData<List<TransferRecordEntity>> source) {
        MediatorLiveData<List<TransferRecord>> result = new MediatorLiveData<>();
        result.addSource(source,
                entities -> result.setValue(TransferRecordMapper.toDomain(entities)));
        return result;
    }
}
