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

    // ── 接收清單（接收方列表，取代「收到 N 個」橫幅）──────────────
    // 存於 VM → 跨 Activity 重建（如切換深淺色）存活。逐檔累積：名稱→該檔狀態。
    public static final class RecvFile {
        public final String name;
        public final long size;
        public final byte type;
        public int percent = -1;
        public boolean done;
        public boolean highlight;   // 當前正在接收的那一檔
        @androidx.annotation.Nullable public String uri;   // 存檔位置（收完才有，供點開）
        @androidx.annotation.Nullable public String mime;
        RecvFile(String name, long size, byte type) { this.name = name; this.size = size; this.type = type; }
    }
    private final java.util.LinkedHashMap<String, RecvFile> received = new java.util.LinkedHashMap<>();
    private long receiveListBatchId = 0;

    public java.util.Collection<RecvFile> receivedFiles() { return received.values(); }
    public boolean hasReceivedList() { return !received.isEmpty(); }

    /**
     * 接收到某檔的進度/完成；遇到新批次先清空。高亮設於當前傳輸中的那一檔。
     * @return true 表示此檔為新加入（以前不在清單中）
     */
    public boolean upsertReceived(long batchId, @NonNull String name, long size, byte type,
                                  int percent, boolean done) {
        if (batchId != receiveListBatchId) { received.clear(); receiveListBatchId = batchId; }
        RecvFile f = received.get(name);
        boolean isNew = (f == null);
        if (isNew) { f = new RecvFile(name, size, type); received.put(name, f); }
        f.percent = done ? 100 : percent;
        f.done = done;
        for (RecvFile o : received.values()) o.highlight = false;
        if (!done) f.highlight = true;
        return isNew;
    }

    /** 收完某檔 → 補上存檔 Uri/MIME，使該列可點開。 */
    public void setReceivedUri(@NonNull String name, @androidx.annotation.Nullable String uri,
                               @androidx.annotation.Nullable String mime) {
        RecvFile f = received.get(name);
        if (f != null) { f.uri = uri; f.mime = mime; }
    }

    public void clearReceivedList() { received.clear(); receiveListBatchId = 0; }

    /** 把接收列表收合為橫幅：統計已完成件數，餵入 receivedCountLd 讓 banner 顯示。 */
    public void collapseReceiveListToBanner() {
        int done = 0;
        for (RecvFile f : received.values()) {
            if (f.done) done++;
        }
        recvBatchId = receiveListBatchId;
        recvCount = done;
        receivedCountLd.setValue(recvCount);
    }

    // ── 旗標 ────────────────────────────────────────────────────
    // #1：未連線時按傳送名片 → 排隊，連上後自動送出
    private boolean pendingCardSend = false;
    // 本端是否正在送出待傳清單（送出鈕只在此期間隱藏；接收對方檔案時不影響送出鈕）
    private boolean sending = false;

    // ── 連線階段 ────────────────────────────────────────────────
    private SessionState.Phase lastPhase = SessionState.Phase.IDLE;

    // ── 完成動畫一次性消費（#2）──────────────────────────────────
    // 傳輸完成是「事件」不是「持久狀態」，但 sessionLd 會黏在 ALL_DONE 並在每次重綁/重建
    // （如切換深淺色重建 Activity）補送給新訂閱者。以 batchId 記住「已慶祝過的批次」，
    // 同批只播一次打勾/彩帶；重建補送的同批 ALL_DONE 不重播。ViewModel 跨重建存活，旗標也隨之存活。
    private long celebratedBatchId = Long.MIN_VALUE;

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

    /**
     * 完成動畫一次性閘門：對某批次回 {@code true} 僅一次（首次真實送達的 ALL_DONE）。
     * 重綁/重建補送同批 ALL_DONE 時回 {@code false}，呼叫端據此略過打勾/彩帶、只呈現已連線穩態。
     * 每批完成事件只會送達一次（單檔走 ALL_DONE、多檔中間檔走 FILE_DONE 不到此），故以 batchId 為鍵安全。
     */
    public boolean shouldCelebrate(long batchId) {
        if (batchId == celebratedBatchId) return false;
        celebratedBatchId = batchId;
        return true;
    }

    /** 回到就緒/閒置時清掉「已慶祝」記號，讓下一輪連線的新批次能正常慶祝（即使 batchId 罕見重用）。 */
    public void resetCelebration() {
        celebratedBatchId = Long.MIN_VALUE;
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
