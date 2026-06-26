package com.kisslink.data.repository;

import androidx.lifecycle.LiveData;

import com.kisslink.domain.TransferRecord;

import java.util.List;

/**
 * 傳輸紀錄倉庫介面——使用領域模型 {@link TransferRecord} 作為 API 邊界。
 *
 * <p>由 {@link LiveDataTransferRepository} 實作（Room + Mapper）。
 * 上層（ViewModel）依賴本介面，可在測試中替換為 fake 實作。
 */
public interface ITransferRepository {

    LiveData<List<TransferRecord>> getAllRecords();

    LiveData<List<TransferRecord>> getByBatch(long batchId);

    /**
     * 搜尋：在 fileName/peerDeviceName 中模糊匹配。
     *
     * @param direction "SEND"、"RECEIVE"，或 null/空字串（不過濾）。
     */
    LiveData<List<TransferRecord>> search(String query, String direction);

    void insert(TransferRecord record);

    void delete(long id);

    void deleteByBatch(long batchId);

    void clearAll();
}
