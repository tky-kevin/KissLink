package com.kisslink.ui.home;

import android.graphics.Rect;
import android.net.Uri;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.TransferProgress;
import com.kisslink.transfer.TransferProtocol;
import java.util.ArrayList;
import java.util.List;

/**
 * 傳輸/接收清單方塊（{@code rvTransfer}）的單一擁有者。
 *
 * <p>把原本散在 {@link HomeActivity} 的四個可變旗標——是否在傳輸版面、接收清單是否顯示中、
 * 自動捲動是否仍生效、上次自動捲到的列——以及所有清單渲染方法收斂於此。先前這些旗標被十餘個 render 方法跨來跨去地讀寫，是「狀態散落＋手動同步」的漂移溫床；集中後此類別是清單顯示狀態的
 * 唯一權威，{@link HomeActivity} 只透過明確 API 委派。
 *
 * <p>持久接收清單的「資料」真相仍在 {@link HomeViewModel}（跨 Activity 重建存活）；本類別只持有 「呈現」狀態（View 重建時由 sticky
 * SessionState + VM 真相重新推導），符合 MVVM 分層。
 */
final class TransferListPresenter {

    private final MaxHeightRecyclerView rv;
    private final SendListAdapter adapter;
    private final HomeViewModel vm;

    // 傳輸列表自動捲動到當前列；使用者一旦手動拖動即停（本次傳輸內不再自動捲）。
    private boolean autoScroll = true;
    private boolean inTransferUi = false; // 是否已進入傳輸版面（用於每段傳輸只重置一次自動捲動）
    private int lastAutoScrollIndex = -1; // 上次自動捲到的列，避免每幀重複 smoothScroll
    private boolean receiveListActive = false; // rvTransfer 目前顯示的是「接收列表」(持久)而非送出列表

    TransferListPresenter(
            @NonNull MaxHeightRecyclerView rv,
            @NonNull HomeViewModel vm,
            @NonNull SendListAdapter.OnItemClickListener onItemClick) {
        this.rv = rv;
        this.vm = vm;
        // 傳輸中列表方塊：唯讀（可點開檔案、不可移除），獨立 adapter
        // （與待傳 sheet 的 adapter 分離，避免縮圖執行緒池共用衝突）。
        adapter = new SendListAdapter();
        adapter.setOnItemClickListener(onItemClick);
        rv.setLayoutManager(new LinearLayoutManager(rv.getContext()));
        rv.setAdapter(adapter);
        rv.setClipToOutline(true); // 內容裁切到 bg_chip 的圓角，列高亮/淡出貼齊邊緣
        float density = rv.getContext().getResources().getDisplayMetrics().density;
        // 高度上限：約 4.5 列；≤上限依內容自適應(上下對稱)，超過才固定並可捲動。
        rv.setMaxHeight(Math.round(252 * density));
        // 上下留白用 ItemDecoration（不可用 padding，否則內建淡出邊會被推離圓角邊緣）。
        final int rvVPad = Math.round(12 * density);
        rv.addItemDecoration(
                new RecyclerView.ItemDecoration() {
                    @Override
                    public void getItemOffsets(
                            @NonNull Rect outRect,
                            @NonNull View view,
                            @NonNull RecyclerView parent,
                            @NonNull RecyclerView.State state) {
                        int pos = parent.getChildAdapterPosition(view);
                        if (pos == 0) outRect.top = rvVPad;
                        int count =
                                parent.getAdapter() != null
                                        ? parent.getAdapter().getItemCount()
                                        : 0;
                        if (count > 0 && pos == count - 1) outRect.bottom = rvVPad;
                    }
                });
        rv.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(@NonNull RecyclerView r, int newState) {
                        // 使用者手動拖動 → 交還捲動控制權，本次傳輸內不再自動捲。
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) autoScroll = false;
                    }
                });
    }

    // ── 呈現狀態查詢 ──────────────────────────────────────────────
    boolean isReceiveListActive() {
        return receiveListActive;
    }

    boolean isListVisible() {
        return rv.getVisibility() == View.VISIBLE;
    }

    /** 收完一個檔 → 補上該列縮圖（payload 局部更新，不重建、不 flicker）。 */
    void updateReceivedThumbnail(@NonNull String name, @Nullable Uri uri, @Nullable String mime) {
        adapter.updateReceivedThumbnail(name, uri, mime);
    }

    // ── 傳輸版面進出 ──────────────────────────────────────────────

    /** 進入傳輸版面的第一幀：每段傳輸只重置一次自動捲動（之後每幀都會走到，但不重置）。 */
    void beginTransferFrame() {
        if (!inTransferUi) {
            inTransferUi = true;
            autoScroll = true;
            lastAutoScrollIndex = -1;
        }
    }

    /** 離開傳輸版面（回到已連線/完成/就緒）：送出列表收合； 接收列表為持久顯示（收完仍保留，供檢視/點開）→ 不收合。 */
    void exitTransfer() {
        inTransferUi = false;
        if (!receiveListActive) rv.setVisibility(View.GONE);
    }

    /** 回到就緒畫面：收合列表方塊（含持久接收列表）。 */
    void reset() {
        inTransferUi = false;
        receiveListActive = false;
        rv.setVisibility(View.GONE);
    }

    // ── 送出方列表 ────────────────────────────────────────────────

    /**
     * 把待傳清單展開成「列表方塊」（沿用待傳列設計、無標題）。只在進入傳輸時建一次—— 結構（檔名/大小/縮圖）在整段傳輸內不變，逐幀的進度/高亮由 {@link
     * #updateOutgoingProgress} 局部更新，避免每幀整列重綁導致縮圖閃爍。
     */
    void buildSendList() {
        receiveListActive = false; // 切回送出列表
        List<SendRow> rows = new ArrayList<>();
        for (SendItem it : vm.currentSelection()) {
            rows.add(
                    new SendRow(
                            it.name,
                            TransferUiController.sizeLabel(it.size),
                            it.itemType,
                            it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null,
                            it.uri,
                            it.mime));
        }
        adapter.submit(rows);
        rv.setVisibility(View.VISIBLE);
        Anim.fadeUp(rv);
    }

    /** 逐幀更新傳輸中當前列的進度/完成/高亮（payload 局部 rebind，不碰縮圖），並自動捲動到當前列。 */
    void updateOutgoingProgress(@NonNull TransferProgress tp, boolean curFileDone) {
        int idx = indexOfSelection(tp.fileName);
        // 後面那列開始 → 前面各列必然已送完，回填被 LiveData 合併丟棄的中間檔完成。
        if (idx > 0) adapter.markRowsDoneBefore(idx);
        adapter.setProgress(tp.fileName, curFileDone ? 100 : tp.percentInt(), curFileDone);
        if (autoScroll && idx >= 0 && idx != lastAutoScrollIndex) {
            lastAutoScrollIndex = idx;
            rv.smoothScrollToPosition(idx);
        }
    }

    // ── 接收方列表 ────────────────────────────────────────────────

    /**
     * 接收方列表（取代「收到 N 個」橫幅）：新檔觸發結構重建（full submit）， 既有檔案只更新進度/狀態（payload → 不碰縮圖、不 flicker）。
     * 收完整批後仍保留（持久），可點開個別檔案。
     */
    void showReceiveList(@NonNull TransferProgress tp, boolean curFileDone) {
        boolean isNew =
                vm.upsertReceived(
                        tp.batchId,
                        tp.fileName,
                        tp.totalBytes,
                        tp.itemType,
                        tp.percentInt(),
                        curFileDone);
        receiveListActive = true;
        if (isNew) rebuildReceiveList();
        else updateReceiveProgress(tp, curFileDone);
    }

    /**
     * 接收清單顯示中、非傳輸中、且有待傳項目 → 收合為「收到 N 個」橫幅，讓出空間給待傳清單/NFC 波紋。
     *
     * <p>由「接收完成」「待傳清單變動」「重建還原」三條路徑共用：收合是狀態(有清單+有待傳)的函數， 不該只綁在 selection
     * 改變這單一事件上（否則「本來就有待傳項目」時收檔完不會收合）。
     */
    void collapseIfSendPending() {
        if (receiveListActive && !inTransferUi && !vm.isSelectionEmpty()) {
            receiveListActive = false;
            rv.setVisibility(View.GONE);
            vm.collapseReceiveListToBanner();
        }
    }

    /** Activity 重建後（VM 仍保有接收清單）→ 重新顯示持久的接收列表。 */
    void restoreIfAny() {
        if (!receiveListActive && vm.hasReceivedList()) {
            receiveListActive = true;
            rebuildReceiveList();
        }
        collapseIfSendPending(); // 重建後若已有待傳項目 → 直接呈現橫幅而非整張清單
    }

    // ── 內部 ──────────────────────────────────────────────────────

    /** 接收方逐幀進度：payload 更新不重設縮圖，不 flicker。 */
    private void updateReceiveProgress(@NonNull TransferProgress tp, boolean curFileDone) {
        adapter.setProgress(tp.fileName, curFileDone ? 100 : tp.percentInt(), curFileDone);
        int idx = adapter.findPositionByName(tp.fileName);
        if (autoScroll && idx >= 0 && idx != lastAutoScrollIndex) {
            lastAutoScrollIndex = idx;
            rv.smoothScrollToPosition(idx);
        }
    }

    private void rebuildReceiveList() {
        List<SendRow> rows = new ArrayList<>();
        int currentIndex = -1, i = 0;
        for (HomeViewModel.RecvFile f : vm.receivedFiles()) {
            Uri fileUri = f.uri != null ? Uri.parse(f.uri) : null;
            SendRow r =
                    new SendRow(
                            f.name,
                            TransferUiController.sizeLabel(f.size),
                            f.type,
                            f.type == TransferProtocol.ITEM_PHOTO ? fileUri : null,
                            fileUri,
                            f.mime);
            r.incoming = true;
            r.percent = f.percent;
            r.done = f.done;
            r.highlight = f.highlight;
            rows.add(r);
            if (f.highlight) currentIndex = i;
            i++;
        }
        adapter.submit(rows);
        if (rv.getVisibility() != View.VISIBLE) {
            rv.setVisibility(View.VISIBLE);
            Anim.fadeUp(rv);
        }
        if (autoScroll && currentIndex >= 0 && currentIndex != lastAutoScrollIndex) {
            lastAutoScrollIndex = currentIndex;
            rv.smoothScrollToPosition(currentIndex);
        }
    }

    private int indexOfSelection(@NonNull String name) {
        List<SendItem> selection = vm.currentSelection();
        for (int i = 0; i < selection.size(); i++) {
            if (name.equals(selection.get(i).name)) return i;
        }
        return -1;
    }
}
