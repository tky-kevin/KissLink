package com.nfctransfer.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.nfctransfer.app.data.HistoryRepository;
import com.nfctransfer.app.data.TransferRecord;
import com.nfctransfer.app.nfc.NfcHceService;
import com.nfctransfer.app.transfer.FileTransferServer;
import com.nfctransfer.app.transfer.TransferNotificationHelper;
import com.nfctransfer.app.util.PermissionHelper;
import com.nfctransfer.app.util.QrCodeHelper;
import com.nfctransfer.app.wifi.WifiDirectManager;

import java.util.List;

public class ReceiveActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 200;

    private TextView tvStatus;
    private TextView tvFileInfo;
    private TextView tvSsidDebug;
    private ProgressBar progressReceive;
    private Button btnStartReceive;
    private Button btnShowQr;
    private ImageView ivQrCode;

    private WifiDirectManager wifiDirectManager;
    private FileTransferServer fileTransferServer;
    private boolean receiving = false;

    /** Stored after group creation so QR toggle can reuse them without re-calling createGroup. */
    private String groupSsid;
    private String groupPassphrase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);

        tvStatus        = findViewById(R.id.tv_status);
        tvFileInfo      = findViewById(R.id.tv_file_info);
        tvSsidDebug     = findViewById(R.id.tv_ssid_debug);
        progressReceive = findViewById(R.id.progress_receive);
        btnStartReceive = findViewById(R.id.btn_start_receive);
        btnShowQr       = findViewById(R.id.btn_show_qr);
        ivQrCode        = findViewById(R.id.iv_qr_code);

        wifiDirectManager = WifiDirectManager.getInstance(this);

        TransferNotificationHelper.createNotificationChannel(this);

        btnStartReceive.setOnClickListener(v -> {
            if (!receiving) {
                checkPermissionsAndStart();
            } else {
                stopReceiveMode();
            }
        });

        btnShowQr.setOnClickListener(v -> toggleQrCode());
    }

    @Override
    protected void onResume() {
        super.onResume();
        wifiDirectManager.registerReceiver(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiDirectManager.unregisterReceiver(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopReceiveMode();
    }

    // -------------------------------------------------------------------------
    // Permission handling
    // -------------------------------------------------------------------------

    private void checkPermissionsAndStart() {
        List<String> missing = PermissionHelper.getMissingPermissions(this);
        if (missing.isEmpty()) {
            startReceiveMode();
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            List<String> stillMissing = PermissionHelper.getMissingPermissions(this);
            if (stillMissing.isEmpty()) {
                startReceiveMode();
            } else {
                Toast.makeText(this, "需要權限才能接收檔案", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Receive flow
    // -------------------------------------------------------------------------

    private void startReceiveMode() {
        receiving = true;
        btnStartReceive.setText("停止接收");
        tvStatus.setText("建立 Wi-Fi Direct 群組中...");

        wifiDirectManager.createGroup(new WifiDirectManager.GroupInfoCallback() {
            @Override
            public void onGroupCreated(String ssid, String passphrase) {
                groupSsid = ssid;
                groupPassphrase = passphrase;

                NfcHceService.setCredentials(ssid, passphrase);
                startService(new Intent(ReceiveActivity.this, NfcHceService.class));

                tvStatus.setText("等待 NFC 感應中...");
                tvSsidDebug.setVisibility(View.VISIBLE);
                tvSsidDebug.setText("SSID: " + ssid);

                startFileServer();
            }

            @Override
            public void onGroupCreationFailed(String reason) {
                tvStatus.setText("群組建立失敗: " + reason);
                receiving = false;
                btnStartReceive.setText("開始接收");
                Toast.makeText(ReceiveActivity.this, "Wi-Fi Direct 群組建立失敗", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startFileServer() {
        fileTransferServer = new FileTransferServer();
        fileTransferServer.start(this, new FileTransferServer.Callback() {
            @Override
            public void onProgressUpdate(String fileName, int percent) {
                tvStatus.setText(getString(R.string.status_receiving));
                tvFileInfo.setVisibility(View.VISIBLE);
                tvFileInfo.setText(fileName + "  " + percent + "%");
                progressReceive.setVisibility(View.VISIBLE);
                progressReceive.setProgress(percent);
                TransferNotificationHelper.showProgressNotification(
                        ReceiveActivity.this, fileName, percent);
            }

            @Override
            public void onFileReceived(String fileName, String filePath, long fileSize) {
                progressReceive.setProgress(100);
                Toast.makeText(ReceiveActivity.this,
                        "已儲存：" + filePath, Toast.LENGTH_LONG).show();

                HistoryRepository repo = new HistoryRepository(ReceiveActivity.this);
                repo.insert(new TransferRecord(fileName, fileSize, filePath,
                        System.currentTimeMillis(), "RECEIVED", "SUCCESS", null));
            }

            @Override
            public void onAllFilesReceived(int totalCount) {
                tvStatus.setText(getString(R.string.status_done));
                TransferNotificationHelper.showCompletionNotification(
                        ReceiveActivity.this, totalCount, false);
            }

            @Override
            public void onError(String fileName, Exception e) {
                tvStatus.setText(getString(R.string.status_error));
                Toast.makeText(ReceiveActivity.this,
                        "錯誤：" + e.getMessage(), Toast.LENGTH_LONG).show();
                TransferNotificationHelper.showErrorNotification(ReceiveActivity.this, fileName);
            }
        });
    }

    private void toggleQrCode() {
        if (ivQrCode.getVisibility() == View.VISIBLE) {
            ivQrCode.setVisibility(View.GONE);
            btnShowQr.setText(getString(R.string.btn_show_qr));
            return;
        }
        if (!receiving || groupSsid == null) {
            Toast.makeText(this, "請先開始接收", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap qr = QrCodeHelper.generateQrCode(groupSsid, groupPassphrase, 512);
        if (qr != null) {
            Glide.with(this).load(qr).into(ivQrCode);
            ivQrCode.setVisibility(View.VISIBLE);
            btnShowQr.setText("隱藏 QR Code");
        } else {
            Toast.makeText(this, "QR Code 生成失敗", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopReceiveMode() {
        if (!receiving) return;
        receiving = false;
        groupSsid = null;
        groupPassphrase = null;
        if (btnStartReceive != null) btnStartReceive.setText("開始接收");
        if (tvStatus != null) tvStatus.setText(getString(R.string.status_waiting));
        if (tvSsidDebug != null) tvSsidDebug.setVisibility(View.GONE);
        if (ivQrCode != null) ivQrCode.setVisibility(View.GONE);

        NfcHceService.clearCredentials();
        stopService(new Intent(this, NfcHceService.class));

        wifiDirectManager.removeGroup();

        if (fileTransferServer != null) {
            fileTransferServer.stop();
            fileTransferServer = null;
        }
    }
}
