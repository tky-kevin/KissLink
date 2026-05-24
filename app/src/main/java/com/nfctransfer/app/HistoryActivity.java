package com.nfctransfer.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.nfctransfer.app.data.TransferDatabase;
import com.nfctransfer.app.data.TransferRecord;
import com.nfctransfer.app.util.FileUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory;
    private TextView tvEmpty;

    private HistoryAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        rvHistory = findViewById(R.id.rv_history);
        tvEmpty   = findViewById(R.id.tv_empty);

        adapter = new HistoryAdapter(new ArrayList<>());
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(adapter);

        findViewById(R.id.btn_clear_history).setOnClickListener(v -> confirmClear());

        loadHistory();
    }

    private void loadHistory() {
        executor.execute(() -> {
            List<TransferRecord> records =
                    TransferDatabase.getInstance(this).transferDao().getAll();
            runOnUiThread(() -> {
                adapter.setRecords(records);
                tvEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
                rvHistory.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清除紀錄")
                .setMessage("確定要清除所有傳輸紀錄？")
                .setPositiveButton("清除", (d, w) -> clearHistory())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearHistory() {
        executor.execute(() -> {
            TransferDatabase.getInstance(this).transferDao().deleteAll();
            runOnUiThread(this::loadHistory);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

        private List<TransferRecord> records;

        HistoryAdapter(List<TransferRecord> records) {
            this.records = records;
        }

        void setRecords(List<TransferRecord> records) {
            this.records = records;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_transfer_record, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TransferRecord r = records.get(position);

            holder.tvFileName.setText(r.getFileName() != null ? r.getFileName() : "未知檔案");

            String sizeStr = FileUtils.formatFileSize(r.getFileSize());
            String dir = r.getDirection();
            String dirSymbol = ("SENT".equals(dir) || "SEND".equals(dir)) ? "↑ 傳送" : "↓ 接收";
            String timeStr = FileUtils.formatTimestamp(r.getTimestamp());
            holder.tvMeta.setText(sizeStr + "  " + dirSymbol + "  " + timeStr);

            String status = r.getStatus();
            holder.tvStatus.setText("SUCCESS".equals(status) ? "成功" : "失敗");
            holder.tvStatus.setTextColor("SUCCESS".equals(status)
                    ? 0xFF4CAF50 : 0xFFF44336);

            if (FileUtils.isImageFile(r.getFileName()) && r.getFilePath() != null) {
                Glide.with(holder.ivIcon.getContext())
                        .load(r.getFilePath())
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .centerCrop()
                        .into(holder.ivIcon);
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_agenda);
            }
        }

        @Override
        public int getItemCount() {
            return records == null ? 0 : records.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvFileName;
            TextView tvMeta;
            TextView tvStatus;

            ViewHolder(View v) {
                super(v);
                ivIcon     = v.findViewById(R.id.iv_file_icon);
                tvFileName = v.findViewById(R.id.tv_file_name);
                tvMeta     = v.findViewById(R.id.tv_file_meta);
                tvStatus   = v.findViewById(R.id.tv_status);
            }
        }
    }
}
