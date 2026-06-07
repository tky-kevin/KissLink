package com.kisslink.ui.main;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.ui.ThemeManager;

public class HistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        findViewById(R.id.btnHistoryBack).setOnClickListener(v -> finish());

        MainViewModel viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        RecyclerView rv = findViewById(R.id.rvHistoryList);
        HistoryAdapter adapter = new HistoryAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        viewModel.getRecentRecords().observe(this, adapter::submitList);
    }
}
