package com.nfctransfer.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TransferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(TransferRecord record);

    @Query("SELECT * FROM transfer_records ORDER BY transfer_time DESC")
    List<TransferRecord> getAll();

    @Query("DELETE FROM transfer_records")
    void deleteAll();

    @Delete
    void delete(TransferRecord record);
}
