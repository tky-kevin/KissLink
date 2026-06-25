package com.kisslink.ui.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
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
    private TextView   tvHeadline, tvSub, tvPercent, tvPercentUnit, tvReceived;
    private LinearLayout percentRow, receivedBanner;
    private View pickRow;
    private MaterialButton btnPickFiles, btnPickMedia;
    private ImageButton ibHistory, ibSettings;
    private ShapeableImageView ivAvatar;

    // 傳輸/接收清單方塊（rvTransfer）的單一擁有者：呈現狀態（是否在傳輸版面/接收清單顯示中/
    // 自動捲動）+ 清單渲染全在此（C3，取代原本散落於本 Activity 的四個旗標與十餘個 render 方法）。
    private TransferListPresenter transferList;
    // 底部待傳區（疊圖摘要 + 送出鈕 + 彈出清單）的單一擁有者（C3）。
    private SendStackPresenter sendStack;

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
        if (item == null || transferList == null || !transferList.isReceiveListActive()) return;
        viewModel.setReceivedUri(item.name, item.contentUri, item.mime);
        Uri uri = item.contentUri != null ? Uri.parse(item.contentUri) : null;
        transferList.updateReceivedThumbnail(item.name, uri, item.mime);
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
        ShareIntentReceiver.ingest(this, viewModel, getIntent());
    }

    private void bindViews() {
        beam        = findViewById(R.id.beam);
        tvHeadline  = findViewById(R.id.tvHeadline);
        tvSub       = findViewById(R.id.tvSub);
        tvPercent   = findViewById(R.id.tvPercent);
        tvPercentUnit= findViewById(R.id.tvPercentUnit);
        percentRow  = findViewById(R.id.percentRow);
        pickRow     = findViewById(R.id.pickRow);
        btnPickFiles= findViewById(R.id.btnPickFiles);
        btnPickMedia= findViewById(R.id.btnPickMedia);
        ibHistory   = findViewById(R.id.ibHistory);
        ibSettings  = findViewById(R.id.ibSettings);
        ivAvatar    = findViewById(R.id.ivAvatar);
        tvReceived     = findViewById(R.id.tvReceived);
        receivedBanner = findViewById(R.id.receivedBanner);

        ui = new TransferUiController(this, main, tvHeadline, tvSub, tvPercent, tvPercentUnit, percentRow);

        // 傳輸/接收清單方塊（rvTransfer）→ 交給單一擁有者（呈現狀態 + 渲染都在內）。
        transferList = new TransferListPresenter(findViewById(R.id.rvTransfer), viewModel, this::openFile);
        // 底部待傳區（疊圖 + 送出鈕 + 彈出清單）→ 單一擁有者，意圖（點列/點送出）以 callback 轉回本 Activity。
        sendStack = new SendStackPresenter(this, findViewById(R.id.sendStackRow),
                findViewById(R.id.stackThumbs), findViewById(R.id.tvStackLabel),
                findViewById(R.id.btnSend), viewModel, this::openFile, this::doSend);

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
            transferList.collapseIfSendPending();
            sendStack.rebuild();
            sendStack.updateButton();
        });
        viewModel.getReceivedCount().observe(this, count -> {
            if (count != null && count > 0) showReceivedBanner(count);
            else hideReceivedBanner();
        });
        // 「本次接收」sheet 垃圾桶清掉該批次 → 同步清掉 live 接收清單/橫幅（count→0 會自動隱藏橫幅）。
        getSupportFragmentManager().setFragmentResultListener(
                HistorySheet.RESULT_BATCH_CLEARED, this, (key, bundle) -> viewModel.clearReceivedList());
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
        ShareIntentReceiver.ingest(this, viewModel, intent);
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
                resetSessionState();   // 真正回到閒置 → 清空 VM 持久狀態（重建走的是 onCreate→renderReady，不清）
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
                    transferList.restoreIfAny();
                    viewModel.setSending(false);
                    sendStack.updateButton();
                    sendStack.rebuild();
                    if (viewModel.isPendingCardSend()) {
                        viewModel.setPendingCardSend(false);
                        main.post(this::sendMyProfileCard);
                    }
                    if (!viewModel.isSelectionEmpty()) {
                        main.post(this::doSend);
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
    // 純視覺化、可重入：onCreate（含切換深淺色/背景返回的 Activity 重建）也會呼叫，故「不」清空
    // ViewModel 的持久狀態（接收清單/慶祝旗標/送出中）——那屬於真正回到 IDLE 的語意，於 onSession
    // 的 IDLE/CANCELLED 處理。否則重建時這裡會把待還原的接收清單清掉，replay 只補回最後一檔 → 剩 1 項。
    private void renderReady() {
        beam.setPhase(BeamStageView.READY);
        ui.showPeerIdentity(beam, null, null,
                ProfileStore.get(this).name(), ProfileStore.get(this).loadAvatar());
        ui.showHeadlineText(getString(R.string.home_ready_title),
                getString(R.string.home_ready_sub));
        // 收合傳輸中列表方塊、顯示挑檔列（接收清單若仍有資料，連線 replay 後由 restoreIfAny 還原）。
        transferList.reset();
        pickRow.setVisibility(View.VISIBLE);
        hideReceivedBanner();
        sendStack.updateButton();
        sendStack.rebuild();
    }

    /** 真正回到閒置：清空接收清單/慶祝旗標/送出中等 ViewModel 持久狀態（與單純的畫面重建區隔）。 */
    private void resetSessionState() {
        viewModel.clearReceivedList();   // 內含 resetReceived（橫幅計數歸零）
        viewModel.resetCelebration();
        viewModel.setSending(false);
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
        // 進入傳輸版面的第一幀重置自動捲動（之後每幀都會走到這，但只重置一次）。
        transferList.beginTransferFrame();
        enterTransferUi(outgoing);
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
            beam.setProgress(viewModel.batchProgress(tp));
            if (outgoing) transferList.updateOutgoingProgress(tp, true);
            else transferList.showReceiveList(tp, true);
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
        String peer = binder != null ? binder.connectedPeerName() : null;
        String who = peer != null ? peer : getString(R.string.peer_fallback);
        ui.showHeadlineText(getString(R.string.transfer_done),
                outgoing ? getString(R.string.sent_to, who) : getString(R.string.received_from, who));

        if (isVcard) {
            // 名片獨立傳送，不影響待傳清單
        } else if (outgoing) {
            // 整批傳完 → 清空待傳清單與送出狀態（會透過 selection LiveData 重建 UI）
            viewModel.onBatchSent();
            sendStack.rebuild();
            sendStack.dismissSheet();
        } else if (tp != null) {
            transferList.showReceiveList(tp, true);
            transferList.collapseIfSendPending(); // 有待傳項目 → 直接收合為橫幅，不讓整張清單擠壓 NFC
        }

        // 短暫顯示完成後回到「已連線」可再選
        main.postDelayed(() -> {
            SessionState.Phase last = viewModel.lastPhase();
            if ((last == SessionState.Phase.ALL_DONE || last == SessionState.Phase.FILE_DONE)
                    && viewModel.isConnected()) {
                beam.setPhase(BeamStageView.CONNECTED);
            }
        }, 1400);
        sendStack.updateButton();
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
        sendStack.updateButton();
        haptic();
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

    /** 進入「傳輸中」版面：只留速度＋列表方塊，收起挑檔/送出/待傳與（已停用的）接收橫幅。 */
    private void enterTransferUi(boolean outgoing) {
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
