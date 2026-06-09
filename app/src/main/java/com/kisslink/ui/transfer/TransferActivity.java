package com.kisslink.ui.transfer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;
import com.kisslink.R;
import com.kisslink.nfc.NfcForegroundHelper;
import com.kisslink.ui.pairing.PairingActivity;
import com.kisslink.pairing.PairingToken;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;
import com.kisslink.transfer.TransferProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 傳輸主畫面(peer 雙向)——連上後雙方對等,任一端皆可選檔案送出、可多輪。
 *
 * <p>UI 先求簡單:狀態文字 + 進度條 +「選擇檔案傳送」。名片/照片選擇器與動畫留待後續。
 */
public class TransferActivity extends AppCompatActivity {

    // 舊角色常數保留(其他呼叫端相容);peer 模型下不再區分。
    public static final String ROLE_SENDER   = "SENDER";
    public static final String ROLE_RECEIVER = "RECEIVER";

    private TextView    tvPhase, tvFileName, tvSpeed, tvEta, tvPercent;
    private ProgressBar progressBar;
    private Button      btnSend, btnAddFiles;
    private View        rvFiles, receiverPanel;
    private NfcForegroundHelper nfcHelper;

    private TransferViewModel viewModel;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                List<SendItem> items = new ArrayList<>();
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    items.add(SendItem.fromUri(getContentResolver(), uri, TransferProtocol.ITEM_FILE));
                }
                viewModel.sendItems(items);
                Snackbar.make(progressBar, "開始傳送 " + items.size() + " 個檔案", Snackbar.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        tvPhase     = findViewById(R.id.tvPhase);
        tvFileName  = findViewById(R.id.tvFileName);
        tvSpeed     = findViewById(R.id.tvSpeed);
        tvEta       = findViewById(R.id.tvEta);
        tvPercent   = findViewById(R.id.tvPercent);
        progressBar = findViewById(R.id.progressBar);
        btnSend     = findViewById(R.id.btnSend);
        btnAddFiles = findViewById(R.id.btnAddFiles);
        rvFiles       = findViewById(R.id.rvFiles);
        receiverPanel = findViewById(R.id.receiverPanel);

        rvFiles.setVisibility(View.GONE);
        receiverPanel.setVisibility(View.GONE);
        btnAddFiles.setVisibility(View.GONE);
        btnSend.setText("選擇檔案傳送");
        btnSend.setEnabled(false);
        btnSend.setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));

        tvPhase.setText("連線中…");

        viewModel = new ViewModelProvider(this).get(TransferViewModel.class);
        viewModel.getState().observe(this, this::onSession);

        // 傳輸中不打擾;否則(已斷線/閒置)觸碰 → 轉到配對畫面。是否重置由 Service 在 latch 當下決定:
        // 連線存活 → 配對畫面會直接帶回此畫面;已死 → latch 時重置成新場重連。
        nfcHelper = new NfcForegroundHelper(this, new NfcForegroundHelper.Callback() {
            @Override public void onPeerToken(@NonNull PairingToken peer) {
                if (isTransferring()) return;
                startActivity(PairingActivity.newIntentForColdLaunch(TransferActivity.this, peer));
                finish();
            }
            @Override public void onTagRead() {
                if (isTransferring()) return;
                Intent intent = new Intent(TransferActivity.this, PairingActivity.class);
                intent.putExtra("from_nfc_tag", true);
                startActivity(intent);
                finish();
            }
        });
    }

    private boolean isTransferring() {
        SessionState st = viewModel.getState().getValue();
        return st != null && st.phase == SessionState.Phase.TRANSFERRING;
    }

    @Override protected void onStart() { super.onStart(); viewModel.bindService(this); }
    @Override protected void onStop()  { super.onStop();  viewModel.unbindService(this); }

    @Override
    protected void onResume() {
        super.onResume();
        nfcHelper.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfcHelper.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        nfcHelper.handleIntent(intent);
    }

    private void onSession(SessionState st) {
        switch (st.phase) {
            case RESETTING:
                tvPhase.setText("重置中…");
                btnSend.setEnabled(false);
                break;

            case PAIRING_LATCHED:
            case PAIRING_LINKING:
            case PAIRING_ELECTING:
            case CREATING_GROUP:
            case HOSTING:
            case CONNECTING:
                tvPhase.setText("連線中…");
                btnSend.setEnabled(false);
                break;

            case CONNECTED:
                tvPhase.setText("已連線 — 可選擇檔案互傳");
                btnSend.setEnabled(true);
                break;

            case TRANSFERRING: {
                TransferProgress p = st.progress;
                if (p == null) break;
                tvPhase.setText("傳輸中");
                tvFileName.setText(p.fileName);
                tvFileName.setVisibility(View.VISIBLE);
                tvSpeed.setText(p.speedLabel());
                tvEta.setText(p.etaLabel());
                int pct = p.percentInt();
                progressBar.setProgress(pct >= 0 ? pct : 0);
                tvPercent.setText(pct >= 0 ? pct + "%" : "—");
                btnSend.setEnabled(true);
                break;
            }

            case FILE_DONE: {
                TransferProgress p = st.progress;
                tvPhase.setText("「" + (p != null ? p.fileName : "") + "」完成");
                progressBar.setProgress(100);
                tvPercent.setText("100%");
                break;
            }

            case ERROR:
                tvPhase.setText("錯誤：" + (st.error != null ? st.error : ""));
                if (st.error != null) Snackbar.make(progressBar, st.error, Snackbar.LENGTH_LONG).show();
                break;

            default:
                break;
        }
    }
}
