package com.kisslink.ui.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.pairing.PairingToken;
import com.kisslink.ui.pairing.PairingActivity;
import com.kisslink.util.PermissionHelper;

/**
 * 主畫面。
 *
 * <h3>新流程(A+B2 + peer 雙向)</h3>
 * <pre>
 *   點「碰一下配對」→ PairingActivity(NFC 快速切換)→ 一貼即連 → TransferActivity(互傳)
 *   或:對方 App 未開時,被對方 HCE 的 deep link 冷啟動 → 直接以 reader 接手連線
 * </pre>
 * 連上後雙方對等,於 TransferActivity 選檔案/名片/照片互傳。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class MainActivity extends AppCompatActivity {

    private MainViewModel  viewModel;
    private HistoryAdapter historyAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        Button btnPair    = findViewById(R.id.btnSend);
        Button btnReceive = findViewById(R.id.btnReceive);
        btnPair.setText("碰一下配對");
        btnPair.setOnClickListener(v -> openPairing());
        btnReceive.setVisibility(View.GONE);

        RecyclerView rvHistory = findViewById(R.id.rvHistory);
        historyAdapter = new HistoryAdapter(id -> viewModel.deleteRecord(id));
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(historyAdapter);
        viewModel.getRecentRecords().observe(this, records -> historyAdapter.submitList(records));

        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
        }

        handlePairingDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingDeepLink(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.allGranted(grantResults)) {
            Toast.makeText(this, "需要 Wi-Fi、藍牙與檔案權限才能使用", Toast.LENGTH_LONG).show();
        }
    }

    private void openPairing() {
        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
            return;
        }
        startActivity(PairingActivity.newIntent(this));
    }

    /** 碰觸冷啟動:對方 HCE 的 kisslink://pair deep link 啟動本 App → 以 reader 接手。 */
    private void handlePairingDeepLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        PairingToken token = PairingToken.fromUri(data);
        if (token != null) {
            Log.i("MainActivity", "Pairing deep link → cold-launch reader: " + token);
            startActivity(PairingActivity.newIntentForColdLaunch(this, token));
        }
    }
}
