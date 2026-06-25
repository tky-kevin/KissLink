package com.kisslink.ui.home;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.TransferProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部「待傳」區的單一擁有者：疊起來的摘要縮圖、送出鈕、與點開的完整可移除清單（sheet）。
 *
 * <p>與 {@link TransferListPresenter} 對稱——把原本散在 {@link HomeActivity} 的待傳渲染
 * （rebuildSendStack / buildStackThumbs / updateSendButton / showSendSheet）連同其 adapter 與
 * sheet 管理器收斂於此。資料真相仍在 {@link HomeViewModel}（選取清單）；本類別只負責呈現，
 * 並把使用者意圖（點送出鈕、點某列）以 callback 往外轉發。
 */
final class SendStackPresenter {

    private final FragmentActivity activity;
    private final View sendStackRow;
    private final FrameLayout stackThumbs;
    private final TextView tvStackLabel;
    private final MaterialButton btnSend;
    private final HomeViewModel vm;
    // 彈出清單用的 adapter（與傳輸中列表方塊的 adapter 分離，避免縮圖執行緒池共用衝突）。
    private final SendListAdapter itemsAdapter = new SendListAdapter();
    private final SendSheetManager sheet = new SendSheetManager();

    SendStackPresenter(@NonNull FragmentActivity activity, @NonNull View sendStackRow,
                       @NonNull FrameLayout stackThumbs, @NonNull TextView tvStackLabel,
                       @NonNull MaterialButton btnSend, @NonNull HomeViewModel vm,
                       @NonNull SendListAdapter.OnItemClickListener onItemClick,
                       @NonNull Runnable onSendClick) {
        this.activity = activity;
        this.sendStackRow = sendStackRow;
        this.stackThumbs = stackThumbs;
        this.tvStackLabel = tvStackLabel;
        this.btnSend = btnSend;
        this.vm = vm;
        itemsAdapter.setOnRemove(vm::removeSelection);
        itemsAdapter.setOnItemClickListener(onItemClick);
        sendStackRow.setOnClickListener(v -> showSheet());
        btnSend.setOnClickListener(v -> onSendClick.run());
    }

    /** 待傳清單變動 → 重建 adapter（供 sheet）與底部疊起來的摘要。 */
    void rebuild() {
        List<SendRow> rows = new ArrayList<>();
        for (SendItem it : vm.currentSelection()) {
            SendRow r = new SendRow(it.name, TransferUiController.sizeLabel(it.size), it.itemType,
                    it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null,
                    it.uri, it.mime);
            r.removable = true;
            rows.add(r);
        }
        itemsAdapter.submit(rows);

        if (vm.isSelectionEmpty()) {
            sendStackRow.setVisibility(View.GONE);
            dismissSheet();
            return;
        }
        tvStackLabel.setText(activity.getString(R.string.send_stack_label, vm.selectionCount()));
        buildStackThumbs();
        // 與「已接收方塊」一致：變為可見時才播淡入上滑（統一入場動畫）。
        Anim.revealFadeUp(sendStackRow);
    }

    /**
     * 送出鈕：只要「已連線且待傳清單有東西」就顯示；僅在本端正在送出時隱藏。
     * 不用 lastPhase 判斷——否則「接收對方一包檔」期間/結束時會誤把送出鈕藏掉。
     */
    void updateButton() {
        if (vm.shouldShowSendButton()) {
            btnSend.setText(activity.getString(R.string.btn_send_n, vm.selectionCount()));
            Anim.revealFadeUp(btnSend);
        } else {
            btnSend.setVisibility(View.GONE);
        }
    }

    /** 進入傳輸版面：收起摘要與送出鈕（讓畫面只留速度＋列表方塊）。 */
    void hideForTransfer() {
        btnSend.setVisibility(View.GONE);
        sendStackRow.setVisibility(View.GONE);
    }

    void dismissSheet() {
        if (sheet.isShowing()) sheet.dismiss();
    }

    /** 點擊摘要 → 彈出完整可移除清單。 */
    private void showSheet() {
        if (vm.isSelectionEmpty()) return;
        sheet.showIfNotEmpty(activity, itemsAdapter, vm::clearSelection);
    }

    /** 疊起來的縮圖（最多 3 個，往右錯開重疊）。 */
    private void buildStackThumbs() {
        stackThumbs.removeAllViews();
        List<SendItem> selection = vm.currentSelection();
        float d = activity.getResources().getDisplayMetrics().density;
        int sizePx = Math.round(40 * d);
        int stepPx = Math.round(22 * d);
        int pad = Math.round(9 * d);
        int max = Math.min(3, selection.size());
        for (int i = 0; i < max; i++) {
            SendItem it = selection.get(i);
            ShapeableImageView iv = new ShapeableImageView(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx);
            lp.leftMargin = i * stepPx;
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(pad, pad, pad, pad);
            iv.setImageResource(TransferUiController.iconForItem(it));
            stackThumbs.addView(iv);
        }
    }
}
