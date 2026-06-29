package com.kisslink.ui.history;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textfield.TextInputEditText;
import com.kisslink.R;
import com.kisslink.data.db.TransferRecordEntity;
import com.kisslink.data.repository.TransferRepository;
import com.kisslink.util.FileUtils;
import com.kisslink.util.ThumbUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 傳輸紀錄 bottom sheet——每筆傳輸（同方向、相近時間）整理成一塊，點檔案即開啟。 支援關鍵字搜尋（fileName/peerDeviceName）與方向篩選（全部/傳送/接收）。
 */
public class HistorySheet extends BottomSheetDialogFragment {

    private static final long GROUP_GAP_MS = 90_000;
    private static final String ARG_BATCH = "batch_id";

    /** Fragment Result key：批次紀錄已被清除 → host 可據此清掉「本次接收」live 橫幅。 */
    public static final String RESULT_BATCH_CLEARED = "history.batch_cleared";

    private RecyclerView rv;
    private TextView tvEmpty;
    private final Adapter adapter = new Adapter();

    // 搜尋 / 篩選狀態
    private String currentQuery = "";
    private String currentDirection = ""; // "" = 全部, "SEND", "RECEIVE"
    @Nullable private LiveData<List<TransferRecordEntity>> currentLiveData;
    private long batchId;

    /** 只顯示某一批次（例如「本次接收」）；batchId=0 顯示全部紀錄。 */
    public static HistorySheet forBatch(long batchId) {
        HistorySheet s = new HistorySheet();
        Bundle b = new Bundle();
        b.putLong(ARG_BATCH, batchId);
        s.setArguments(b);
        return s;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle saved) {
        return inflater.inflate(R.layout.sheet_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
        rv = v.findViewById(R.id.rvHistory);
        tvEmpty = v.findViewById(R.id.tvEmpty);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        batchId = getArguments() != null ? getArguments().getLong(ARG_BATCH, 0) : 0;
        TextView title = v.findViewById(R.id.tvSheetTitle);
        if (batchId != 0 && title != null) title.setText(R.string.batch_title);

        // 批次模式不顯示搜尋/篩選
        if (batchId != 0) {
            View searchLayout = v.findViewById(R.id.searchLayout);
            View chipGroup = v.findViewById(R.id.chipGroup);
            if (searchLayout != null) searchLayout.setVisibility(View.GONE);
            if (chipGroup != null) chipGroup.setVisibility(View.GONE);
        }

        ImageButton btnClearAll = v.findViewById(R.id.btnClearAll);
        btnClearAll.setOnClickListener(
                x -> {
                    TransferRepository repo = TransferRepository.getInstance(requireContext());
                    if (batchId != 0) {
                        repo.deleteByBatch(batchId);
                        getParentFragmentManager()
                                .setFragmentResult(RESULT_BATCH_CLEARED, Bundle.EMPTY);
                    } else {
                        repo.clearAll();
                    }
                    dismiss();
                });

        // 搜尋欄
        TextInputEditText etSearch = v.findViewById(R.id.etSearch);
        if (etSearch != null) {
            etSearch.addTextChangedListener(
                    new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                        @Override
                        public void onTextChanged(CharSequence s, int st, int b, int c) {}

                        @Override
                        public void afterTextChanged(Editable s) {
                            currentQuery = s == null ? "" : s.toString().trim();
                            rebindLiveData();
                        }
                    });
        }

        // 篩選 chips
        ChipGroup chipGroup = v.findViewById(R.id.chipGroup);
        if (chipGroup != null) {
            chipGroup.setOnCheckedStateChangeListener(
                    (group, checkedIds) -> {
                        if (checkedIds.isEmpty()) return;
                        int id = checkedIds.get(0);
                        if (id == R.id.chipSend) currentDirection = "SEND";
                        else if (id == R.id.chipReceive) currentDirection = "RECEIVE";
                        else currentDirection = "";
                        rebindLiveData();
                    });
        }

        rebindLiveData();
    }

    // ── 資料綁定 ──────────────────────────────────────────────

    private void rebindLiveData() {
        if (getViewLifecycleOwner() == null) return;
        TransferRepository repo = TransferRepository.getInstance(requireContext());

        LiveData<List<TransferRecordEntity>> next;
        if (batchId != 0) {
            next = repo.getByBatch(batchId);
        } else if (currentQuery.isEmpty() && currentDirection.isEmpty()) {
            next = repo.getAllRecords();
        } else {
            next = repo.search(currentQuery.isEmpty() ? "" : currentQuery, currentDirection);
        }

        // 移除舊觀察者，換新觀察者
        if (currentLiveData != null) currentLiveData.removeObservers(getViewLifecycleOwner());
        currentLiveData = next;
        currentLiveData.observe(
                getViewLifecycleOwner(),
                records -> {
                    List<Object> flat = group(records);
                    adapter.submit(flat);
                    boolean empty = flat.isEmpty();
                    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                    rv.setVisibility(empty ? View.GONE : View.VISIBLE);
                    if (empty && !currentQuery.isEmpty()) {
                        tvEmpty.setText(R.string.history_search_empty);
                    } else {
                        tvEmpty.setText(R.string.history_empty);
                    }
                });
    }

    // ── 分塊 ──────────────────────────────────────────────────

    static final class Header {
        final String direction;
        final String peer;
        final long timestamp;
        int count;

        Header(String direction, String peer, long ts) {
            this.direction = direction;
            this.peer = peer;
            this.timestamp = ts;
        }
    }

    private List<Object> group(@Nullable List<TransferRecordEntity> records) {
        List<Object> out = new ArrayList<>();
        if (records == null || records.isEmpty()) return out;
        Header cur = null;
        TransferRecordEntity prev = null;
        for (TransferRecordEntity r : records) {
            boolean newGroup = cur == null || prev == null || !sameBatch(r, prev);
            if (newGroup) {
                cur = new Header(r.direction, r.peerDeviceName, r.timestampMs);
                out.add(cur);
            }
            cur.count++;
            out.add(r);
            prev = r;
        }
        return out;
    }

    /** 同批次：方向相同，且 batchId 一致（皆有值）或時間相近（無 batchId 的舊資料）。 */
    private static boolean sameBatch(TransferRecordEntity a, TransferRecordEntity b) {
        if (!safe(a.direction).equals(safe(b.direction))) return false;
        if (a.batchId != 0 && b.batchId != 0) return a.batchId == b.batchId;
        return Math.abs(a.timestampMs - b.timestampMs) <= GROUP_GAP_MS;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ── Adapter ───────────────────────────────────────────────

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int T_HEADER = 0, T_FILE = 1;
        private final List<Object> items = new ArrayList<>();
        private ExecutorService thumbPool = createThumbPool();
        private final Handler main = new Handler(Looper.getMainLooper());

        private ExecutorService createThumbPool() {
            return Executors.newFixedThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "history-thumb");
                        t.setDaemon(true);
                        return t;
                    });
        }

        @SuppressWarnings("NotifyDataSetChanged")
        void submit(List<Object> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @Override
        public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
            super.onDetachedFromRecyclerView(recyclerView);
            thumbPool.shutdownNow();
        }

        @Override
        public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
            super.onAttachedToRecyclerView(recyclerView);
            if (thumbPool.isShutdown()) {
                thumbPool = createThumbPool();
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof Header ? T_HEADER : T_FILE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == T_HEADER)
                return new HeaderVH(inf.inflate(R.layout.item_history_header, parent, false));
            return new FileVH(inf.inflate(R.layout.item_history_file, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Object o = items.get(position);
            Context ctx = h.itemView.getContext();
            if (o instanceof Header) {
                Header hd = (Header) o;
                HeaderVH vh = (HeaderVH) h;
                boolean send = "SEND".equals(hd.direction);
                vh.dir.setText(send ? R.string.history_send : R.string.history_receive);
                String when =
                        DateUtils.getRelativeDateTimeString(
                                        ctx,
                                        hd.timestamp,
                                        DateUtils.MINUTE_IN_MILLIS,
                                        DateUtils.WEEK_IN_MILLIS,
                                        0)
                                .toString();
                String peer = hd.peer != null && !hd.peer.isEmpty() ? " · " + hd.peer : "";
                vh.peerTime.setText("· " + when + peer);

            } else {
                TransferRecordEntity r = (TransferRecordEntity) o;
                FileVH vh = (FileVH) h;
                vh.name.setText(r.fileName);
                vh.meta.setText(sizeLabel(r.fileSizeBytes) + (r.success ? "" : " · 未完成"));
                int icon =
                        r.mimeType != null
                                ? FileUtils.guessIconFromMime(r.mimeType)
                                : FileUtils.guessIcon(r.fileName);
                vh.thumb.setImageResource(icon);
                vh.thumb.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
                vh.thumbTag = r.filePath;
                loadThumbIfImage(ctx, r, vh);
                vh.itemView.setOnClickListener(x -> openFile(ctx, r));
                vh.delete.setOnClickListener(x -> TransferRepository.getInstance(ctx).delete(r.id));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void loadThumbIfImage(Context ctx, TransferRecordEntity r, FileVH vh) {
            if (r.filePath == null || !r.filePath.startsWith("content:")) return;
            String mime = r.mimeType != null ? r.mimeType : guessMime(r.fileName);
            boolean isImage = mime != null && mime.startsWith("image");
            boolean isVideo = mime != null && mime.startsWith("video");
            if (!isImage && !isVideo) return;
            final String want = r.filePath;
            final Uri uri = Uri.parse(r.filePath);
            thumbPool.execute(
                    () -> {
                        Bitmap bm =
                                isVideo
                                        ? ThumbUtils.decodeVideo(ctx, uri, dp(ctx, 48))
                                        : ThumbUtils.decodeImage(ctx, uri, dp(ctx, 48));
                        if (bm == null) return;
                        main.post(
                                () -> {
                                    if (want.equals(vh.thumbTag)) {
                                        vh.thumb.setPadding(0, 0, 0, 0);
                                        vh.thumb.setImageBitmap(bm);
                                    }
                                });
                    });
        }
    }

    private void openFile(Context ctx, TransferRecordEntity r) {
        if ("text/plain".equals(r.mimeType)) {
            showTextRecordDialog(ctx, r);
            return;
        }
        if (r.filePath == null || !r.filePath.startsWith("content:")) {
            toast("此檔案位置未記錄，無法開啟");
            return;
        }
        try {
            Uri uri = Uri.parse(r.filePath);
            String mime = r.mimeType != null ? r.mimeType : guessMime(r.fileName);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime != null ? mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("沒有可開啟此檔案的應用程式");
        }
    }

    /** 歷史記錄中的純文字項目 → 對話框顯示全文（連結可點擊），可複製；連結可開啟。 */
    private void showTextRecordDialog(Context ctx, TransferRecordEntity r) {
        String text = r.fileName;
        boolean isLink = android.util.Patterns.WEB_URL.matcher(text).matches();
        MaterialAlertDialogBuilder b =
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle(isLink ? R.string.share_link_title : R.string.share_text_title)
                        .setMessage(text)
                        .setPositiveButton(
                                R.string.action_copy,
                                (d, w) -> {
                                    ClipboardManager cm =
                                            (ClipboardManager)
                                                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm != null) {
                                        cm.setPrimaryClip(ClipData.newPlainText("KissLink", text));
                                        toast("已複製到剪貼簿");
                                    }
                                })
                        .setNegativeButton(R.string.btn_cancel, null);
        if (isLink) {
            b.setNeutralButton(
                    R.string.action_open,
                    (d, w) -> {
                        try {
                            ctx.startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse(text))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) {
                            toast("找不到可開啟連結的應用程式");
                        }
                    });
        }
        androidx.appcompat.app.AlertDialog dialog = b.show();
        TextView msgTv = dialog.findViewById(android.R.id.message);
        if (msgTv != null) {
            msgTv.setAutoLinkMask(Linkify.WEB_URLS);
            msgTv.setMovementMethod(LinkMovementMethod.getInstance());
            msgTv.setTextIsSelectable(true);
        }
    }

    private void toast(String m) {
        if (getContext() != null)
            android.widget.Toast.makeText(getContext(), m, android.widget.Toast.LENGTH_SHORT)
                    .show();
    }

    // ── ViewHolders ──
    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView dir, peerTime;

        HeaderVH(@NonNull View v) {
            super(v);
            dir = v.findViewById(R.id.tvDir);
            peerTime = v.findViewById(R.id.tvPeerTime);
        }
    }

    static class FileVH extends RecyclerView.ViewHolder {
        final ShapeableImageView thumb;
        final TextView name, meta;
        final ImageButton delete;
        @Nullable String thumbTag;

        FileVH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.ivThumb);
            name = v.findViewById(R.id.tvName);
            meta = v.findViewById(R.id.tvMeta);
            delete = v.findViewById(R.id.ibDelete);
        }
    }

    // ── 工具 ──
    @Nullable
    private static String guessMime(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static String sizeLabel(long bytes) {
        return FileUtils.sizeLabel(bytes);
    }
}
