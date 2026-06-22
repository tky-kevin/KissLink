package com.kisslink.ui.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.pairing.LocalPairing;
import com.kisslink.pairing.NfcPairingController;
import com.kisslink.pairing.PairingToken;
import com.kisslink.profile.Profile;
import com.kisslink.profile.ProfileStore;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;
import com.kisslink.transfer.TransferProtocol;
import com.kisslink.ui.history.HistorySheet;
import com.kisslink.ui.profile.ProfileCardSheet;
import com.kisslink.ui.profile.ReceivedCardSheet;
import com.kisslink.util.PermissionHelper;
import com.kisslink.util.ThemePrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 單頁主畫面（C 方案 Beam）——配對、連線、傳輸全在這一頁，靠 {@link SessionState} 切換內容。
 *
 * <p>NFC 配對沿用 {@link NfcPairingController}（reader/HCE，與舊 PairingActivity 同機制），
 * latch 後經 binder 餵入 Service 的 PairingCoordinator；連線/傳輸狀態由單一 SessionState 驅動 UI。
 *
 * <p>MVVM：選取／傳輸／接收等狀態與其衍生判斷集中在 {@link HomeViewModel}；本 Activity 是薄殼，
 * 只負責生命週期、權限、view binding、觀察 ViewModel 與轉發使用者意圖。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class HomeActivity extends AppCompatActivity implements ProfileCardSheet.Host {

    private static final String TAG = "HomeActivity";

    // ── Views ──
    private BeamStageView beam;
    private TextView   tvHeadline, tvSub, tvPercent, tvPercentUnit, tvReceived, tvStackLabel;
    private LinearLayout percentRow, receivedBanner;
    private View sendStackRow, pickRow;
    private android.widget.FrameLayout stackThumbs;
    private MaterialButton btnPickFiles, btnPickMedia, btnSend;
    private ImageButton ibHistory, ibSettings;
    private ShapeableImageView ivAvatar;
    private MaxHeightRecyclerView rvTransfer;

    private SendListAdapter itemsAdapter;
    // 傳輸中列表方塊用獨立 adapter（與待傳 sheet 的 itemsAdapter 分離，避免縮圖執行緒池共用衝突）。
    private SendListAdapter transferAdapter;
    // 傳輸列表自動捲動到當前列；使用者一旦手動拖動即停（本次傳輸內不再自動捲）。
    private boolean transferAutoScroll = true;
    private boolean inTransferUi = false;        // 是否已進入傳輸版面（用於每段傳輸只重置一次自動捲動）
    private int lastAutoScrollIndex = -1;        // 上次自動捲到的列，避免每幀重複 smoothScroll
    private boolean receiveListActive = false;   // rvTransfer 目前顯示的是「接收列表」(持久)而非送出列表
    private final SendSheetManager sendSheetManager = new SendSheetManager();

    // 選取／傳輸／接收等狀態與其衍生判斷集中於此（MVVM）；本 Activity 僅渲染與轉發意圖。
    private HomeViewModel viewModel;
    // 傳輸 UI 渲染邏輯（連線階段/進度/速度顯示），從本 Activity 拆出。
    private TransferUiController ui;

    // ── Service ──
    @Nullable private FileTransferService.TransferBinder binder;
    private boolean bound = false;
    // 穩定的 observer 實例:背景/前景往返會反覆 onServiceConnected,用固定實例避免重複註冊堆疊。
    private final androidx.lifecycle.Observer<SessionState> sessionObserver = this::onSession;
    private final androidx.lifecycle.Observer<byte[]> incomingCardObserver = vcard -> {
        if (vcard == null || vcard.length == 0 || binder == null) return;
        ReceivedCardSheet.newInstance(vcard).show(getSupportFragmentManager(), "received_card");
        binder.clearIncomingCard();
    };
    // 收完一個檔 → 把接收列表中該列補上存檔 Uri（可點開、顯示縮圖），僅更新該列避免 flicker。
    private final androidx.lifecycle.Observer<FileTransferService.ReceivedItem> receivedItemObserver = item -> {
        if (item == null || !receiveListActive) return;
        viewModel.setReceivedUri(item.name, item.contentUri, item.mime);
        Uri uri = item.contentUri != null ? Uri.parse(item.contentUri) : null;
        transferAdapter.updateReceivedThumbnail(item.name, uri, item.mime);
    };

    // ── NFC ──
    @Nullable private NfcPairingController nfc;
    private boolean resumed = false;

    // ── 主執行緒 Handler（延遲回復用）──
    private final Handler main = new Handler(Looper.getMainLooper());

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
                    // 由 MIME 判定型別:圖片/影片走 ITEM_PHOTO 才會顯示縮圖(否則一律檔案圖示)。
                    byte type = itemTypeForMime(getContentResolver().getType(uri));
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
        ThemePrefs.apply(this);   // ← 套用儲存的深/淺/系統偏好
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);
        bindViews();
        applyInsets();
        observeViewModel();

        // 名片姓名 → 對外配對顯示名稱
        LocalPairing.setDisplayName(ProfileStore.get(this).name());

        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
        }

        // Service 的啟動/綁定移到 onStart（解綁在 onStop）：離開 App（背景）即解綁，
        // 觸發 Service 的閒置自動拆除，不再讓 Wi-Fi Direct 在背景常駐。
        renderReady();

        // 從其他 app 分享檔案進來 → 加入待傳清單。
        handleShareIntent(getIntent());
    }

    private void bindViews() {
        beam        = findViewById(R.id.beam);
        tvHeadline  = findViewById(R.id.tvHeadline);
        tvSub       = findViewById(R.id.tvSub);
        tvPercent   = findViewById(R.id.tvPercent);
        tvPercentUnit= findViewById(R.id.tvPercentUnit);
        percentRow  = findViewById(R.id.percentRow);
        sendStackRow= findViewById(R.id.sendStackRow);
        stackThumbs = findViewById(R.id.stackThumbs);
        tvStackLabel= findViewById(R.id.tvStackLabel);
        pickRow     = findViewById(R.id.pickRow);
        btnPickFiles= findViewById(R.id.btnPickFiles);
        btnPickMedia= findViewById(R.id.btnPickMedia);
        btnSend     = findViewById(R.id.btnSend);
        ibHistory   = findViewById(R.id.ibHistory);
        ibSettings  = findViewById(R.id.ibSettings);
        ivAvatar    = findViewById(R.id.ivAvatar);
        tvReceived     = findViewById(R.id.tvReceived);
        receivedBanner = findViewById(R.id.receivedBanner);
        rvTransfer     = findViewById(R.id.rvTransfer);

        ui = new TransferUiController(this, main, tvHeadline, tvSub, tvPercent, tvPercentUnit, percentRow);

        itemsAdapter = new SendListAdapter();
        itemsAdapter.setOnRemove(viewModel::removeSelection);
        itemsAdapter.setOnItemClickListener(this::openFile);
        sendStackRow.setOnClickListener(v -> showSendSheet());

        // 傳輸中列表方塊：唯讀（可點開檔案、不可移除），獨立 adapter。
        transferAdapter = new SendListAdapter();
        transferAdapter.setOnItemClickListener(this::openFile);
        rvTransfer.setLayoutManager(new LinearLayoutManager(this));
        rvTransfer.setAdapter(transferAdapter);
        rvTransfer.setClipToOutline(true);   // 內容裁切到 bg_chip 的圓角，列高亮/淡出貼齊邊緣
        // 高度上限：約 4.5 列；≤上限依內容自適應(上下對稱)，超過才固定並可捲動。
        rvTransfer.setMaxHeight(Math.round(252 * getResources().getDisplayMetrics().density));
        // 上下留白用 ItemDecoration（不可用 padding，否則內建淡出邊會被推離圓角邊緣）。
        final int rvVPad = Math.round(12 * getResources().getDisplayMetrics().density);
        rvTransfer.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                                 @NonNull RecyclerView parent,
                                                 @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos == 0) outRect.top = rvVPad;
                int count = parent.getAdapter() != null ? parent.getAdapter().getItemCount() : 0;
                if (count > 0 && pos == count - 1) outRect.bottom = rvVPad;
            }
        });
        rvTransfer.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // 使用者手動拖動 → 交還捲動控制權，本次傳輸內不再自動捲。
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) transferAutoScroll = false;
            }
        });

        // #9：點「已連線至 xxx」可手動斷線
        tvHeadline.setOnClickListener(v -> onHeadlineTapped());

        // 已接收方塊整塊可點 → 開啟該批次（與「待傳方塊」整塊可點的互動一致）。
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
        btnSend.setOnClickListener(v -> doSend());

        ibHistory.setOnClickListener(v ->
                new HistorySheet().show(getSupportFragmentManager(), "history"));
        ibSettings.setOnClickListener(v ->
                SettingsSheet.newInstance().show(getSupportFragmentManager(), "settings"));
        ivAvatar.setOnClickListener(v ->
                ProfileCardSheet.newInstance().show(getSupportFragmentManager(), "profile"));

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

    /** 把 ViewModel 的狀態接到 UI 渲染：待傳清單 → 疊圖/送出鈕；接收件數 → 橫幅。 */
    private void observeViewModel() {
        viewModel.getSelection().observe(this, items -> {
            // 接收方：若接收列表顯示中且使用者新增待傳項目 → 收合為橫幅
            if (receiveListActive && !inTransferUi && !viewModel.isSelectionEmpty()) {
                receiveListActive = false;
                rvTransfer.setVisibility(View.GONE);
                viewModel.collapseReceiveListToBanner();
            }
            rebuildSendStack();
            updateSendButton();
        });
        viewModel.getReceivedCount().observe(this, count -> {
            if (count != null && count > 0) showReceivedBanner(count);
            else hideReceivedBanner();
        });
    }

    @Override protected void onStart() {
        super.onStart();
        // 進入前景（含背景閒置拆除後返回）→ 確保 Service 存在並綁定。
        Intent svc = FileTransferService.intent(this);
        startForegroundService(svc); // 若已在運行則無害；若已被閒置拆除則復活
        if (!bound) {
            bindService(svc, connection, BIND_AUTO_CREATE);
            bound = true;
        }
    }

    @Override protected void onStop() {
        super.onStop();
        // 離開 App（切到其他 app / 回主畫面 / 鎖屏）→ 解綁。Service 仍為已啟動的前景服務而存活，
        // 但若沒在傳檔，會在閒置逾時（IDLE_TEARDOWN_MS）後自動 stopSelf 釋放 Wi-Fi Direct。
        // 傳輸中（transferring）時 Service 不會自拆，背景傳檔可繼續。返回 App（onStart）會重新綁定。
        if (bound) {
            try { unbindService(connection); } catch (IllegalArgumentException ignored) {}
            bound = false;
            binder = null;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshAvatar();
        // 名片可能在 sheet 內被編輯 → 同步顯示名稱
        LocalPairing.setDisplayName(ProfileStore.get(this).name());
        enableNfcIfReady();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (nfc != null) nfc.disable();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (nfc != null) nfc.handleIntent(intent);
        handleShareIntent(intent);
    }

    /**
     * 接收「分享選單」送進來的檔案/相片(ACTION_SEND / ACTION_SEND_MULTIPLE)→ 併入待傳清單。
     * 分享的 URI 帶臨時讀取授權,於本 Activity 期間有效(碰一下連線 → 傳送 皆在同一畫面內完成)。
     */
    private void handleShareIntent(@Nullable Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean single = Intent.ACTION_SEND.equals(action);
        boolean multi  = Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (!single && !multi) return;

        List<Uri> uris = new ArrayList<>();
        if (single) {
            Uri u = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (u != null) {
                uris.add(u);
            } else {
                // 沒有檔案串流 → 分享的是純文字/連結：加入待傳清單。
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (text != null && text.toString().trim().length() > 0) {
                    String content = text.toString().trim();
                    CharSequence subj = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                    if (subj != null && subj.toString().trim().length() > 0)
                        content = subj.toString().trim() + "\n\n" + content;
                    List<SendItem> picked = new ArrayList<>();
                    picked.add(SendItem.text(content));
                    viewModel.addAllToSelection(picked);
                    toast(getString(R.string.share_added_text));
                    clearShareIntent();
                    return;
                }
            }
        } else {
            ArrayList<Uri> list =
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) { toast(getString(R.string.share_unsupported)); clearShareIntent(); return; }

        List<SendItem> picked = new ArrayList<>();
        for (Uri uri : uris) {
            String mt = getContentResolver().getType(uri);
            if (mt == null) mt = intent.getType();
            picked.add(SendItem.fromUri(getContentResolver(), uri, itemTypeForMime(mt)));
        }
        viewModel.addAllToSelection(picked);
        toast(getString(R.string.share_added, uris.size()));
        clearShareIntent(); // 避免旋轉/重綁時重複加入
    }

    /** 圖片/影片 → ITEM_PHOTO(可顯示縮圖);其餘 → ITEM_FILE。供檔案選擇器與分享接收共用。 */
    private static byte itemTypeForMime(@Nullable String mime) {
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
            return TransferProtocol.ITEM_PHOTO;
        }
        return TransferProtocol.ITEM_FILE;
    }

    /** 消費掉分享 intent,換成普通 intent,避免後續生命週期重複處理。 */
    private void clearShareIntent() {
        setIntent(new Intent(this, HomeActivity.class));
    }

    private void copyToClipboard(@NonNull String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("KissLink", text));
            toast(getString(R.string.share_copied));
        }
    }

    /** 點擊收到的文字/連結：彈出對話框顯示全文（連結可點擊），可複製；連結可開啟。 */
    private void showReceivedTextDialog(@NonNull String text, @Nullable Uri fileUri) {
        boolean isLink = android.util.Patterns.WEB_URL.matcher(text).matches();
        com.google.android.material.dialog.MaterialAlertDialogBuilder b =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(isLink ? R.string.share_link_title : R.string.share_text_title)
                        .setMessage(text)
                        .setPositiveButton(R.string.action_copy, (d, w) -> copyToClipboard(text))
                        .setNegativeButton(R.string.btn_cancel, null);
        if (isLink) {
            b.setNeutralButton(R.string.action_open, (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(text))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    toast(getString(R.string.share_open_failed));
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

    @Override protected void onDestroy() {
        super.onDestroy();
        if (bound) { unbindService(connection); bound = false; }
        // 不在此 stopService：配對中 Activity 可能重建；服務由 onTaskRemoved / 閒置自動結束。
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
            // bound 由 onStart/onStop 管理(綁定生命週期);此處只接上 binder。
            binder = (FileTransferService.TransferBinder) service;
            enableNfcIfReady();
            // 用穩定 observer 實例 + 固定 owner(this):同一 LiveData 重綁不重複註冊;
            // 服務被閒置拆除後重建(新 LiveData)則自動重新觀察。
            binder.getSessionState().observe(HomeActivity.this, sessionObserver);
            binder.getIncomingCard().observe(HomeActivity.this, incomingCardObserver);
            binder.getReceivedItem().observe(HomeActivity.this, receivedItemObserver);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            // 服務進程意外中止(非主動解綁);binding 仍註冊,AUTO_CREATE 會重連。
            binder = null;
        }
    };

    // ══════════════════════════════════════════════════════════
    //  NFC
    // ══════════════════════════════════════════════════════════

    private void ensureController() {
        if (nfc != null) return;
        nfc = new NfcPairingController(this, new NfcPairingController.Callback() {
            @Override public void onPeerToken(@NonNull PairingToken peer) {
                haptic();
                // 未就緒時由 connectivityReadyOrPrompt 自行決定解鎖時機(權限即時解鎖、
                // radio 提示則於關閉後解鎖),避免在此提早解鎖造成兩機貼合連觸疊出多個提示。
                if (!connectivityReadyOrPrompt()) return;
                final FileTransferService.TransferBinder b = binder;
                if (b != null) b.onNfcLatchedAsReader(peer);
            }
            @Override public void onTagRead() {
                haptic();
                if (!connectivityReadyOrPrompt()) return;
                final FileTransferService.TransferBinder b = binder;
                if (b != null) b.onNfcLatchedAsTag();
            }
            @Override public void onError(@NonNull String message) { toast(message); }
        });
    }

    private void enableNfcIfReady() {
        if (binder == null || !resumed) return;
        ensureController();
        nfc.setLocalToken(binder.localToken());
        nfc.enable();
        nfc.handleIntent(getIntent());
    }

    // ══════════════════════════════════════════════════════════
    //  狀態 → UI
    // ══════════════════════════════════════════════════════════

    private void onSession(SessionState st) {
        SessionState.Phase p = st.phase;

        switch (p) {
            case IDLE:
            case CANCELLED:
                ui.stopStageTicker();
                renderReady();
                if (nfc != null && viewModel.lastPhase() != SessionState.Phase.IDLE) nfc.resetLatched();
                break;

            case RESETTING:
                ui.stopStageTicker();
                beam.setPhase(BeamStageView.CONNECTING);
                ui.showLoadingHeadline(getString(R.string.home_resetting_title), "");
                break;

            case PAIRING_LATCHED:
            case PAIRING_LINKING:
            case PAIRING_ELECTING:
            case CREATING_GROUP:
            case HOSTING:
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
                    ui.showPeerIdentity(beam, connectedPeerName(), connectedPeerAvatar(),
                            ProfileStore.get(this).name(), ProfileStore.get(this).loadAvatar());
                    ui.showHeadlineText(getString(R.string.home_connected_title),
                            connectedSub());
                    if (nfc != null) nfc.resetLatched();
                    exitTransferUi();
                    restoreReceiveListIfAny();
                    viewModel.setSending(false);
                    updateSendButton();
                    rebuildSendStack();
                    if (viewModel.isPendingCardSend()) {
                        viewModel.setPendingCardSend(false);
                        main.post(this::sendMyProfileCard);
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
                ui.showHeadlineText(getString(R.string.home_error_title),
                        st.error != null ? st.error : getString(R.string.home_error_retry));
                if (nfc != null) nfc.resetLatched();
                break;

            default: break;
        }
        viewModel.onSession(st);
    }

    // ── READY ──
    private void renderReady() {
        beam.setPhase(BeamStageView.READY);
        ui.showPeerIdentity(beam, null, null,
                ProfileStore.get(this).name(), ProfileStore.get(this).loadAvatar());
        ui.showHeadlineText(getString(R.string.home_ready_title),
                getString(R.string.home_ready_sub));
        // 還原傳輸中版面：收合列表方塊（含持久的接收列表）、顯示挑檔列。
        inTransferUi = false;
        receiveListActive = false;
        viewModel.clearReceivedList();
        rvTransfer.setVisibility(View.GONE);
        pickRow.setVisibility(View.VISIBLE);
        hideReceivedBanner();
        viewModel.resetReceived();
        viewModel.resetCelebration();
        viewModel.setSending(false);
        updateSendButton();
        rebuildSendStack();
    }

    // ── 連線對象身份 ──
    @Nullable private String connectedPeerName() {
        return binder != null ? binder.connectedPeerName() : null;
    }

    @Nullable private byte[] connectedPeerAvatar() {
        return binder != null ? binder.connectedPeerAvatar() : null;
    }

    /** 已連線副標：「已連線至 ◯◯」；無對方名稱時回退「對方」。 */
    private String connectedSub() {
        String peer = connectedPeerName();
        return getString(R.string.home_connected_sub,
                peer != null ? peer : getString(R.string.peer_fallback));
    }

    // ── 傳輸中 ──
    // 畫面只留兩件事：速度（大字英雄）＋ 一個檔案列表方塊（沿用待傳列設計、無標題、當前列高亮）。
    // 進度收斂到中央光環、不顯示百分比文字；不顯示「傳送中 · 檔名」小灰字。
    private void onTransferring(@Nullable TransferProgress tp) {
        if (tp == null) return;
        boolean outgoing = tp.outgoing;
        beam.setDirection(outgoing ? BeamStageView.SEND : BeamStageView.RECEIVE);
        beam.setPhase(BeamStageView.TRANSFERRING);
        beam.setProgress(viewModel.batchProgress(tp));   // #3：整包進度
        // 速度作為主角：大字 + tabular 數字（取代「已連線」與小灰字）
        ui.showSpeedHero(TransferUiController.speedNumber(tp.speedBps),
                TransferUiController.speedUnit(tp.speedBps));
        // 進入傳輸版面的第一幀重置自動捲動（之後每幀都會走到這，但只重置一次，
        // 否則使用者手動捲動會被下一幀的進度更新覆蓋）。
        if (!inTransferUi) {
            inTransferUi = true;
            transferAutoScroll = true;
            lastAutoScrollIndex = -1;
        }
        enterTransferUi(outgoing);
        if (outgoing) {
            // 送出方：把待傳清單展開成傳輸中的列表方塊（只建一次），逐幀只更新當前列進度/高亮。
            if (tp.itemType != TransferProtocol.ITEM_VCARD) {
                if (rvTransfer.getVisibility() != View.VISIBLE) buildTransferList();
                updateTransferProgress(tp, false);
            }
        } else {
            // 接收方：逐檔累積成接收列表（取代「收到 N 個」橫幅），樣式比照送出列表。
            showReceiveList(tp, false);
        }
    }

    private void onTransferDone(@Nullable TransferProgress tp, boolean all) {
        boolean outgoing = tp != null && tp.outgoing;
        boolean isVcard = tp != null && tp.itemType == TransferProtocol.ITEM_VCARD;

        // #3：多檔的「中間檔」（FILE_DONE，尚未到最後一筆）→ 維持傳輸視覺，只推進整包進度（雙向皆同）
        if (!isVcard && !all && tp != null && tp.fileCount > 1) {
            beam.setPhase(BeamStageView.TRANSFERRING);
            beam.setProgress(viewModel.batchProgress(tp));
            if (outgoing) updateTransferProgress(tp, true);
            else showReceiveList(tp, true);
            return;
        }

        // #2：完成是「一次性事件」。切換深淺色會重建 Activity，重綁時 sessionLd 會把黏著的
        // ALL_DONE 補送給新訂閱者；若每次都重跑 DONE，會重播打勾/彩帶。對同一批次只慶祝一次，
        // 補送的同批完成事件 → 不重播，直接呈現「已連線」穩態（與真實完成 1.4 秒後回到的畫面一致）。
        long batchId = tp != null ? tp.batchId : 0L;
        if (!viewModel.shouldCelebrate(batchId)) {
            if (viewModel.isConnected()) {
                beam.setPhase(BeamStageView.CONNECTED);
                beam.setProgress(1f);
                ui.hidePercent();
                ui.showPeerIdentity(beam, connectedPeerName(), connectedPeerAvatar(),
                        ProfileStore.get(this).name(), ProfileStore.get(this).loadAvatar());
                ui.showHeadlineText(getString(R.string.home_connected_title), connectedSub());
            }
            exitTransferUi();
            restoreReceiveListIfAny();   // 重建後（如切換深淺色）把 VM 中的接收列表重新顯示
            updateSendButton();
            rebuildSendStack();
            return;
        }

        // 其餘 → 顯示完成（最後一筆 ALL_DONE / 名片 / 單檔）：列表方塊收合、打勾＋彩帶、速度換成完成文案
        beam.setPhase(BeamStageView.DONE);
        beam.setProgress(1f);
        ui.hidePercent();
        exitTransferUi();
        String peer = binder != null ? binder.connectedPeerName() : null;
        String who = peer != null ? peer : getString(R.string.peer_fallback);
        ui.showHeadlineText(getString(R.string.transfer_done),
                outgoing ? getString(R.string.sent_to, who) : getString(R.string.received_from, who));

        if (isVcard) {
            // 名片獨立傳送，不影響待傳清單
        } else if (outgoing) {
            // 整批傳完 → 清空待傳清單與送出狀態（會透過 selection LiveData 重建 UI）
            viewModel.onBatchSent();
            rebuildSendStack();
            if (sendSheetManager.isShowing()) sendSheetManager.dismiss();
        } else if (tp != null) {
            showReceiveList(tp, true);
        }

        // 短暫顯示完成後回到「已連線」可再選
        main.postDelayed(() -> {
            SessionState.Phase last = viewModel.lastPhase();
            if ((last == SessionState.Phase.ALL_DONE || last == SessionState.Phase.FILE_DONE)
                    && viewModel.isConnected()) {
                beam.setPhase(BeamStageView.CONNECTED);
            }
        }, 1400);
        updateSendButton();
    }

    private void showReceivedBanner(int count) {
        tvReceived.setText(getString(R.string.received_batch, count));
        Anim.revealFadeUp(receivedBanner);
    }

    private void hideReceivedBanner() {
        receivedBanner.setVisibility(View.GONE);
    }

    // ── 內容選擇 / 傳送 ──
    private void doSend() {
        if (binder == null || !viewModel.isConnected() || viewModel.isSelectionEmpty()) return;
        viewModel.markSendingStarted();   // 記錄本批 + 標記送出中（送出鈕暫時隱藏）
        binder.sendItems(new ArrayList<>(viewModel.currentSelection()));
        updateSendButton();
        haptic();
    }

    /**
     * 送出鈕：只要「已連線且待傳清單有東西」就顯示；僅在本端正在送出時隱藏。
     * 不再用 lastPhase 判斷——否則「接收對方一包檔」期間/結束時會誤把送出鈕藏掉。
     */
    private void updateSendButton() {
        if (viewModel.shouldShowSendButton()) {
            btnSend.setText(getString(R.string.btn_send_n, viewModel.selectionCount()));
            Anim.revealFadeUp(btnSend);
        } else {
            btnSend.setVisibility(View.GONE);
        }
    }

    // ── 待傳清單（疊起來 + 彈出）#2 ──
    private void rebuildSendStack() {
        // adapter 內容（供彈出清單）
        List<SendRow> rows = new ArrayList<>();
        for (SendItem it : viewModel.currentSelection()) {
            SendRow r = new SendRow(it.name, TransferUiController.sizeLabel(it.size), it.itemType,
                    it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null,
                    it.uri, it.mime);
            r.removable = true;
            rows.add(r);
        }
        itemsAdapter.submit(rows);

        // 底部疊起來的摘要
        if (viewModel.isSelectionEmpty()) {
            sendStackRow.setVisibility(View.GONE);
            if (sendSheetManager.isShowing()) sendSheetManager.dismiss();
            return;
        }
        tvStackLabel.setText(getString(R.string.send_stack_label, viewModel.selectionCount()));
        buildStackThumbs();
        // 與「已接收方塊」一致：變為可見時才播淡入上滑（point 7 統一入場動畫）。
        Anim.revealFadeUp(sendStackRow);
    }

    /** 疊起來的縮圖（最多 3 個，往右錯開重疊）。 */
    private void buildStackThumbs() {
        stackThumbs.removeAllViews();
        List<SendItem> selection = viewModel.currentSelection();
        float d = getResources().getDisplayMetrics().density;
        int sizePx = Math.round(40 * d);
        int stepPx = Math.round(22 * d);
        int pad = Math.round(9 * d);
        int max = Math.min(3, selection.size());
        for (int i = 0; i < max; i++) {
            SendItem it = selection.get(i);
            ShapeableImageView iv = new ShapeableImageView(this);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(sizePx, sizePx);
            lp.leftMargin = i * stepPx;
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(pad, pad, pad, pad);
            iv.setImageResource(TransferUiController.iconForItem(it));
            stackThumbs.addView(iv);
        }
    }

    /** 點擊摘要 → 彈出完整可移除清單。 */
    private void showSendSheet() {
        if (viewModel.isSelectionEmpty()) return;
        sendSheetManager.showIfNotEmpty(this, itemsAdapter, viewModel::clearSelection);
    }

    /**
     * 點中央大字標題:
     * <ul>
     *   <li>已連線 → 確認後手動斷線(#9)。</li>
     *   <li>配對/連線中或失敗 → 立即輕量中斷重來,確保卡住時一點即解、可即時再貼合重連。</li>
     * </ul>
     */
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

    /** 是否為「可一點即中斷」的配對/連線/失敗階段(RESETTING 不在內——正在沉澱拆除中)。 */
    private static boolean isInterruptiblePairing(SessionState.Phase p) {
        switch (p) {
            case PAIRING_LATCHED:
            case PAIRING_LINKING:
            case PAIRING_ELECTING:
            case CREATING_GROUP:
            case HOSTING:
            case CONNECTING:
            case SOCKETING:
            case ERROR:
                return true;
            default:
                return false;
        }
    }

    /**
     * 建立連線前的就緒檢查:先確認連線必需的執行期權限,再確認 NFC/藍牙/Wi-Fi 已開啟。
     * 任一未就緒 → 提示使用者(請求權限 / 開啟對應設定)並回 false,呼叫端應放掉本次配對。
     */
    private boolean connectivityReadyOrPrompt() {
        if (!PermissionHelper.hasConnectivityPermissions(this)) {
            PermissionHelper.requestPermissions(this);
            toast(getString(R.string.conn_need_perms));
            // 系統權限視窗會讓 Activity onPause → nfc.disable(),NFC 派發停止;
            // 此處解鎖安全,不會在兩機貼合時連觸。
            if (nfc != null) nfc.resetLatched();
            return false;
        }
        PermissionHelper.Radio off = PermissionHelper.firstDisabledRadio(this);
        if (off != null) { promptEnableRadio(off); return false; }
        return true;
    }

    /** radio 提示是否正在畫面上(避免同一次貼合連觸疊出第二個對話框)。 */
    private boolean radioPromptShowing = false;

    /** 提示開啟未啟用的無線電,並提供「前往設定」直達對應系統設定頁。 */
    private void promptEnableRadio(@NonNull PermissionHelper.Radio radio) {
        // 此 dialog 為 app 內視窗,不會讓 Activity onPause,NFC 派發/HCE 仍在運作。
        // 若已有提示在畫面上就不再彈第二個,否則兩機貼著會被 NFC 連續觸發而疊出多個。
        if (radioPromptShowing) return;
        final int msgRes;
        final Intent settings;
        switch (radio) {
            case NFC:
                msgRes = R.string.conn_need_nfc;
                settings = new Intent(android.provider.Settings.ACTION_NFC_SETTINGS);
                break;
            case BLUETOOTH:
                msgRes = R.string.conn_need_bluetooth;
                settings = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                break;
            case WIFI:
            default:
                msgRes = R.string.conn_need_wifi;
                settings = new Intent(android.provider.Settings.Panel.ACTION_WIFI);
                break;
        }
        radioPromptShowing = true;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.conn_need_title)
                .setMessage(msgRes)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.action_open_settings, (dd, w) -> {
                    try { startActivity(settings); }
                    catch (Exception e) { toast(getString(msgRes)); }
                })
                .setOnDismissListener(d -> {
                    radioPromptShowing = false;
                    // 關閉後才解鎖:使用者開啟無線電後可再碰一下重試。
                    if (nfc != null) nfc.resetLatched();
                })
                .show();
    }

    /**
     * 傳送中：把待傳清單展開成「列表方塊」（沿用待傳列設計、無標題）。只在進入傳輸時建一次——
     * 結構（檔名/大小/縮圖）在整段傳輸內不變，逐幀的進度/高亮由 {@link #updateTransferProgress} 局部更新，
     * 避免每幀整列重綁導致縮圖閃爍。
     */
    private void buildTransferList() {
        receiveListActive = false;   // 切回送出列表
        List<SendRow> rows = new ArrayList<>();
        for (SendItem it : viewModel.currentSelection()) {
            rows.add(new SendRow(it.name, TransferUiController.sizeLabel(it.size), it.itemType,
                    it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null,
                    it.uri, it.mime));
        }
        transferAdapter.submit(rows);
        rvTransfer.setVisibility(View.VISIBLE);
        Anim.fadeUp(rvTransfer);
    }

    /**
     * 接收方列表（取代「收到 N 個」橫幅）：新檔觸發結構重建（full submit），
     * 既有檔案只更新進度/狀態（payload → 不碰縮圖、不 flicker）。
     * 收完整批後仍保留（持久），可點開個別檔案。
     */
    private void showReceiveList(@NonNull TransferProgress tp, boolean curFileDone) {
        boolean isNew = viewModel.upsertReceived(tp.batchId, tp.fileName, tp.totalBytes,
                tp.itemType, tp.percentInt(), curFileDone);
        receiveListActive = true;
        if (isNew) {
            rebuildReceiveList();
        } else {
            updateReceiveProgress(tp, curFileDone);
        }
    }

    /** 接收方逐幀進度：payload 更新不重設縮圖，不 flicker。 */
    private void updateReceiveProgress(@NonNull TransferProgress tp, boolean curFileDone) {
        transferAdapter.setProgress(tp.fileName, curFileDone ? 100 : tp.percentInt(), curFileDone);
        int idx = transferAdapter.findPositionByName(tp.fileName);
        if (transferAutoScroll && idx >= 0 && idx != lastAutoScrollIndex) {
            lastAutoScrollIndex = idx;
            rvTransfer.smoothScrollToPosition(idx);
        }
    }

    /** Activity 重建後（VM 仍保有接收清單）→ 重新顯示持久的接收列表。 */
    private void restoreReceiveListIfAny() {
        if (!receiveListActive && viewModel.hasReceivedList()) {
            receiveListActive = true;
            rebuildReceiveList();
        }
    }

    private void rebuildReceiveList() {
        List<SendRow> rows = new ArrayList<>();
        int currentIndex = -1, i = 0;
        for (HomeViewModel.RecvFile f : viewModel.receivedFiles()) {
            Uri fileUri = f.uri != null ? Uri.parse(f.uri) : null;
            SendRow r = new SendRow(f.name, TransferUiController.sizeLabel(f.size), f.type,
                    f.type == TransferProtocol.ITEM_PHOTO ? fileUri : null,
                    fileUri, f.mime);
            r.incoming = true;
            r.percent = f.percent;
            r.done = f.done;
            r.highlight = f.highlight;
            rows.add(r);
            if (f.highlight) currentIndex = i;
            i++;
        }
        transferAdapter.submit(rows);
        if (rvTransfer.getVisibility() != View.VISIBLE) {
            rvTransfer.setVisibility(View.VISIBLE);
            Anim.fadeUp(rvTransfer);
        }
        if (transferAutoScroll && currentIndex >= 0 && currentIndex != lastAutoScrollIndex) {
            lastAutoScrollIndex = currentIndex;
            rvTransfer.smoothScrollToPosition(currentIndex);
        }
    }

    /** 逐幀更新傳輸中當前列的進度/完成/高亮（payload 局部 rebind，不碰縮圖），並自動捲動到當前列。 */
    private void updateTransferProgress(@NonNull TransferProgress tp, boolean curFileDone) {
        transferAdapter.setProgress(tp.fileName, curFileDone ? 100 : tp.percentInt(), curFileDone);
        // 自動捲到當前列（除非使用者已手動接管）；只在當前列改變時捲，避免每幀抖動。
        int idx = indexOfSelection(tp.fileName);
        if (transferAutoScroll && idx >= 0 && idx != lastAutoScrollIndex) {
            lastAutoScrollIndex = idx;
            rvTransfer.smoothScrollToPosition(idx);
        }
    }

    private int indexOfSelection(@NonNull String name) {
        List<SendItem> selection = viewModel.currentSelection();
        for (int i = 0; i < selection.size(); i++) {
            if (name.equals(selection.get(i).name)) return i;
        }
        return -1;
    }

    /** 進入「傳輸中」版面：只留速度＋列表方塊，收起挑檔/送出/待傳與（已停用的）接收橫幅。 */
    private void enterTransferUi(boolean outgoing) {
        pickRow.setVisibility(View.GONE);
        btnSend.setVisibility(View.GONE);
        sendStackRow.setVisibility(View.GONE);
        receivedBanner.setVisibility(View.GONE);
    }

    /**
     * 離開「傳輸中」版面（回到已連線/完成/就緒）：還原挑檔列與待傳/送出。
     * 送出列表收合；接收列表為持久顯示（收完仍保留，供檢視/點開）→ 不收合。
     */
    private void exitTransferUi() {
        inTransferUi = false;
        if (!receiveListActive) rvTransfer.setVisibility(View.GONE);
        pickRow.setVisibility(View.VISIBLE);
        rebuildSendStack();
        updateSendButton();
    }

    private void openFile(@androidx.annotation.NonNull SendRow row) {
        if (row.itemType == TransferProtocol.ITEM_TEXT) {
            showReceivedTextDialog(row.name, row.fileUri);
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

    // ── 頭像 ──
    // 一律以「預先合成好的方形點陣圖」+ 固定 centerCrop/零內距顯示(真實頭像與預設字符同路徑),
    // 避免切換內距/scaleType 造成 centerCrop 矩陣沿用舊值而把頭像縮小/裁切。
    private static final int AVATAR_DISPLAY_PX = 256;

    private void refreshAvatar() {
        ivAvatar.setPadding(0, 0, 0, 0);
        // 傳入 Activity（themed context）而非 application context，預設頭像才會跟隨 App 內深/淺色覆寫。
        ivAvatar.setImageBitmap(ProfileStore.get(this).loadAvatarForDisplay(this, AVATAR_DISPLAY_PX));
    }

    // ── 工具 ──
    private boolean ensurePerms() {
        if (PermissionHelper.hasPermissions(this)) return true;
        PermissionHelper.requestPermissions(this);
        return false;
    }

    private void haptic() {
        if (beam != null) beam.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    public static Intent intent(Context ctx) {
        return new Intent(ctx, HomeActivity.class);
    }

    // ══════════════════════════════════════════════════════════
    //  ProfileCardSheet.Host
    // ══════════════════════════════════════════════════════════

    @Override
    public void onProfileChanged() {
        refreshAvatar();
        ProfileStore ps = ProfileStore.get(this);
        LocalPairing.setDisplayName(ps.name());
        beam.setSelfIdentity(ps.name());
        beam.setSelfAvatar(ps.loadAvatar());
        if (nfc != null && binder != null) nfc.setLocalToken(binder.localToken());
    }

    @Override
    public void sendMyProfileCard() {
        if (binder == null || !viewModel.isConnected()) {
            // #1：尚未連線 → 排隊，連上後自動送出
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
        // 名片獨立傳送，不動待傳清單
        binder.sendItems(one);
        beam.playCardFly();   // #14：名片縮入對方頭像的 genie 動畫
        haptic();
    }
}
