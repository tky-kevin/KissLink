package com.nfctransfer.app.data;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryRepository {

    private final TransferDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public HistoryRepository(Context context) {
        dao = TransferDatabase.getInstance(context.getApplicationContext()).transferDao();
    }

    public void insert(TransferRecord record) {
        executor.execute(() -> dao.insert(record));
    }

    public LiveData<List<TransferRecord>> getAllRecords() {
        MutableLiveData<List<TransferRecord>> liveData = new MutableLiveData<>();
        executor.execute(() -> liveData.postValue(dao.getAll()));
        return liveData;
    }

    public void deleteAll() {
        executor.execute(dao::deleteAll);
    }
}
