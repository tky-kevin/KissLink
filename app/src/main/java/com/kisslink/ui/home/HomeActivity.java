package com.kisslink.ui.home;

import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.pairing.LocalPairing;
import com.kisslink.profile.Profile;
import com.kisslink.profile.ProfileStore;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProtocol;
import com.kisslink.ui.history.HistorySheet;
import com.kisslink.ui.profile.ProfileCardSheet;
import com.kisslink.ui.profile.ReceivedCardSheet;
import com.kisslink.util.PermissionHelper;
import com.kisslink.util.ThemePrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 單頁主畫面（C 方案 Beam）——配對、連線、傳輸全在這一頁，靠 {@link SessionState} 切換內容。
 *
 * <p>NFC 配對委由 {@link HomeNfcDelegate}；選取/傳輸狀態由 {@link HomeViewModel} 管理；
 * 本 Activity 只負責生命週期、view binding、觀察 ViewModel 與轉發使用者意圖。
 */
@AndroidEntryPoint
public class HomeActivity extends AppCompatActivity
        implements ProfileCardSheet.Host, SessionRenderer.Host {

    private static final String TAG = "HomeActivity";

    // ── Views ──
    private MaterialButton btnPickFiles, btnPickMedia;
    private ImageButton ibHistory, ibSettings;
    private ShapeableImageView ivAvatar;

    private TransferListPresenter transferList;
    private SendStackPresenter sendStack;
    private SessionRenderer sessionRenderer;

    private HomeViewModel viewModel;

    // ── NFC 委派 ──
    private HomeNfcDelegate nfcDelegate;

    // ── Service ──
    @Nullable private FileTransferService.TransferBinder binder;
    private boolean bound = false;
    private final androidx.lifecycle.Observer<SessionState> sessionObserver = this::onSession;
    private final androidx.lifecycle.Observer<byte[]> incomingCardObserver = vcard -> {
        if (vcard == null || vcard.length == 0 || binder == null) return;
        ReceivedCardSheet.newInstance(vcard).show(getSupportFragmentManager(), "received_card");
        binder.clearIncomingCard();
    };
    private final androidx.lifecycle.Observer<FileTransferService.ReceivedItem> receivedItemObserver = item -> {
        if (item == null || transferList == null || !transferList.isReceiveListActive()) return;
        viewModel.setReceivedUri(item.name, item.contentUri, item.mime);
        Uri uri = item.contentUri != null ? Uri.parse(item.contentUri) : null;
        transferList.updateReceivedThumbnail(item.name, uri, item.mime);
    };

    // ── 主執行緒 Handler（延遲回復用）──
    private final Handler main = new Handler(Looper.getMainLooper());

    // ── 剪貼板快速分享 ──
    private View clipboardRow;
    private TextView tvClipboardPreview;
    private String lastOfferedClip = null;

    // ── 內容選擇器 ──
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                List<SendItem> picked = new ArrayList<>();
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    byte type = TransferProtocol.itemTypeForMime(getContentResolver().getType(uri));
                    picked.add(SendItem.fromUri(getContentResolver(), uri, type));
                }
                viewModel.addAllToSelection(picked);
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> mediaPicker =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(30), uris -> {
                if (uris == null || uris.isEmpty()) return;
                List<SendItem> picked = new ArrayList<>();
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    picked.add(SendItem.fromUri(getContentResolver(), uri, TransferProtocol.ITEM_PHOTO));
                }
                viewModel.addAllToSelection(picked);
            });

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemePrefs.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);
        bindViews();
        applyInsets();
        observeViewModel();

        nfcDelegate = new HomeNfcDelegate(this, nfcHost);

        LocalPairing.setDisplayName(ProfileStore.get(this).name());

        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
        }

        sessionRenderer.renderReady();
        ShareIntentReceiver.ingest(this, viewModel, getIntent());
    }

    private void bindViews() {
        BeamStageView beam = findViewById(R.id.beam);
        TextView tvHeadline = findViewById(R.id.tvHeadline);
        TextView tvSub      = findViewById(R.id.tvSub);
        TextView tvPercent  = findViewById(R.id.tvPercent);
        TextView tvPercentUnit = findViewById(R.id.tvPercentUnit);
        LinearLayout percentRow = findViewById(R.id.percentRow);
        View pickRow        = findViewById(R.id.pickRow);
        LinearLayout receivedBanner = findViewById(R.id.receivedBanner);
        TextView tvReceived = findViewById(R.id.tvReceived);
        btnPickFiles= findViewById(R.id.btnPickFiles);
        btnPickMedia= findViewById(R.id.btnPickMedia);
        ibHistory   = findViewById(R.id.ibHistory);
        ibSettings  = findViewById(R.id.ibSettings);
        ivAvatar    = findViewById(R.id.ivAvatar);

        TransferUiController ui = new TransferUiController(
                this, main, tvHeadline, tvSub, tvPercent, tvPercentUnit, percentRow);

        transferList = new TransferListPresenter(findViewById(R.id.rvTransfer), viewModel, this::openFile);
        sendStack = new SendStackPresenter(this, findViewById(R.id.sendStackRow),
                findViewById(R.id.stackThumbs), findViewById(R.id.tvStackLabel),
                findViewById(R.id.btnSend), viewModel, this::openFile, this::doSend);
        sessionRenderer = new SessionRenderer(this, main, beam, ui, transferList, sendStack,
                viewModel, pickRow, receivedBanner, tvReceived, this);

        tvHeadline.setOnClickListener(v -> onHeadlineTapped());

        receivedBanner.setOnClickListener(v -> {
            long batchId = viewModel.receivedBatchId();
            if (batchId != 0)
                HistorySheet.forBatch(batchId).show(getSupportFragmentManager(), "batch");
        });

        btnPickFiles.setOnClickListener(v -> {
            if (!ensurePerms()) return;
            filePicker.launch(new String[]{"*/*"});
        });
        btnPickMedia.setOnClickListener(v -> {
            if (!ensurePerms()) return;
            mediaPicker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                    .build());
        });

        ibHistory.setOnClickListener(v ->
                new HistorySheet().show(getSupportFragmentManager(), "history"));
        ibSettings.setOnClickListener(v ->
                SettingsSheet.newInstance().show(getSupportFragmentManager(), "settings"));
        ivAvatar.setOnClickListener(v ->
                ProfileCardSheet.newInstance().show(getSupportFragmentManager(), "profile"));

        // 剪貼板快速分享
        clipboardRow = findViewById(R.id.clipboardRow);
        tvClipboardPreview = findViewById(R.id.tvClipboardPreview);
        clipboardRow.setOnClickListener(v -> onClipboardShareTapped());
        ImageButton ibDismiss = findViewById(R.id.ibClipboardDismiss);
        ibDismiss.setOnClickListener(v -> {
            lastOfferedClip = tvClipboardPreview.getTag() instanceof String
                    ? (String) tvClipboardPreview.getTag() : null;
            clipboardRow.setVisibility(View.GONE);
        });

        refreshAvatar();
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
    }

    private void observeViewModel() {
        viewModel.getSelection().observe(this, items -> {
            transferList.collapseIfSendPending();
            sendStack.rebuild();
            sendStack.updateButton();
        });
        viewModel.getReceivedCount().observe(this, count -> {
            if (count != null && count > 0) sessionRenderer.showReceivedBanner(count);
            else sessionRenderer.hideReceivedBanner();
        });
        getSupportFragmentManager().setFragmentResultListener(
                HistorySheet.RESULT_BATCH_CLEARED, this, (key, bundle) -> viewModel.clearReceivedList());
    }

    @Override protected void onStart() {
        super.onStart();
        Intent svc = FileTransferService.intent(this);
        startForegroundService(svc);
        if (!bound) {
            bindService(svc, connection, BIND_AUTO_CREATE);
            bound = true;
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (bound) {
            try { unbindService(connection); } catch (IllegalArgumentException ignored) {}
            bound = false;
            binder = null;
            // binder 已失效：清掉 nfcDelegate 的 binderReady，否則下次 onResume 會在
            // bindService 非同步回呼前就以 stale 旗標走進 requireBinder() → 閃退。
            nfcDelegate.onBinderLost();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAvatar();
        LocalPairing.setDisplayName(ProfileStore.get(this).name());
        nfcDelegate.onResume();
        refreshClipboardChip();
    }

    @Override protected void onPause() {
        super.onPause();
        nfcDelegate.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        nfcDelegate.onNewIntent(intent);
        ShareIntentReceiver.ingest(this, viewModel, intent);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (bound) { unbindService(connection); bound = false; }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.allGranted(grantResults)) {
            toast(getString(R.string.need_perms));
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Service 綁定
    // ══════════════════════════════════════════════════════════

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (FileTransferService.TransferBinder) service;
            nfcDelegate.onBinderReady();
            androidx.lifecycle.LiveData<SessionState> state = binder.getSessionState();
            if (state != null) state.observe(HomeActivity.this, sessionObserver);
            binder.getIncomingCard().observe(HomeActivity.this, incomingCardObserver);
            binder.getReceivedItem().observe(HomeActivity.this, receivedItemObserver);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            nfcDelegate.onBinderLost();
            binder = null;
        }
    };

    // ══════════════════════════════════════════════════════════
    //  狀態 → UI
    // ══════════════════════════════════════════════════════════

    private void onSession(SessionState st) {
        sessionRenderer.render(st);
    }

    private void doSend() {
        if (binder == null || !viewModel.isConnected() || viewModel.isSelectionEmpty()) return;
        viewModel.markSendingStarted();
        binder.sendItems(new ArrayList<>(viewModel.currentSelection()));
        sendStack.updateButton();
        haptic();
    }

    private void onHeadlineTapped() {
        if (binder == null) return;
        if (viewModel.isConnected()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.disconnect_title)
                    .setMessage(R.string.disconnect_msg)
                    .setNegativeButton(R.string.btn_cancel, null)
                    .setPositiveButton(R.string.disconnect_confirm, (dd, w) -> {
                        if (binder != null) binder.disconnect();
                    })
                    .show();
        } else if (isInterruptiblePairing(viewModel.lastPhase())) {
            binder.interruptPairing();
        }
    }

    private static boolean isInterruptiblePairing(SessionState.Phase p) {
        switch (p) {
            case PAIRING_LATCHED:
            case PAIRING_LINKING:
            case PAIRING_ELECTING:
            case CONNECTING:
            case SOCKETING:
            case ERROR:
                return true;
            default:
                return false;
        }
    }

    private void openFile(@androidx.annotation.NonNull SendRow row) {
        if (row.itemType == TransferProtocol.ITEM_TEXT) {
            ReceivedTextDialog.show(this, row.name);
            return;
        }
        if (row.fileUri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(row.fileUri, row.mime != null ? row.mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "沒有可開啟此檔案的應用程式", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ── 剪貼板快速分享 ──────────────────────────────────────────

    private void refreshClipboardChip() {
        if (clipboardRow == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            clipboardRow.setVisibility(View.GONE);
            return;
        }
        android.content.ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            clipboardRow.setVisibility(View.GONE);
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            clipboardRow.setVisibility(View.GONE);
            return;
        }
        String content = text.toString().trim();
        if (content.length() < 3 || content.equals(lastOfferedClip)) {
            clipboardRow.setVisibility(View.GONE);
            return;
        }
        tvClipboardPreview.setText(getString(R.string.clipboard_share_prefix) + content);
        tvClipboardPreview.setTag(content);
        clipboardRow.setVisibility(View.VISIBLE);
    }

    private void onClipboardShareTapped() {
        if (tvClipboardPreview == null || !(tvClipboardPreview.getTag() instanceof String)) return;
        String content = (String) tvClipboardPreview.getTag();
        lastOfferedClip = content;
        clipboardRow.setVisibility(View.GONE);
        viewModel.addAllToSelection(Collections.singletonList(SendItem.text(content)));
        toast(getString(R.string.clipboard_added));
    }

    // ── 頭像 ──
    private static final int AVATAR_DISPLAY_PX = 256;

    private void refreshAvatar() {
        ivAvatar.setPadding(0, 0, 0, 0);
        ivAvatar.setImageBitmap(ProfileStore.get(this).loadAvatarForDisplay(this, AVATAR_DISPLAY_PX));
    }

    // ── 工具 ──
    private boolean ensurePerms() {
        if (PermissionHelper.hasPermissions(this)) return true;
        PermissionHelper.requestPermissions(this);
        return false;
    }

    private void haptic() {
        sessionRenderer.haptic();
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    public static Intent intent(Context ctx) {
        return new Intent(ctx, HomeActivity.class);
    }

    // ══════════════════════════════════════════════════════════
    //  SessionRenderer.Host
    // ══════════════════════════════════════════════════════════

    @Override @Nullable public String peerName() {
        return binder != null ? binder.connectedPeerName() : null;
    }

    @Override @Nullable public byte[] peerAvatar() {
        return binder != null ? binder.connectedPeerAvatar() : null;
    }

    @Override public void resetLatchedNfc() {
        nfcDelegate.resetLatched();
    }

    @Override public void requestSend() {
        doSend();
    }

    @Override public void requestSendProfileCard() {
        sendMyProfileCard();
    }

    // ══════════════════════════════════════════════════════════
    //  ProfileCardSheet.Host
    // ══════════════════════════════════════════════════════════

    @Override
    public void onProfileChanged() {
        refreshAvatar();
        ProfileStore ps = ProfileStore.get(this);
        LocalPairing.setDisplayName(ps.name());
        sessionRenderer.updateSelfIdentity(ps.name(), ps.loadAvatar());
        if (binder != null) nfcDelegate.onBinderReady(); // refresh token after profile change
    }

    @Override
    public void sendMyProfileCard() {
        if (binder == null || !viewModel.isConnected()) {
            viewModel.setPendingCardSend(true);
            toast(getString(R.string.card_queued));
            return;
        }
        ProfileStore ps = ProfileStore.get(this);
        Profile p = ps.load();
        if (p.getName() == null || p.getName().trim().isEmpty()) p.setName(ps.name());
        p.setPhoto(ps.avatarThumbBytes());
        String fileName = getString(R.string.card_of, ps.name());
        SendItem card = SendItem.vcard(fileName, p.toVCard());
        List<SendItem> one = new ArrayList<>();
        one.add(card);
        binder.sendItems(one);
        sessionRenderer.playCardFly();
        haptic();
    }

    // ── HomeNfcDelegate.Host 實作 ──────────────────────────────

    private final HomeNfcDelegate.Host nfcHost = new HomeNfcDelegate.Host() {
        @Override @NonNull
        public FileTransferService.TransferBinder requireBinder() {
            if (binder == null) throw new IllegalStateException("requireBinder: binder is null");
            return binder;
        }
        @Override public void haptic() { HomeActivity.this.haptic(); }
        @Override public void toast(@NonNull String message) { HomeActivity.this.toast(message); }
        @Override public boolean hasConnectivityPermissions() {
            return PermissionHelper.hasConnectivityPermissions(HomeActivity.this);
        }
        @Override public void requestPermissions() {
            PermissionHelper.requestPermissions(HomeActivity.this);
        }
    };
}
