package com.kisslink.ui.main;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.kisslink.data.db.TransferRecordEntity;
import com.kisslink.data.repository.TransferRepository;

import java.util.List;

public class MainViewModel extends AndroidViewModel {

    private final TransferRepository repository;

    public MainViewModel(@NonNull Application app) {
        super(app);
        repository = TransferRepository.getInstance(app);
    }

    public LiveData<List<TransferRecordEntity>> getRecentRecords() {
        return repository.getRecentRecords(20);
    }

    public void deleteRecord(long id) {
        repository.delete(id);
    }

    public void clearAll() {
        repository.clearAll();
    }
}
