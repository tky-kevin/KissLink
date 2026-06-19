package com.kisslink.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.transfer.TransferProtocol;
import com.kisslink.utils.FileUtils;
import com.kisslink.utils.ThumbUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beam 中央下方的項目清單：已選待送、傳輸中（含百分比）、完成（勾）。
 * 相片/影片載入小縮圖；檔案用圖示。
 */
public class SendListAdapter extends RecyclerView.Adapter<SendListAdapter.VH> {

    public interface OnRemove { void onRemove(int position); }
    public interface OnItemClickListener { void onItemClick(SendRow row); }

    private final List<SendRow> rows = new ArrayList<>();
    // 縮圖執行緒池，在 onDetachedFromRecyclerView 關閉（避免累積），
    // 並在 onAttachedToRecyclerView 重建（BottomSheet 展開/收合會 detach/attach）。
    private ExecutorService thumbPool = newThumbPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private static ExecutorService newThumbPool() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "send-thumb");
            t.setDaemon(true);
            return t;
        });
    }
    @androidx.annotation.Nullable private OnRemove onRemove;
    @androidx.annotation.Nullable private OnItemClickListener onItemClickListener;

    public void setOnRemove(@androidx.annotation.Nullable OnRemove l) { this.onRemove = l; }
    public void setOnItemClickListener(@androidx.annotation.Nullable OnItemClickListener l) { this.onItemClickListener = l; }

    @SuppressWarnings("NotifyDataSetChanged")
    public void submit(@NonNull List<SendRow> next) {
        rows.clear();
        rows.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_send, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SendRow r = rows.get(position);
        Context ctx = h.itemView.getContext();

        h.name.setText(r.name);

        h.meta.setVisibility(View.VISIBLE);
        // 中間 meta：傳輸中顯示方向，否則大小
        if (r.percent >= 0 && !r.done) {
            h.meta.setText(r.incoming ? ctx.getString(R.string.receiving)
                                      : ctx.getString(R.string.sending));
        } else {
            h.meta.setText(r.sizeLabel);
        }

        // 右側狀態
        if (r.done) {
            h.status.setText("✓");
            h.status.setTextColor(ctx.getColor(R.color.beam_accent));
        } else if (r.percent >= 0) {
            h.status.setText(r.percent + "%");
            h.status.setTextColor(ctx.getColor(R.color.beam_accent));
        } else {
            h.status.setText(r.sizeLabel.isEmpty() ? "" : r.sizeLabel);
            h.status.setTextColor(ctx.getColor(R.color.beam_muted));
            h.meta.setVisibility(View.GONE);
        }

        // 移除鈕（待傳清單）
        h.remove.setVisibility(r.removable && !r.done && r.percent < 0 ? View.VISIBLE : View.GONE);
        h.remove.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && onRemove != null) onRemove.onRemove(pos);
        });

        // 項目點擊（開啟檔案）
        h.itemView.setOnClickListener(v -> {
            if (onItemClickListener != null && r.fileUri != null) {
                onItemClickListener.onItemClick(r);
            }
        });

        // 縮圖
        h.thumb.setImageResource(FileUtils.iconFor(r.itemType, r.mime, r.name));
        h.thumb.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        h.thumbTag = r.thumbUri;
        if (r.isVisualMedia() && r.thumbUri != null) {
            final Uri want = r.thumbUri;
            try {
                thumbPool.execute(() -> {
                    Bitmap bm = ThumbUtils.decode(ctx, want, dp(ctx, 48));
                    if (bm == null) return;
                    main.post(() -> {
                        if (want.equals(h.thumbTag)) {
                            h.thumb.setPadding(0, 0, 0, 0);
                            h.thumb.setImageBitmap(bm);
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // pool 已 shutdown（不應發生，但作為最後防線）
            }
        }
    }

    @Override public int getItemCount() { return rows.size(); }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        // BottomSheet 展開/收合會觸發 detach → attach；若池子已關閉則重建
        if (thumbPool.isShutdown()) {
            thumbPool = newThumbPool();
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        thumbPool.shutdownNow(); // 釋放縮圖執行緒，避免 Activity 重建時累積
    }

    private static String itemTypeLabel(Context c, byte t) {
        switch (t) {
            case TransferProtocol.ITEM_VCARD: return "名片";
            case TransferProtocol.ITEM_PHOTO: return "相片／影片";
            default: return "檔案";
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ShapeableImageView thumb;
        final TextView name, meta, status;
        final android.widget.ImageButton remove;
        @androidx.annotation.Nullable Uri thumbTag;
        VH(@NonNull View v) {
            super(v);
            thumb  = v.findViewById(R.id.ivThumb);
            name   = v.findViewById(R.id.tvName);
            meta   = v.findViewById(R.id.tvMeta);
            status = v.findViewById(R.id.tvStatus);
            remove = v.findViewById(R.id.ibRemove);
        }
    }
}
