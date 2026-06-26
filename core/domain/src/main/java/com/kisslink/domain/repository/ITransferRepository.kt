package com.kisslink.domain.repository

import com.kisslink.domain.model.TransferRecord
import kotlinx.coroutines.flow.Flow

/** Domain contract for transfer history persistence. */
interface ITransferRepository {
    fun getAllRecords(): Flow<List<TransferRecord>>
    fun getRecentRecords(limit: Int): Flow<List<TransferRecord>>
    fun getByBatch(batchId: Long): Flow<List<TransferRecord>>

    suspend fun insert(record: TransferRecord): Long
    suspend fun delete(id: Long)
    suspend fun deleteByBatch(batchId: Long)
    suspend fun clearAll()
}
