package com.kisslink.ui.transfer;

import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;
import com.kisslink.R;
import com.kisslink.model.BusinessCard;
import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.NFCManager;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;
import com.kisslink.ui.card.CardDisplayActivity;

import java.util.ArrayList;

/**
 * 傳輸流程 BottomSheet（取代 PairingActivity + TransferActivity 的主要使用情境）。
 *
 * <p>從底部彈出，佔螢幕 50%，支援下拖關閉。
 * 內部有三個面板用 View visibility 切換：
 * <ol>
 *   <li>配對中（pairingPanel）— 初始</li>
 *   <li>傳輸中（transferPanel）— TRANSFERRING / FILE_DONE / CONNECTED</li>
 *   <li>完成（donePanel）— ALL_DONE</li>
 * </ol>
 *
 * 使用 {@link #newInstance(String, ArrayList)} 建立實例。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class TransferBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_ROLE = "role";
    private static final String ARG_URIS = "uris";

    // ── State ─────────────────────────────────────────────────
    private String role;
    @Nullable private ArrayList<Uri> uris;

    private FileTransferService.TransferBinder binder;
    private boolean bound           = false;
    private boolean filesEnqueued   = false;
    @Nullable private GroupCredential pendingCredential;

    private NFCManager nfcManager;

    // ── Views ─────────────────────────────────────────────────
    private View        rootView;
    // Pairing panel
    private LinearLayout pairingPanel;
    private TextView    tvSheetRole;
    private TextView    tvSheetStatus;
    private TextView    tvSheetHint;
    private View        ring1, ring2, ring3, ring4;
    // Transfer panel
    private LinearLayout transferPanel;
    private TextView    tvSheetPhase;
    private TextView    tvSheetFileName;
    private TextView    tvSheetSpeed;
    private ProgressBar progressBarSheet;
    private TextView    tvSheetPercent;
    // Done panel
    private LinearLayout donePanel;

    // ── NFC ring animators ────────────────────────────────────
    private ObjectAnimator anim1, anim2, anim3, anim4;

    // ══════════════════════════════════════════════════════════
    //  Factory
    // ══════════════════════════════════════════════════════════

    /**
     * @param role {@link FileTransferService#ROLE_SENDER} 或 {@link FileTransferService#ROLE_RECEIVER}
     * @param uris SENDER 時的待傳檔案列表；RECEIVER 傳 null
     */
    public static TransferBottomSheet newInstance(String role, @Nullable ArrayList<Uri> uris) {
        Bundle args = new Bundle();
        args.putString(ARG_ROLE, role);
        if (uris != null) args.putParcelableArrayList(ARG_URIS, uris);
        TransferBottomSheet f = new TransferBottomSheet();
        f.setArguments(args);
        return f;
    }

    // ══════════════════════════════════════════════════════════
    //  ServiceConnection
    // ══════════════════════════════════════════════════════════

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!isAdded()) return;
            binder = (FileTransferService.TransferBinder) service;
            bound  = true;

            // 觀察單一 session 狀態
            binder.getSessionState().observe(getViewLifecycleOwner(),
                    TransferBottomSheet.this::onSession);

            // RECEIVER：補送 NFC 先到的憑證
            if (FileTransferService.ROLE_RECEIVER.equals(role) && pendingCredential != null) {
                binder.submitReceiverCredential(pendingCredential);
                pendingCredential = null;
            }

            // SENDER：交付待傳檔案（防重複）
            if (FileTransferService.ROLE_SENDER.equals(role) && !filesEnqueued && uris != null) {
                binder.enqueueFiles(uris);
                filesEnqueued = true;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound  = false;
            binder = null;
        }
    };

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            role = args.getString(ARG_ROLE, FileTransferService.ROLE_SENDER);
            uris = args.getParcelableArrayList(ARG_URIS);
        } else {
            role = FileTransferService.ROLE_SENDER;
        }

        nfcManager = new NFCManager(requireActivity());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.bottom_sheet_transfer, container, false);
        bindViews();
        setupUi();
        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();

        // ── BottomSheet 行為設定 ──────────────────────────────
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog != null) {
            BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setHalfExpandedRatio(0.5f);
            behavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
            behavior.setDraggable(true);
            behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View v, int state) {
                    if (state == BottomSheetBehavior.STATE_HIDDEN) dismiss();
                }
                @Override
                public void onSlide(@NonNull View v, float offset) {}
            });
        }

        // ── 啟動 Service ──────────────────────────────────────
        Context ctx = requireActivity();
        Intent svcIntent = FileTransferService.ROLE_SENDER.equals(role)
                ? FileTransferService.senderIntent(ctx)
                : FileTransferService.receiverIntent(ctx);
        ctx.startForegroundService(svcIntent);
        ctx.bindService(svcIntent, connection, Context.BIND_AUTO_CREATE);

        // ── NFC 圓圈動畫 ──────────────────────────────────────
        startRingAnimations();
    }

    @Override
    public void onResume() {
        super.onResume();
        // RECEIVER：啟用 NFC Reader 等待碰觸（同時處理名片和 Wi-Fi 憑證）
        if (FileTransferService.ROLE_RECEIVER.equals(role)) {
            nfcManager.enableReaderMode(
                    requireActivity(),
                    cred -> requireActivity().runOnUiThread(() -> onNfcCredential(cred)),
                    card -> requireActivity().runOnUiThread(() -> onNfcCard(card)),
                    err  -> requireActivity().runOnUiThread(() -> showError(err)));
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (FileTransferService.ROLE_RECEIVER.equals(role)) {
            nfcManager.disableReaderMode(requireActivity());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        stopRingAnimations();

        if (bound) {
            requireActivity().unbindService(connection);
            bound  = false;
            binder = null;
        }
        // 只有在 Fragment 真正被移除（非暫時隱藏）時才停止 Service
        if (isRemoving()) {
            requireActivity().stopService(
                    new Intent(requireActivity(), FileTransferService.class));
        }
    }

    // ══════════════════════════════════════════════════════════
    //  NFC 憑證回呼
    // ══════════════════════════════════════════════════════════

    private void onNfcCredential(GroupCredential cred) {
        if (binder != null) {
            binder.submitReceiverCredential(cred);
        } else {
            pendingCredential = cred;
        }
    }

    private void onNfcCard(BusinessCard card) {
        // 收到名片：停止 Service、啟動 CardDisplayActivity（帶動畫），關閉 sheet
        if (isAdded()) {
            requireActivity().stopService(
                    new Intent(requireActivity(), FileTransferService.class));
            requireActivity().startActivity(CardDisplayActivity.newIntent(requireActivity(), card));
        }
        dismiss();
    }

    // ══════════════════════════════════════════════════════════
    //  Session 狀態觀察
    // ══════════════════════════════════════════════════════════

    private void onSession(SessionState st) {
        if (!isAdded()) return;

        // 更新配對面板狀態文字
        updatePairingStatus(st);

        if (st.isError() && st.error != null) {
            showError(st.error);
            return;
        }

        switch (st.phase) {
            case CONNECTED:
            case TRANSFERRING:
            case FILE_DONE:
                showTransferPanel(st);
                break;

            case ALL_DONE:
                showDonePanel();
                break;

            default:
                // IDLE / CREATING_GROUP / HOSTING / CONNECTING → 配對面板
                showPairingPanel();
                break;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  面板切換
    // ══════════════════════════════════════════════════════════

    private void showPairingPanel() {
        pairingPanel.setVisibility(View.VISIBLE);
        transferPanel.setVisibility(View.GONE);
        donePanel.setVisibility(View.GONE);
    }

    private void showTransferPanel(SessionState st) {
        pairingPanel.setVisibility(View.GONE);
        transferPanel.setVisibility(View.VISIBLE);
        donePanel.setVisibility(View.GONE);

        TransferProgress p = st.progress;
        switch (st.phase) {
            case CONNECTED:
                tvSheetPhase.setText(FileTransferService.ROLE_SENDER.equals(role)
                        ? "連線成功，傳送中…" : "已連線，等待傳送方…");
                progressBarSheet.setProgress(0);
                tvSheetSpeed.setText("");
                tvSheetPercent.setText("");
                tvSheetFileName.setText("");
                break;

            case TRANSFERRING:
                if (p != null) {
                    tvSheetPhase.setText("傳輸中 (" + (p.fileIndex + 1) + "/" + p.fileCount + ")");
                    tvSheetFileName.setText(p.fileName);
                    tvSheetSpeed.setText(p.speedLabel());
                    int pct = p.percentInt();
                    progressBarSheet.setProgress(pct >= 0 ? pct : 0);
                    tvSheetPercent.setText(pct >= 0 ? pct + "%" : "—");
                }
                break;

            case FILE_DONE:
                if (p != null) {
                    tvSheetPhase.setText("「" + p.fileName + "」完成");
                    progressBarSheet.setProgress(100);
                    tvSheetSpeed.setText("");
                    tvSheetPercent.setText("100%");
                }
                break;

            default:
                break;
        }
    }

    private void showDonePanel() {
        pairingPanel.setVisibility(View.GONE);
        transferPanel.setVisibility(View.GONE);
        donePanel.setVisibility(View.VISIBLE);
    }

    // ══════════════════════════════════════════════════════════
    //  配對面板狀態更新
    // ══════════════════════════════════════════════════════════

    private void updatePairingStatus(SessionState st) {
        switch (st.phase) {
            case CREATING_GROUP: tvSheetStatus.setText("建立 Wi-Fi Direct 群組…"); break;
            case HOSTING:        tvSheetStatus.setText("等待對方碰觸 NFC…");       break;
            case CONNECTING:     tvSheetStatus.setText("靜默連線中，請稍候…");      break;
            default:             tvSheetStatus.setText("準備中…");                  break;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  NFC 圓圈動畫
    // ══════════════════════════════════════════════════════════

    private void startRingAnimations() {
        anim1 = buildRingAnim(ring1, 0);
        anim2 = buildRingAnim(ring2, 400);
        anim3 = buildRingAnim(ring3, 800);
        anim4 = buildRingAnim(ring4, 1200);
        anim1.start();
        anim2.start();
        anim3.start();
        anim4.start();
    }

    private ObjectAnimator buildRingAnim(View target, long startDelay) {
        ObjectAnimator a = ObjectAnimator.ofFloat(target, "alpha", 0.8f, 0.2f);
        a.setDuration(2000);
        a.setStartDelay(startDelay);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        return a;
    }

    private void stopRingAnimations() {
        if (anim1 != null) anim1.cancel();
        if (anim2 != null) anim2.cancel();
        if (anim3 != null) anim3.cancel();
        if (anim4 != null) anim4.cancel();
    }

    // ══════════════════════════════════════════════════════════
    //  UI 初始化
    // ══════════════════════════════════════════════════════════

    private void bindViews() {
        // Pairing panel
        pairingPanel    = rootView.findViewById(R.id.pairingPanel);
        tvSheetRole     = rootView.findViewById(R.id.tvSheetRole);
        tvSheetStatus   = rootView.findViewById(R.id.tvSheetStatus);
        tvSheetHint     = rootView.findViewById(R.id.tvSheetHint);
        ring1           = rootView.findViewById(R.id.ring1);
        ring2           = rootView.findViewById(R.id.ring2);
        ring3           = rootView.findViewById(R.id.ring3);
        ring4           = rootView.findViewById(R.id.ring4);
        // Transfer panel
        transferPanel   = rootView.findViewById(R.id.transferPanel);
        tvSheetPhase    = rootView.findViewById(R.id.tvSheetPhase);
        tvSheetFileName = rootView.findViewById(R.id.tvSheetFileName);
        tvSheetSpeed    = rootView.findViewById(R.id.tvSheetSpeed);
        progressBarSheet= rootView.findViewById(R.id.progressBarSheet);
        tvSheetPercent  = rootView.findViewById(R.id.tvSheetPercent);
        // Done panel
        donePanel       = rootView.findViewById(R.id.donePanel);
    }

    private void setupUi() {
        // 關閉按鈕
        ImageButton btnClose = rootView.findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> dismissWithCancel());

        // 完成確定按鈕
        rootView.findViewById(R.id.btnDone).setOnClickListener(v -> dismiss());

        // 角色文字 & 提示文字
        if (FileTransferService.ROLE_SENDER.equals(role)) {
            tvSheetRole.setText("傳送方");
            tvSheetHint.setText("等待對方靠近並碰觸");
        } else {
            tvSheetRole.setText("接收方");
            tvSheetHint.setText("輕碰兩台手機 NFC 感應區");
        }

        // 初始顯示配對面板
        showPairingPanel();
    }

    // ══════════════════════════════════════════════════════════
    //  關閉邏輯
    // ══════════════════════════════════════════════════════════

    /** 按 X 關閉：先取消傳輸再 dismiss。 */
    private void dismissWithCancel() {
        if (binder != null) binder.cancel();
        if (isAdded()) {
            requireActivity().stopService(
                    new Intent(requireActivity(), FileTransferService.class));
        }
        dismiss();
    }

    // ══════════════════════════════════════════════════════════
    //  錯誤提示
    // ══════════════════════════════════════════════════════════

    private void showError(String msg) {
        if (rootView != null && isAdded()) {
            Snackbar.make(rootView, msg, Snackbar.LENGTH_LONG).show();
        }
    }
}
