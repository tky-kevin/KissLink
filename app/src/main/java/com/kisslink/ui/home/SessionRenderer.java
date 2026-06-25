package com.kisslink.ui.home;

import android.os.Handler;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.kisslink.R;
import com.kisslink.profile.ProfileStore;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;
import com.kisslink.transfer.TransferProtocol;

/**
 * 主畫面的渲染層：把單一 {@link SessionState} 對應成畫面（beam 階段、標題、速度、傳輸/完成版面、
 * 接收橫幅），以及就緒/閒置畫面。C3 最後一刀——原本這整層散在 {@link HomeActivity} 的 onSession
 * 大 switch 與十餘個 render helper 都收斂於此，{@code beam} 也完全封裝在內。
 *
 * <p>單向資料流：事件（SessionState / 選取變動）→ 渲染。少數需要回到 Activity 的「動作」
 * （NFC 解鎖、送出、送名片、取得對方身份）透過 {@link Host} 介面外送，渲染層本身不持有 binder/NFC。
 * 狀態真相仍在 {@link HomeViewModel}；本類別與兩個子 presenter（清單、待傳疊圖）協作。
 */
final class SessionRenderer {

    /** 渲染層需要 Activity 代為執行的少數動作（持有 binder / NFC 的那一側）。 */
    interface Host {
        @Nullable String peerName();
        @Nullable byte[] peerAvatar();
        void resetLatchedNfc();
        void requestSend();              // 連上後自動送出待傳清單
        void requestSendProfileCard();   // 連上後送出排隊的名片
    }

    private final FragmentActivity ctx;
    private final Handler main;
    private final BeamStageView beam;
    private final TransferUiController ui;
    private final TransferListPresenter transferList;
    private final SendStackPresenter sendStack;
    private final HomeViewModel vm;
    private final View pickRow;
    private final LinearLayout receivedBanner;
    private final TextView tvReceived;
    private final Host host;

    SessionRenderer(@NonNull FragmentActivity ctx, @NonNull Handler main, @NonNull BeamStageView beam,
                    @NonNull TransferUiController ui, @NonNull TransferListPresenter transferList,
                    @NonNull SendStackPresenter sendStack, @NonNull HomeViewModel vm,
                    @NonNull View pickRow, @NonNull LinearLayout receivedBanner,
                    @NonNull TextView tvReceived, @NonNull Host host) {
        this.ctx = ctx;
        this.main = main;
        this.beam = beam;
        this.ui = ui;
        this.transferList = transferList;
        this.sendStack = sendStack;
        this.vm = vm;
        this.pickRow = pickRow;
        this.receivedBanner = receivedBanner;
        this.tvReceived = tvReceived;
        this.host = host;
    }

    // ══════════════════════════════════════════════════════════
    //  狀態 → UI
    // ══════════════════════════════════════════════════════════

    void render(@NonNull SessionState st) {
        SessionState.Phase p = st.phase;

        switch (p) {
            case IDLE:
            case CANCELLED:
                ui.stopStageTicker();
                resetSessionState();   // 真正回到閒置 → 清空 VM 持久狀態（重建走的是 renderReady，不清）
                renderReady();
                if (vm.lastPhase() != SessionState.Phase.IDLE) host.resetLatchedNfc();
                break;

            case RESETTING:
                ui.stopStageTicker();
                beam.setPhase(BeamStageView.CONNECTING);
                ui.showLoadingHeadline(ctx.getString(R.string.home_resetting_title), "");
                break;

            case PAIRING_LATCHED:
            case PAIRING_LINKING:
            case PAIRING_ELECTING:
            case CONNECTING:
            case SOCKETING:
                beam.setPhase(BeamStageView.CONNECTING);
                ui.runStageTicker(TransferUiController.stageTargetFor(p));
                break;

            case CONNECTED:
                // 不立刻切「已連線」:讓階段 ticker 把最後的「建立 TCP 通道」走完並停留一拍再切,
                // 避免 socket 太快時 TCP 階段只閃一下。ticker 未在跑時會立即執行。
                ui.completeStagesThen(() -> {
                    beam.setPhase(BeamStageView.CONNECTED);
                    ui.showPeerIdentity(beam, host.peerName(), host.peerAvatar(),
                            ProfileStore.get(ctx).name(), ProfileStore.get(ctx).loadAvatar());
                    ui.showHeadlineText(ctx.getString(R.string.home_connected_title), connectedSub());
                    host.resetLatchedNfc();
                    exitTransferUi();
                    transferList.restoreIfAny();
                    vm.setSending(false);
                    sendStack.updateButton();
                    sendStack.rebuild();
                    if (vm.isPendingCardSend()) {
                        vm.setPendingCardSend(false);
                        main.post(host::requestSendProfileCard);
                    }
                    if (!vm.isSelectionEmpty()) {
                        main.post(host::requestSend);
                    }
                });
                break;

            case TRANSFERRING:
                ui.stopStageTicker();
                onTransferring(st.progress);
                break;

            case FILE_DONE:
            case ALL_DONE:
                onTransferDone(st.progress, p == SessionState.Phase.ALL_DONE);
                break;

            case ERROR:
                ui.stopStageTicker();
                beam.setPhase(BeamStageView.ERROR);
                ui.showHeadlineText(ctx.getString(R.string.home_error_title),
                        st.error != null ? st.error : ctx.getString(R.string.home_error_retry));
                host.resetLatchedNfc();
                break;

            default: break;
        }
        vm.onSession(st);
    }

    // ── READY ──
    // 純視覺化、可重入：onCreate（含切換深淺色/背景返回的 Activity 重建）也會呼叫，故「不」清空
    // ViewModel 的持久狀態（接收清單/慶祝旗標/送出中）——那屬於真正回到 IDLE 的語意，於 render()
    // 的 IDLE/CANCELLED 處理。否則重建時這裡會把待還原的接收清單清掉，replay 只補回最後一檔 → 剩 1 項。
    void renderReady() {
        beam.setPhase(BeamStageView.READY);
        ui.showPeerIdentity(beam, null, null,
                ProfileStore.get(ctx).name(), ProfileStore.get(ctx).loadAvatar());
        ui.showHeadlineText(ctx.getString(R.string.home_ready_title),
                ctx.getString(R.string.home_ready_sub));
        // 收合傳輸中列表方塊、顯示挑檔列（接收清單若仍有資料，連線 replay 後由 restoreIfAny 還原）。
        transferList.reset();
        pickRow.setVisibility(View.VISIBLE);
        hideReceivedBanner();
        sendStack.updateButton();
        sendStack.rebuild();
    }

    // ── 接收橫幅（接收清單的收合形態）──────────────────────────────
    void showReceivedBanner(int count) {
        tvReceived.setText(ctx.getString(R.string.received_batch, count));
        Anim.revealFadeUp(receivedBanner);
    }

    void hideReceivedBanner() {
        receivedBanner.setVisibility(View.GONE);
    }

    // ── beam 的其他入口（供 Activity 在非 SessionState 路徑呼叫）────
    void haptic() {
        beam.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    void updateSelfIdentity(@Nullable String name, @Nullable android.graphics.Bitmap avatar) {
        beam.setSelfIdentity(name);
        beam.setSelfAvatar(avatar);
    }

    void playCardFly() {
        beam.playCardFly();   // 名片縮入對方頭像的 genie 動畫
    }

    // ══════════════════════════════════════════════════════════
    //  內部
    // ══════════════════════════════════════════════════════════

    /** 真正回到閒置：清空接收清單/慶祝旗標/送出中等 ViewModel 持久狀態（與單純的畫面重建區隔）。 */
    private void resetSessionState() {
        vm.clearReceivedList();   // 內含 resetReceived（橫幅計數歸零）
        vm.resetCelebration();
        vm.setSending(false);
    }

    /** 已連線副標：「已連線至 ◯◯」；無對方名稱時回退「對方」。 */
    private String connectedSub() {
        String peer = host.peerName();
        return ctx.getString(R.string.home_connected_sub,
                peer != null ? peer : ctx.getString(R.string.peer_fallback));
    }

    // ── 傳輸中 ──
    // 畫面只留兩件事：速度（大字英雄）＋ 一個檔案列表方塊（沿用待傳列設計、無標題、當前列高亮）。
    // 進度收斂到中央光環、不顯示百分比文字；不顯示「傳送中 · 檔名」小灰字。
    private void onTransferring(@Nullable TransferProgress tp) {
        if (tp == null) return;
        boolean outgoing = tp.outgoing;
        beam.setDirection(outgoing ? BeamStageView.SEND : BeamStageView.RECEIVE);
        beam.setPhase(BeamStageView.TRANSFERRING);
        beam.setProgress(vm.batchProgress(tp));   // #3：整包進度
        // 速度作為主角：大字 + tabular 數字（取代「已連線」與小灰字）
        ui.showSpeedHero(TransferUiController.speedNumber(tp.speedBps),
                TransferUiController.speedUnit(tp.speedBps));
        // 進入傳輸版面的第一幀重置自動捲動（之後每幀都會走到這，但只重置一次）。
        transferList.beginTransferFrame();
        enterTransferUi();
        if (outgoing) {
            // 送出方：把待傳清單展開成傳輸中的列表方塊（只建一次），逐幀只更新當前列進度/高亮。
            if (tp.itemType != TransferProtocol.ITEM_VCARD) {
                if (!transferList.isListVisible()) transferList.buildSendList();
                transferList.updateOutgoingProgress(tp, false);
            }
        } else {
            // 接收方：逐檔累積成接收列表（取代「收到 N 個」橫幅），樣式比照送出列表。
            transferList.showReceiveList(tp, false);
        }
    }

    private void onTransferDone(@Nullable TransferProgress tp, boolean all) {
        boolean outgoing = tp != null && tp.outgoing;
        boolean isVcard = tp != null && tp.itemType == TransferProtocol.ITEM_VCARD;

        // #3：多檔的「中間檔」（FILE_DONE，尚未到最後一筆）→ 維持傳輸視覺，只推進整包進度（雙向皆同）
        if (!isVcard && !all && tp != null && tp.fileCount > 1) {
            beam.setPhase(BeamStageView.TRANSFERRING);
            beam.setProgress(vm.batchProgress(tp));
            if (outgoing) transferList.updateOutgoingProgress(tp, true);
            else transferList.showReceiveList(tp, true);
            return;
        }

        // #2：完成是「一次性事件」。切換深淺色會重建 Activity，重綁時 sessionLd 會把黏著的
        // ALL_DONE 補送給新訂閱者；若每次都重跑 DONE，會重播打勾/彩帶。對同一批次只慶祝一次，
        // 補送的同批完成事件 → 不重播，直接呈現「已連線」穩態（與真實完成 1.4 秒後回到的畫面一致）。
        long batchId = tp != null ? tp.batchId : 0L;
        if (!vm.shouldCelebrate(batchId)) {
            if (vm.isConnected()) {
                beam.setPhase(BeamStageView.CONNECTED);
                beam.setProgress(1f);
                ui.hidePercent();
                ui.showPeerIdentity(beam, host.peerName(), host.peerAvatar(),
                        ProfileStore.get(ctx).name(), ProfileStore.get(ctx).loadAvatar());
                ui.showHeadlineText(ctx.getString(R.string.home_connected_title), connectedSub());
            }
            exitTransferUi();
            transferList.restoreIfAny();   // 重建後（如切換深淺色）把 VM 中的接收列表重新顯示
            sendStack.updateButton();
            sendStack.rebuild();
            return;
        }

        // 其餘 → 顯示完成（最後一筆 ALL_DONE / 名片 / 單檔）：列表方塊收合、打勾＋彩帶、速度換成完成文案
        beam.setPhase(BeamStageView.DONE);
        beam.setProgress(1f);
        ui.hidePercent();
        exitTransferUi();
        String peer = host.peerName();
        String who = peer != null ? peer : ctx.getString(R.string.peer_fallback);
        ui.showHeadlineText(ctx.getString(R.string.transfer_done),
                outgoing ? ctx.getString(R.string.sent_to, who) : ctx.getString(R.string.received_from, who));

        if (isVcard) {
            // 名片獨立傳送，不影響待傳清單
        } else if (outgoing) {
            // 整批傳完 → 清空待傳清單與送出狀態（會透過 selection LiveData 重建 UI）
            vm.onBatchSent();
            sendStack.rebuild();
            sendStack.dismissSheet();
        } else if (tp != null) {
            transferList.showReceiveList(tp, true);
            transferList.collapseIfSendPending(); // 有待傳項目 → 直接收合為橫幅，不讓整張清單擠壓 NFC
        }

        // 短暫顯示完成後回到「已連線」可再選
        main.postDelayed(() -> {
            SessionState.Phase last = vm.lastPhase();
            if ((last == SessionState.Phase.ALL_DONE || last == SessionState.Phase.FILE_DONE)
                    && vm.isConnected()) {
                beam.setPhase(BeamStageView.CONNECTED);
            }
        }, 1400);
        sendStack.updateButton();
    }

    /** 進入「傳輸中」版面：只留速度＋列表方塊，收起挑檔/送出/待傳與（已停用的）接收橫幅。 */
    private void enterTransferUi() {
        pickRow.setVisibility(View.GONE);
        sendStack.hideForTransfer();
        receivedBanner.setVisibility(View.GONE);
    }

    /**
     * 離開「傳輸中」版面（回到已連線/完成/就緒）：還原挑檔列與待傳/送出。
     * 送出列表收合；接收列表為持久顯示（收完仍保留，供檢視/點開）→ 不收合。
     */
    private void exitTransferUi() {
        transferList.exitTransfer();   // 送出列表收合；接收列表持久 → 不收合（由 presenter 判斷）
        pickRow.setVisibility(View.VISIBLE);
        sendStack.rebuild();
        sendStack.updateButton();
    }
}
