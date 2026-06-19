package com.kisslink.ui.home;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home 畫面的狀態持有者（MVVM 的 VM）：把原本散在 {@code HomeActivity} 的選取／傳輸／接收
 * 狀態與其衍生判斷集中於此，與 Android View 解耦以利單元測試。
 *
 * <p>本類別「不」持有任何 View / Context，也不直接觸碰 Service binding——綁定生命週期仍由
 * Activity 管理；Activity 把來自 binder 的 {@link SessionState} 透過 {@link #onSession} 餵入，
 * VM 更新內部狀態並以 LiveData / 衍生方法對外揭露，由 Activity 負責渲染。
 */
public class HomeViewModel extends ViewModel {

    // ── 選取（待傳清單）──────────────────────────────────────────
    private final List<SendItem> selection = new ArrayList<>();
    private final MutableLiveData<List<SendItem>> selectionLd = new MutableLiveData<>(selection);

    // ── 送出批次（目前僅維持狀態旗標，與舊版行為一致）──────────────
    private final Set<String> outgoingNames = new HashSet<>();
    private int outgoingRemaining = 0; // 本批還沒傳完的件數，歸零即清空待傳清單

    // ── 接收橫幅 ────────────────────────────────────────────────
    private long recvBatchId = 0; // 目前接收批次
    private int recvCount = 0; // 目前接收批次已完成件數
    private final MutableLiveData<Integer> receivedCountLd = new MutableLiveData<>(0);

    // ── 旗標 ────────────────────────────────────────────────────
    // #1：未連線時按傳送名片 → 排隊，連上後自動送出
    private boolean pendingCardSend = false;
    // 本端是否正在送出待傳清單（送出鈕只在此期間隱藏；接收對方檔案時不影響送出鈕）
    private boolean sending = false;

    // ── 連線階段 ────────────────────────────────────────────────
    private SessionState.Phase lastPhase = SessionState.Phase.IDLE;

    // ── 進度單調化狀態 ──────────────────────────────────────────
    private long progBatchId = Long.MIN_VALUE;
    private String progFile = "";
    private float progShown = 0f;

    // ══════════════════════════════════════════════════════════
    //  選取（待傳清單）
    // ══════════════════════════════════════════════════════════

    /** 待傳清單變動時對外通知（內容為現用清單實例，僅供讀取）。 */
    public LiveData<List<SendItem>> getSelection() {
        return selectionLd;
    }

    /** 現用待傳清單（僅供讀取/迭代；請勿外部結構性修改）。 */
    @NonNull
    public List<SendItem> currentSelection() {
        return selection;
    }

    public int selectionCount() {
        return selection.size();
    }

    public boolean isSelectionEmpty() {
        return selection.isEmpty();
    }

    public void addToSelection(@NonNull SendItem item) {
        selection.add(item);
        notifySelectionChanged();
    }

    public void addAllToSelection(@NonNull List<SendItem> items) {
        if (items.isEmpty()) return;
        selection.addAll(items);
        notifySelectionChanged();
    }

    public void removeSelection(int position) {
        if (position < 0 || position >= selection.size()) return;
        selection.remove(position);
        notifySelectionChanged();
    }

    public void clearSelection() {
        if (selection.isEmpty()) return;
        selection.clear();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        selectionLd.setValue(selection);
    }

    // ══════════════════════════════════════════════════════════
    //  送出批次
    // ══════════════════════════════════════════════════════════

    /** doSend 起手：記錄本批檔名與件數，標記本端正在送出。 */
    public void markSendingStarted() {
        outgoingNames.clear();
        for (SendItem it : selection) outgoingNames.add(it.name);
        outgoingRemaining = selection.size();
        sending = true;
    }

    /** 整批送完：清空待傳清單與送出狀態（會觸發 {@link #getSelection()} 通知）。 */
    public void onBatchSent() {
        outgoingNames.clear();
        outgoingRemaining = 0;
        sending = false;
        clearSelection();
    }

    public boolean isSending() {
        return sending;
    }

    public void setSending(boolean sending) {
        this.sending = sending;
    }

    // ══════════════════════════════════════════════════════════
    //  名片排隊旗標（#1）
    // ══════════════════════════════════════════════════════════

    public boolean isPendingCardSend() {
        return pendingCardSend;
    }

    public void setPendingCardSend(boolean pending) {
        this.pendingCardSend = pending;
    }

    // ══════════════════════════════════════════════════════════
    //  連線階段 / 衍生判斷
    // ══════════════════════════════════════════════════════════

    public SessionState.Phase lastPhase() {
        return lastPhase;
    }

    /** 由 Activity 在處理完一次 {@link SessionState} 後呼叫，記錄最後階段。 */
    public void onSession(@NonNull SessionState st) {
        this.lastPhase = st.phase;
    }

    /** 已連線（含傳輸中／單檔完成／全部完成）→ 可送出。 */
    public boolean isConnected() {
        return lastPhase == SessionState.Phase.CONNECTED
                || lastPhase == SessionState.Phase.TRANSFERRING
                || lastPhase == SessionState.Phase.FILE_DONE
                || lastPhase == SessionState.Phase.ALL_DONE;
    }

    /**
     * 送出鈕：只要「已連線且待傳清單有東西」就顯示；僅在本端正在送出時隱藏。
     * 不用 lastPhase 判斷——否則「接收對方一包檔」期間/結束會誤把送出鈕藏掉。
     */
    public boolean shouldShowSendButton() {
        return isConnected() && !selection.isEmpty() && !sending;
    }

    // ══════════════════════════════════════════════════════════
    //  接收橫幅
    // ══════════════════════════════════════════════════════════

    /** 接收件數；0 表示橫幅應隱藏。 */
    public LiveData<Integer> getReceivedCount() {
        return receivedCountLd;
    }

    public long receivedBatchId() {
        return recvBatchId;
    }

    /** 回到就緒畫面：重置接收批次計數並隱藏橫幅。 */
    public void resetReceived() {
        recvBatchId = 0;
        recvCount = 0;
        receivedCountLd.setValue(0);
    }

    /** 接收到新批次的「傳輸中」事件 → 重置計數並隱藏橫幅。 */
    public void onIncomingBatch(long batchId) {
        if (batchId != recvBatchId) {
            recvBatchId = batchId;
            recvCount = 0;
            receivedCountLd.setValue(0);
        }
    }

    /** 接收方：累計本批收到的項目數並更新橫幅。 */
    public void countReceived(@NonNull TransferProgress tp) {
        if (tp.batchId != recvBatchId) {
            recvBatchId = tp.batchId;
            recvCount = 0;
        }
        recvCount++;
        receivedCountLd.setValue(recvCount);
    }

    // ══════════════════════════════════════════════════════════
    //  進度
    // ══════════════════════════════════════════════════════════

    /**
     * 進度 0..1，同段內單調不回退。
     * 傳送多檔（fileCount&gt;1）→ (已完成檔數 + 當前檔比例)/總檔數，整包進度；
     * 接收或單檔（fileCount≤1）→ 當前檔比例（換檔時重置）。
     */
    public float batchProgress(@NonNull TransferProgress tp) {
        boolean wholeBatch = tp.fileCount > 1;
        boolean newBatch = tp.batchId != progBatchId;
        boolean newFile = !tp.fileName.equals(progFile);
        if (newBatch || (!wholeBatch && newFile)) {
            progBatchId = tp.batchId;
            progShown = 0f;
        }
        progFile = tp.fileName;
        int pct = tp.percentInt();
        float frac = pct >= 0 ? pct / 100f : 0f;
        int count = Math.max(1, tp.fileCount);
        float raw = Math.min(1f, (tp.fileIndex + frac) / count);
        if (raw > progShown) progShown = raw;
        return progShown;
    }
}
