package com.kisslink.ui.transfer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.kisslink.R;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;
import com.kisslink.ui.ThemeManager;

import java.util.ArrayList;

/**
 * 傳輸主畫面。
 *
 * <h3>SENDER 模式</h3>
 * <ul>
 *   <li>若從 {@link com.kisslink.ui.pairing.PairingActivity} 帶入了 URIs，
 *       連線成功後立即自動開始傳送（無需再按按鈕）。</li>
 *   <li>也可手動按「選擇更多檔案」追加，再按「開始傳送」。</li>
 * </ul>
 *
 * <h3>RECEIVER 模式</h3>
 * <ul>
 *   <li>顯示接收進度，檔案自動儲存至 {@code 下載/KissLink}。</li>
 * </ul>
 */
public class TransferActivity extends AppCompatActivity {

    public static final String ROLE_SENDER   = "SENDER";
    public static final String ROLE_RECEIVER = "RECEIVER";
    private static final String EXTRA_ROLE   = "role";
    private static final String EXTRA_URIS   = "uris";   // ArrayList<Uri>

    // ── Views ─────────────────────────────────────────────────
    private TextView    tvPhase, tvFileName, tvSpeed, tvEta, tvPercent;
    private ProgressBar progressBar;
    private RecyclerView rvFiles;
    private Button      btnAddFiles, btnSend;
    private View        receiverPanel;

    // ── ViewModel ─────────────────────────────────────────────
    private TransferViewModel viewModel;
    private String role;

    // ── 傳送方：待傳清單 ────────────────────────────────────────
    private final ArrayList<Uri> selectedUris = new ArrayList<>();
    private SelectedFileAdapter  fileAdapter;

    // ── SAF 追加選擇器（可在傳送中追加）───────────────────────────
    private final ActivityResultLauncher<String[]> addFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris == null || uris.isEmpty()) return;
                        for (Uri uri : uris) {
                            try {
                                getContentResolver().takePersistableUriPermission(
                                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException ignored) {}
                            selectedUris.add(uri);
                        }
                        fileAdapter.notifyDataSetChanged();
                        btnSend.setEnabled(!selectedUris.isEmpty());
                    });

    // ══════════════════════════════════════════════════════════
    //  Factory
    // ══════════════════════════════════════════════════════════

    /**
     * @param preloadedUris SENDER 模式下預先選好的檔案（可為 null）
     */
    public static Intent newIntent(Context ctx, String role,
                                   @Nullable ArrayList<Uri> preloadedUris) {
        Intent i = new Intent(ctx, TransferActivity.class)
                .putExtra(EXTRA_ROLE, role);
        if (preloadedUris != null && !preloadedUris.isEmpty()) {
            i.putParcelableArrayListExtra(EXTRA_URIS, preloadedUris);
        }
        return i;
    }

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);
        role = getIntent().getStringExtra(EXTRA_ROLE);

        bindViews();
        setupRoleUi();

        viewModel = new ViewModelProvider(this).get(TransferViewModel.class);
        viewModel.getState().observe(this, this::onSession);
    }

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.bindService(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.unbindService(this);
    }

    // ══════════════════════════════════════════════════════════
    //  UI 初始化
    // ══════════════════════════════════════════════════════════

    private void bindViews() {
        tvPhase      = findViewById(R.id.tvPhase);
        tvFileName   = findViewById(R.id.tvFileName);
        tvSpeed      = findViewById(R.id.tvSpeed);
        tvEta        = findViewById(R.id.tvEta);
        progressBar  = findViewById(R.id.progressBar);
        tvPercent    = findViewById(R.id.tvPercent);
        rvFiles      = findViewById(R.id.rvFiles);
        btnAddFiles  = findViewById(R.id.btnAddFiles);
        btnSend      = findViewById(R.id.btnSend);
        receiverPanel= findViewById(R.id.receiverPanel);
    }

    private void setupRoleUi() {
        if (ROLE_SENDER.equals(role)) {
            rvFiles.setVisibility(View.VISIBLE);
            btnAddFiles.setVisibility(View.VISIBLE);
            btnSend.setVisibility(View.VISIBLE);
            receiverPanel.setVisibility(View.GONE);

            fileAdapter = new SelectedFileAdapter(selectedUris, getContentResolver());
            rvFiles.setLayoutManager(new LinearLayoutManager(this));
            rvFiles.setAdapter(fileAdapter);

            // 載入從 MainActivity 選好的檔案
            ArrayList<Uri> preloaded = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
            if (preloaded != null && !preloaded.isEmpty()) {
                selectedUris.addAll(preloaded);
                fileAdapter.notifyDataSetChanged();
                tvPhase.setText("已選 " + preloaded.size() + " 個檔案，等待連線後自動傳送…");
                btnSend.setEnabled(false); // 等 CONNECTED 再觸發
            } else {
                tvPhase.setText("等待連線…（可先追加檔案）");
                btnSend.setEnabled(false);
            }

            btnAddFiles.setOnClickListener(v -> addFileLauncher.launch(new String[]{"*/*"}));

            btnSend.setOnClickListener(v -> {
                viewModel.sendFiles(new ArrayList<>(selectedUris));
                btnSend.setEnabled(false);
                btnAddFiles.setEnabled(false);
            });

        } else {
            // RECEIVER
            rvFiles.setVisibility(View.GONE);
            btnAddFiles.setVisibility(View.GONE);
            btnSend.setVisibility(View.GONE);
            receiverPanel.setVisibility(View.VISIBLE);
            tvPhase.setText("等待傳送方…");
        }
    }

    // ══════════════════════════════════════════════════════════
    //  進度回呼
    // ══════════════════════════════════════════════════════════

    private void onSession(SessionState st) {
        switch (st.phase) {

            case IDLE:
            case CREATING_GROUP:
            case HOSTING:
            case CONNECTING:
                tvPhase.setText("等待連線…");
                break;

            case CONNECTED:
                // 送檔由 Service 在握手完成後自動驅動（檔案已於 PairingActivity 交付），
                // 此處只更新畫面文字，不再從 UI 觸發傳送，避免重複送與時序耦合。
                tvPhase.setText(ROLE_SENDER.equals(role)
                        ? "連線成功，傳送中…" : "已連線，等待傳送方…");
                if (ROLE_SENDER.equals(role)) {
                    btnSend.setEnabled(false);
                    btnAddFiles.setEnabled(false);
                }
                break;

            case TRANSFERRING: {
                TransferProgress p = st.progress;
                if (p == null) break;
                tvPhase.setText("傳輸中 (" + (p.fileIndex + 1) + "/" + p.fileCount + ")");
                tvFileName.setText(p.fileName);
                tvFileName.setVisibility(View.VISIBLE);
                tvSpeed.setText(p.speedLabel());
                tvEta.setText(p.etaLabel());
                int pct = p.percentInt();
                progressBar.setProgress(pct >= 0 ? pct : 0);
                tvPercent.setText(pct >= 0 ? pct + "%" : "—");
                break;
            }

            case FILE_DONE: {
                TransferProgress p = st.progress;
                tvPhase.setText("「" + (p != null ? p.fileName : "") + "」完成");
                progressBar.setProgress(100);
                break;
            }

            case ALL_DONE: {
                TransferProgress p = st.progress;
                tvPhase.setText("全部完成（" + (p != null ? p.fileCount : 0) + " 個檔案）");
                progressBar.setProgress(100);
                tvSpeed.setText("");
                tvEta.setText("");
                Snackbar.make(progressBar, "傳輸完成！", Snackbar.LENGTH_LONG).show();
                break;
            }

            case CANCELLED:
                tvPhase.setText("已取消");
                break;

            case ERROR:
                tvPhase.setText("錯誤：" + (st.error != null ? st.error : ""));
                if (st.error != null) {
                    Snackbar.make(progressBar, st.error, Snackbar.LENGTH_LONG).show();
                }
                break;
        }
    }
}
