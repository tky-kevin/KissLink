package com.kisslink.ui.main;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.data.db.TransferRecordEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HistoryAdapter extends ListAdapter<TransferRecordEntity, HistoryAdapter.VH> {

    private final SimpleDateFormat sdf =
            new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault());

    public HistoryAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        TransferRecordEntity r = getItem(pos);

        boolean isSend = "SEND".equals(r.direction);
        h.tvDirectionIcon.setText(isSend ? "↑" : "↓");

        h.tvFileName.setText(r.fileName);

        String time = sdf.format(new Date(r.timestampMs));
        String peer = (r.peerDeviceName != null && !r.peerDeviceName.isEmpty())
                ? " · " + r.peerDeviceName : "";
        h.tvMeta.setText(time + peer);

        int statusColor = r.success
                ? ContextCompat.getColor(h.itemView.getContext(), R.color.notion_success)
                : ContextCompat.getColor(h.itemView.getContext(), R.color.notion_error);
        if (h.ivStatus.getBackground() != null) {
            Drawable bg = DrawableCompat.wrap(h.ivStatus.getBackground()).mutate();
            DrawableCompat.setTint(bg, statusColor);
            h.ivStatus.setBackground(bg);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvDirectionIcon, tvFileName, tvMeta;
        View ivStatus;

        VH(View v) {
            super(v);
            tvDirectionIcon = v.findViewById(R.id.tvDirectionIcon);
            tvFileName      = v.findViewById(R.id.tvFileName);
            tvMeta          = v.findViewById(R.id.tvMeta);
            ivStatus        = v.findViewById(R.id.ivStatus);
        }
    }

    private static final DiffUtil.ItemCallback<TransferRecordEntity> DIFF =
            new DiffUtil.ItemCallback<TransferRecordEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull TransferRecordEntity a,
                                               @NonNull TransferRecordEntity b) {
                    return a.id == b.id;
                }
                @Override
                public boolean areContentsTheSame(@NonNull TransferRecordEntity a,
                                                  @NonNull TransferRecordEntity b) {
                    return a.id == b.id && a.success == b.success;
                }
            };
}
