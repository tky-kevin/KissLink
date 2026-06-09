package com.kisslink.ui.pairing;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.kisslink.R;
import com.kisslink.pairing.NfcPairingController;
import com.kisslink.pairing.PairingToken;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SessionState;
import com.kisslink.ui.transfer.TransferActivity;

/**
 * 碰觸配對畫面——主控 NFC reader/HCE 快速切換({@link NfcPairingController}),
 * latch 後把結果經 binder 餵給 Service 的 PairingCoordinator,連上後跳 {@link TransferActivity}。
 *
 * <p>兩種進入方式:
 * <ul>
 *   <li>一般:使用者開此畫面 → 開始切換,等對方碰。</li>
 *   <li>冷啟動:對方 HCE 的 deep link 啟動本 App({@link #EXTRA_PEER_TOKEN}),
 *       本機已知對方 token → 直接以 reader 身分接手,不需切換。</li>
 * </ul>
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class PairingActivity extends AppCompatActivity {

    private static final String EXTRA_PEER_TOKEN = "peer_token_uri";

    private TextView tvRole, tvStatus, tvHint;
    private Button   btnCancel;

    @Nullable private FileTransferService.TransferBinder binder;
    @Nullable private NfcPairingController nfc;
    @Nullable private PairingToken coldLaunchPeer;

    private boolean bound = false;
    private boolean navigating = false;
    private boolean resumed = false;
    private boolean fromNfcTag = false;

    // ── Factory ────────────────────────────────────────────────

    public static Intent newIntent(Context ctx) {
        return new Intent(ctx, PairingActivity.class);
    }

    /** 冷啟動:帶著對方 token,直接以 reader 身分接手。 */
    public static Intent newIntentForColdLaunch(Context ctx, PairingToken peer) {
        return new Intent(ctx, PairingActivity.class)
                .putExtra(EXTRA_PEER_TOKEN, peer.toUri());
    }

    // ── 生命週期 ────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);
        
        Intent i = getIntent();
        if (i != null) {
            String peerUri = i.getStringExtra(EXTRA_PEER_TOKEN);
            if (peerUri != null) coldLaunchPeer = PairingToken.fromUri(Uri.parse(peerUri));
            fromNfcTag = i.getBooleanExtra("from_nfc_tag", false);
        }

        tvRole    = findViewById(R.id.tvRole);
        tvStatus  = findViewById(R.id.tvStatus);
        tvHint    = findViewById(R.id.tvHint);
        btnCancel = findViewById(R.id.btnCancel);

        tvRole.setText("碰一下配對");
        tvHint.setText("把兩台手機背面輕碰");
        btnCancel.setOnClickListener(v -> { stopSessionService(); finish(); });

        Intent svc = FileTransferService.intent(this);
        startForegroundService(svc);
        bindService(svc, connection, BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        enableNfcIfReady();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (nfc != null) nfc.handleIntent(intent);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (nfc != null) nfc.disable();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (bound) { unbindService(connection); bound = false; }
        // 不在此停服務:配對流程中 Activity 可能反覆重建,若在此 stopService 會把進行中的
        // 配對/連線連同 WifiDirectManager 一起砍掉再重建,製造殘留狀態與重疊 coordinator。
        // 服務改由「明確按取消」或「App 從最近清單滑掉(onTaskRemoved)」才結束。
    }

    // ── Service 綁定 ────────────────────────────────────────────

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (FileTransferService.TransferBinder) service;
            bound = true;

            // 先處理 latch:coldLaunch/fromNfcTag 經 Service.prepareForLatch 會把殘留連線徹底重置,
            // 再註冊觀察者——避免觀察者一註冊就讀到「上一場殘留的 CONNECTED」而誤跳回傳輸畫面。
            if (coldLaunchPeer != null) {
                // 本機是 reader,已知對方 token → 直接接手。
                tvStatus.setText("連線中…");
                binder.onNfcLatchedAsReader(coldLaunchPeer);
            } else if (fromNfcTag) {
                // 本機是 tag，已在其他 Activity 被讀取過 → 直接以 tag 身份接手。
                tvStatus.setText("連線中…");
                binder.onNfcLatchedAsTag();
                fromNfcTag = false; // 避免重複觸發
            } else {
                enableNfcIfReady();
            }

            binder.getSessionState().observe(PairingActivity.this, st -> {
                updateStatus(st);
                if (st.isTransferStartedOrConnected()) goToTransfer();
                else if (st.isError() && st.error != null) {
                    showError(st.error);
                    if (nfc != null) nfc.resetLatched();
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false; binder = null;
        }
    };

    // ── NFC 切換 ────────────────────────────────────────────────

    private void ensureController() {
        if (nfc != null) return;
        nfc = new NfcPairingController(this, new NfcPairingController.Callback() {
            @Override public void onPeerToken(@NonNull PairingToken peer) {
                tvStatus.setText("已碰到,連線中…");
                if (binder != null) binder.onNfcLatchedAsReader(peer);
            }
            @Override public void onTagRead() {
                tvStatus.setText("已碰到,連線中…");
                if (binder != null) binder.onNfcLatchedAsTag();
            }
            @Override public void onError(@NonNull String message) { showError(message); }
        });
    }

    private void enableNfcIfReady() {
        if (navigating || coldLaunchPeer != null) return;
        if (!bound || binder == null || !resumed) return;
        ensureController();
        nfc.setLocalToken(binder.localToken());
        nfc.enable();
        nfc.handleIntent(getIntent());
        tvStatus.setText("等待碰觸…");
    }

    // ── UI ──────────────────────────────────────────────────────

    private void updateStatus(SessionState st) {
        switch (st.phase) {
            case RESETTING:        tvStatus.setText("重置中…");              break;
            case PAIRING_LATCHED:  tvStatus.setText("已碰到…");              break;
            case PAIRING_LINKING:  tvStatus.setText("建立安全通道…");         break;
            case PAIRING_ELECTING: tvStatus.setText("協商中…");             break;
            case CREATING_GROUP:   tvStatus.setText("建立 Wi-Fi Direct…");   break;
            case HOSTING:          tvStatus.setText("等待對方…");            break;
            case CONNECTING:       tvStatus.setText("連線中…");              break;
            case CONNECTED:        tvStatus.setText("連線成功！");           break;
            case ERROR:            tvStatus.setText("配對失敗,請重試");      break;
            default: break;
        }
    }

    private void goToTransfer() {
        if (navigating) return;
        navigating = true;
        startActivity(new Intent(this, TransferActivity.class));
        finish();
    }

    private void stopSessionService() {
        stopService(new Intent(this, FileTransferService.class));
    }

    private void showError(String msg) {
        Snackbar.make(btnCancel, msg, Snackbar.LENGTH_LONG).show();
    }
}
