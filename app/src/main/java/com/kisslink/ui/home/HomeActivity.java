package com.kisslink.ui.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
    private static final long STAGE_MIN_DWELL_MS = 650; // 連線階段每格最短停留

    // ── Views ──
    private BeamStageView beam;
    private TextView   tvHeadline, tvSub, tvPercent, tvReceived, tvStackLabel;
    private LinearLayout percentRow, receivedBanner;
    private View sendStackRow;
    private android.widget.FrameLayout stackThumbs;
    private MaterialButton btnPickFiles, btnPickMedia, btnSend, btnViewReceived;
    private ImageButton ibHistory, ibSettings;
    private ShapeableImageView ivAvatar;

    private SendListAdapter itemsAdapter;
    @Nullable private com.google.android.material.bottomsheet.BottomSheetDialog sendSheet;

    // 選取／傳輸／接收等狀態與其衍生判斷集中於此（MVVM）；本 Activity 僅渲染與轉發意圖。
    private HomeViewModel viewModel;

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

    // ── NFC ──
    @Nullable private NfcPairingController nfc;
    private boolean resumed = false;

    // ── 主執行緒 Handler（連線階段序列器 / 延遲回復用）──
    private final Handler main = new Handler(Looper.getMainLooper());

    // 連線階段序列器（NFC→BLE→Wi-Fi，每格最短停留）
    private int stageShown = -1, stageTarget = 0;
    private boolean stageRunning = false;

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
                    picked.add(SendItem.fromUri(getContentResolver(), uri, TransferProtocol.ITEM_FILE));
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
        percentRow  = findViewById(R.id.percentRow);
        sendStackRow= findViewById(R.id.sendStackRow);
        stackThumbs = findViewById(R.id.stackThumbs);
        tvStackLabel= findViewById(R.id.tvStackLabel);
        btnPickFiles= findViewById(R.id.btnPickFiles);
        btnPickMedia= findViewById(R.id.btnPickMedia);
        btnSend     = findViewById(R.id.btnSend);
        ibHistory   = findViewById(R.id.ibHistory);
        ibSettings  = findViewById(R.id.ibSettings);
        ivAvatar    = findViewById(R.id.ivAvatar);
        tvReceived     = findViewById(R.id.tvReceived);
        receivedBanner = findViewById(R.id.receivedBanner);
        btnViewReceived= findViewById(R.id.btnViewReceived);

        itemsAdapter = new SendListAdapter();
        itemsAdapter.setOnRemove(viewModel::removeSelection);
        itemsAdapter.setOnItemClickListener(this::openFile);
        sendStackRow.setOnClickListener(v -> showSendSheet());

        // #9：點「已連線至 xxx」可手動斷線
        tvHeadline.setOnClickListener(v -> onHeadlineTapped());

        btnViewReceived.setOnClickListener(v -> {
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
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) { toast(getString(R.string.share_unsupported)); clearShareIntent(); return; }

        List<SendItem> picked = new ArrayList<>();
        for (Uri uri : uris) {
            byte type = TransferProtocol.ITEM_FILE;
            String mt = getContentResolver().getType(uri);
            if (mt == null) mt = intent.getType();
            if (mt != null && mt.startsWith("image/")) type = TransferProtocol.ITEM_PHOTO;
            picked.add(SendItem.fromUri(getContentResolver(), uri, type));
        }
        viewModel.addAllToSelection(picked);
        toast(getString(R.string.share_added, uris.size()));
        clearShareIntent(); // 避免旋轉/重綁時重複加入
    }

    /** 消費掉分享 intent,換成普通 intent,避免後續生命週期重複處理。 */
    private void clearShareIntent() {
        setIntent(new Intent(this, HomeActivity.class));
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
                // 建立任何連線前先確認權限與無線電都就緒;否則提示開啟並放掉這次 latch(可立即再碰)。
                if (!connectivityReadyOrPrompt()) { if (nfc != null) nfc.resetLatched(); return; }
                if (binder != null) binder.onNfcLatchedAsReader(peer);
            }
            @Override public void onTagRead() {
                haptic();
                if (!connectivityReadyOrPrompt()) { if (nfc != null) nfc.resetLatched(); return; }
                if (binder != null) binder.onNfcLatchedAsTag();
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
                stopStageTicker();
                renderReady();
                if (nfc != null && viewModel.lastPhase() != SessionState.Phase.IDLE) nfc.resetLatched();
                break;

            case RESETTING:
                stopStageTicker();
                beam.setPhase(BeamStageView.CONNECTING);
                showHeadlineText(getString(R.string.home_resetting_title), "");
                break;

            case PAIRING_LATCHED:
            case PAIRING_LINKING:
            case PAIRING_ELECTING:
            case CREATING_GROUP:
            case HOSTING:
            case CONNECTING:
                beam.setPhase(BeamStageView.CONNECTING);
                runStageTicker(p);   // #8：連線階段名稱直接作為大字標題（無小字）
                break;

            case CONNECTED:
                stopStageTicker();
                beam.setPhase(BeamStageView.CONNECTED);
                showPeerIdentity();
                showHeadlineText(connectedHeadline(), "");   // #8：已連線至 xxx
                if (nfc != null) nfc.resetLatched(); // 連上後仍可再碰（同對象 resume / 新對象切換）
                viewModel.setSending(false);
                updateSendButton();
                rebuildSendStack();
                if (viewModel.isPendingCardSend()) { // #1
                    viewModel.setPendingCardSend(false);
                    main.post(this::sendMyProfileCard);
                }
                break;

            case TRANSFERRING:
                stopStageTicker();
                onTransferring(st.progress);
                break;

            case FILE_DONE:
            case ALL_DONE:
                onTransferDone(st.progress, p == SessionState.Phase.ALL_DONE);
                break;

            case ERROR:
                stopStageTicker();
                beam.setPhase(BeamStageView.ERROR);
                showHeadlineText("連線中斷", st.error != null ? st.error : "請再碰一下重試");
                if (nfc != null) nfc.resetLatched();
                break;

            default: break;
        }
        viewModel.onSession(st);
    }

    // ── READY ──
    private void renderReady() {
        beam.setPhase(BeamStageView.READY);
        beam.setSelfIdentity(ProfileStore.get(this).name());
        beam.setSelfAvatar(ProfileStore.get(this).loadAvatar());
        beam.setPeerIdentity(null);
        beam.setPeerAvatar(null);
        showHeadlineText(getString(R.string.home_ready_title), "");   // #8：去掉小字
        hideReceivedBanner();
        viewModel.resetReceived();
        viewModel.setSending(false);
        updateSendButton();
        rebuildSendStack();
    }

    // ── 連線階段序列器 ──
    private void runStageTicker(SessionState.Phase p) {
        int target;
        switch (p) {
            case PAIRING_LATCHED: target = 0; break;          // NFC
            case PAIRING_LINKING:
            case PAIRING_ELECTING: target = 1; break;          // BLE
            default: target = 2; break;                        // Wi-Fi
        }
        stageTarget = Math.max(stageTarget, target);
        if (!stageRunning) {
            stageRunning = true;
            stageShown = -1;
            main.post(stageTick);
        }
    }

    private final Runnable stageTick = new Runnable() {
        @Override public void run() {
            if (!stageRunning) return;
            if (stageShown < stageTarget) {
                stageShown++;
                // 階段名稱作為大字標題,小字提示「點一下可中斷重來」(失敗不卡死)。
                showHeadlineText(stageLabel(stageShown), getString(R.string.stage_tap_to_cancel));
            }
            main.postDelayed(this, STAGE_MIN_DWELL_MS);
        }
    };

    private void stopStageTicker() {
        stageRunning = false;
        stageTarget = 0;
        stageShown = -1;
        main.removeCallbacks(stageTick);
    }

    private String stageLabel(int step) {
        switch (step) {
            case 0:  return getString(R.string.stage_nfc);
            case 1:  return getString(R.string.stage_ble);
            default: return getString(R.string.stage_wifi);
        }
    }

    // ── 連線對象身份 ──
    private void showPeerIdentity() {
        String peerName = binder != null ? binder.connectedPeerName() : null;
        beam.setPeerIdentity(peerName);
        byte[] avatar = binder != null ? binder.connectedPeerAvatar() : null;
        beam.setPeerAvatar(avatar != null ? decodeAvatar(avatar) : null);
        beam.setSelfAvatar(ProfileStore.get(this).loadAvatar());
        beam.setSelfIdentity(ProfileStore.get(this).name());
    }

    @Nullable
    private static Bitmap decodeAvatar(byte[] bytes) {
        try { return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length); }
        catch (Exception e) { return null; }
    }

    private String peerSubLabel() {
        String peerName = binder != null ? binder.connectedPeerName() : null;
        return peerName != null ? peerName : "可互傳檔案、相片與名片";
    }

    // ── 傳輸中 ──
    private void onTransferring(@Nullable TransferProgress tp) {
        if (tp == null) return;
        boolean outgoing = tp.outgoing;
        beam.setDirection(outgoing ? BeamStageView.SEND : BeamStageView.RECEIVE);
        beam.setPhase(BeamStageView.TRANSFERRING);
        beam.setProgress(viewModel.batchProgress(tp));   // #3：整包進度
        // 傳輸速度顯示在 headline（取代「已連線」字樣），頭像上不放數字
        showTransferHeadline(speedLabelInt(tp.speedBps),
                (outgoing ? getString(R.string.sending) : getString(R.string.receiving))
                        + " · " + tp.fileName);
        if (outgoing) {
            if (tp.itemType != TransferProtocol.ITEM_VCARD) updateOutgoingRows(tp, false);
        } else {
            // 接收新批次 → 重置橫幅計數
            viewModel.onIncomingBatch(tp.batchId);
        }
    }

    private void onTransferDone(@Nullable TransferProgress tp, boolean all) {
        boolean outgoing = tp != null && tp.outgoing;
        boolean isVcard = tp != null && tp.itemType == TransferProtocol.ITEM_VCARD;

        // #3：多檔的「中間檔」（FILE_DONE，尚未到最後一筆）→ 維持傳輸視覺，只推進整包進度（雙向皆同）
        if (!isVcard && !all && tp != null && tp.fileCount > 1) {
            beam.setPhase(BeamStageView.TRANSFERRING);
            beam.setProgress(viewModel.batchProgress(tp));
            if (outgoing) updateOutgoingRows(tp, true);
            else viewModel.countReceived(tp);
            return;
        }

        // 其餘 → 顯示完成（最後一筆 ALL_DONE / 名片 / 單檔）
        beam.setPhase(BeamStageView.DONE);
        beam.setProgress(1f);
        hidePercent();
        String peer = binder != null ? binder.connectedPeerName() : null;
        String who = peer != null ? peer : "對方";
        showHeadlineText("傳輸完成",
                outgoing ? getString(R.string.sent_to, who) : getString(R.string.received_from, who));

        if (isVcard) {
            // 名片獨立傳送，不影響待傳清單
        } else if (outgoing) {
            updateOutgoingRows(tp, true);
            // 整批傳完 → 清空待傳清單與送出狀態（會透過 selection LiveData 重建 UI）
            viewModel.onBatchSent();
            rebuildSendStack();
            if (sendSheet != null && sendSheet.isShowing()) sendSheet.dismiss();
        } else if (tp != null) {
            viewModel.countReceived(tp);
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
            SendRow r = new SendRow(it.name, sizeLabel(it.size), it.itemType,
                    it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null,
                    it.uri, it.mime);
            r.removable = true;
            rows.add(r);
        }
        itemsAdapter.submit(rows);

        // 底部疊起來的摘要
        if (viewModel.isSelectionEmpty()) {
            sendStackRow.setVisibility(View.GONE);
            if (sendSheet != null && sendSheet.isShowing()) sendSheet.dismiss();
            return;
        }
        tvStackLabel.setText(getString(R.string.send_stack_label, viewModel.selectionCount()));
        buildStackThumbs();
        if (sendStackRow.getVisibility() != View.VISIBLE) {
            sendStackRow.setVisibility(View.VISIBLE);
            Anim.fadeUp(sendStackRow);
        }
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
            iv.setImageResource(iconForItem(it));
            stackThumbs.addView(iv);
        }
    }

    /** 點擊摘要 → 彈出完整可移除清單。 */
    private void showSendSheet() {
        if (viewModel.isSelectionEmpty()) return;
        float d = getResources().getDisplayMetrics().density;
        com.google.android.material.bottomsheet.BottomSheetDialog dlg =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        content.setPadding(pad, pad, pad, Math.round(8 * d));

        // 標題列（含垃圾桶）
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.send_sheet_title);
        title.setTextColor(getColor(R.color.beam_ink));
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        titleRow.addView(title);

        android.widget.ImageButton btnClear = new android.widget.ImageButton(this);
        int btnSize = Math.round(36 * d);
        btnClear.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
        btnClear.setBackground(null);
        btnClear.setImageResource(R.drawable.ic_delete);
        btnClear.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int btnPad = Math.round(6 * d);
        btnClear.setPadding(btnPad, btnPad, btnPad, btnPad);
        btnClear.setOnClickListener(v -> {
            viewModel.clearSelection();
            if (sendSheet != null && sendSheet.isShowing()) sendSheet.dismiss();
        });
        titleRow.addView(btnClear);

        content.addView(titleRow);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(itemsAdapter);
        LinearLayout.LayoutParams rvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rvlp.topMargin = Math.round(10 * d);
        rv.setLayoutParams(rvlp);
        content.addView(rv);

        dlg.setContentView(content);
        dlg.setOnDismissListener(x -> { rv.setAdapter(null); sendSheet = null; });
        sendSheet = dlg;
        dlg.show();
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
            new androidx.appcompat.app.AlertDialog.Builder(this)
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
            return false;
        }
        PermissionHelper.Radio off = PermissionHelper.firstDisabledRadio(this);
        if (off != null) { promptEnableRadio(off); return false; }
        return true;
    }

    /** 提示開啟未啟用的無線電,並提供「前往設定」直達對應系統設定頁。 */
    private void promptEnableRadio(@NonNull PermissionHelper.Radio radio) {
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
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.conn_need_title)
                .setMessage(msgRes)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.action_open_settings, (dd, w) -> {
                    try { startActivity(settings); }
                    catch (Exception e) { toast(getString(msgRes)); }
                })
                .show();
    }

    private String connectedHeadline() {
        String peer = binder != null ? binder.connectedPeerName() : null;
        return peer != null ? getString(R.string.home_connected_to, peer)
                            : getString(R.string.home_connected_title);
    }

    private static int iconForItem(SendItem it) {
        return com.kisslink.utils.FileUtils.iconFor(it.itemType, it.mime, it.name);
    }

    /** 傳送中：以待傳清單為基底，更新對應列的進度/完成（接收端改用收到橫幅，不進清單）。 */
    private void updateOutgoingRows(@NonNull TransferProgress tp, boolean done) {
        List<SendRow> rows = new ArrayList<>();
        for (SendItem it : viewModel.currentSelection()) {
            SendRow r = new SendRow(it.name, sizeLabel(it.size), it.itemType,
                    it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null,
                    it.uri, it.mime);
            if (it.name.equals(tp.fileName)) {
                r.percent = done ? 100 : tp.percentInt();
                r.done = done;
            }
            rows.add(r);
        }
        itemsAdapter.submit(rows);
    }

    private void openFile(@androidx.annotation.NonNull SendRow row) {
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

    // ── headline / percent ──
    private void showHeadlineText(String title, @Nullable String sub) {
        hidePercent();
        tvHeadline.setVisibility(View.VISIBLE);
        boolean changed = !title.contentEquals(tvHeadline.getText());
        tvHeadline.setText(title);
        if (changed) Anim.fadeUp(tvHeadline);
        if (sub != null) {
            boolean subChanged = !sub.contentEquals(tvSub.getText());
            tvSub.setText(sub);
            if (subChanged) Anim.fadeUp(tvSub);
        }
    }

    private void showPercent(int pct) {
        tvHeadline.setVisibility(View.INVISIBLE);
        percentRow.setVisibility(View.VISIBLE);
        tvPercent.setText(pct >= 0 ? String.valueOf(pct) : "0");
    }

    /** 傳輸中：headline 直接顯示速度（取代「已連線」）。頻繁更新故不套淡入動畫，避免閃爍。 */
    private void showTransferHeadline(String speed, @Nullable String sub) {
        percentRow.setVisibility(View.GONE);
        tvHeadline.setVisibility(View.VISIBLE);
        tvHeadline.setText(speed);
        if (sub != null) tvSub.setText(sub);
    }

    /** 速度字串，顯示到整數位（例如「12 MB/s」）。 */
    private static String speedLabelInt(long bps) {
        if (bps < 0) bps = 0;
        if (bps >= 1024L * 1024) return Math.round(bps / (1024.0 * 1024)) + " MB/s";
        if (bps >= 1024) return Math.round(bps / 1024.0) + " KB/s";
        return bps + " B/s";
    }

    private void hidePercent() {
        percentRow.setVisibility(View.GONE);
        tvHeadline.setVisibility(View.VISIBLE);
    }

    // ── 頭像 ──
    private void refreshAvatar() {
        Bitmap a = ProfileStore.get(this).loadAvatar();
        if (a != null) {
            ivAvatar.setPadding(0, 0, 0, 0);
            ivAvatar.setImageBitmap(a);
        } else {
            int pad = Math.round(7 * getResources().getDisplayMetrics().density);
            ivAvatar.setPadding(pad, pad, pad, pad);
            ivAvatar.setImageResource(R.drawable.ic_avatar_default);
        }
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

    private static String sizeLabel(long bytes) {
        return com.kisslink.utils.FileUtils.sizeLabel(bytes);
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
        if (p.name == null || p.name.trim().isEmpty()) p.name = ps.name(); // #11：套用預設名（探索者）
        p.photo = ps.avatarThumbBytes();                                   // #10：頭像隨名片送
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
