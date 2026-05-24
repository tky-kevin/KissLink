package com.nfctransfer.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nfctransfer.app.data.HistoryRepository;
import com.nfctransfer.app.data.TransferRecord;
import com.nfctransfer.app.transfer.FileTransferClient;
import com.nfctransfer.app.transfer.TransferNotificationHelper;
import com.nfctransfer.app.util.PermissionHelper;
import com.nfctransfer.app.wifi.WifiDirectManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sender side:
 * 1. User picks files.
 * 2. Taps "開始傳送" — enables NFC foreground dispatch and shows hint.
 * 3. Taps NFC against receiver — reads SELECT AID + READ APDU to get JSON credentials.
 * 4. Calls WifiDirectManager.connectToGroup(); on success starts FileTransferClient.
 */
public class SendActivity extends AppCompatActivity {

    private static final String TAG = "SendActivity";

    // SELECT AID APDU: 00 A4 04 00 08 F0 4E 46 43 54 52 01 01 00
    private static final byte[] SELECT_AID_APDU = {
            (byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00,
            (byte) 0x08,
            (byte) 0xF0, (byte) 0x4E, (byte) 0x46, (byte) 0x43,
            (byte) 0x54, (byte) 0x52, (byte) 0x01, (byte) 0x01,
            (byte) 0x00
    };

    // READ APDU: 00 B0 00 00 00
    private static final byte[] READ_APDU = {
            (byte) 0x00, (byte) 0xB0, (byte) 0x00, (byte) 0x00, (byte) 0x00
    };

    private Button btnPickFile;
    private Button btnStartSend;
    private ProgressBar progressSend;
    private RecyclerView rvFiles;
    private TextView tvNfcHint;

    private final List<Uri> selectedUris = new ArrayList<>();
    private WifiDirectManager wifiDirectManager;
    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private boolean waitingForNfc = false;

    private final ExecutorService nfcExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    selectedUris.clear();
                    selectedUris.addAll(uris);
                    btnStartSend.setEnabled(true);
                    Toast.makeText(this, "已選擇 " + uris.size() + " 個檔案", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send);

        btnPickFile  = findViewById(R.id.btn_pick_file);
        btnStartSend = findViewById(R.id.btn_start_send);
        progressSend = findViewById(R.id.progress_send);
        rvFiles      = findViewById(R.id.rv_files);
        tvNfcHint    = findViewById(R.id.tv_nfc_hint);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));

        wifiDirectManager = WifiDirectManager.getInstance(this);

        TransferNotificationHelper.createNotificationChannel(this);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        nfcPendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE);

        btnPickFile.setOnClickListener(v ->
                filePickerLauncher.launch(new String[]{"*/*"}));

        btnStartSend.setOnClickListener(v -> startSendFlow());
    }

    @Override
    protected void onResume() {
        super.onResume();
        wifiDirectManager.registerReceiver(this);
        if (waitingForNfc && nfcAdapter != null) {
            enableNfcForegroundDispatch();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiDirectManager.unregisterReceiver(this);
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nfcExecutor.shutdownNow();
        wifiDirectManager.disconnect();
    }

    // -------------------------------------------------------------------------
    // Send flow
    // -------------------------------------------------------------------------

    private void startSendFlow() {
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, "請先選擇檔案", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!PermissionHelper.isNfcEnabled(this)) {
            PermissionHelper.showNfcEnableDialog(this);
            return;
        }

        List<String> missing = PermissionHelper.getMissingPermissions(this);
        if (!missing.isEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]), 300);
            return;
        }

        progressSend.setVisibility(View.VISIBLE);
        progressSend.setProgress(0);
        tvNfcHint.setText("請靠近接收方手機...");

        waitingForNfc = true;
        enableNfcForegroundDispatch();

        Toast.makeText(this, "請將裝置靠近接收方", Toast.LENGTH_LONG).show();
    }

    private void enableNfcForegroundDispatch() {
        if (nfcAdapter == null) return;
        IntentFilter isoDepFilter = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        IntentFilter tagFilter = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
        IntentFilter[] filters = new IntentFilter[]{isoDepFilter, tagFilter};
        String[][] techLists = new String[][]{new String[]{IsoDep.class.getName()}};
        nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, filters, techLists);
    }

    // -------------------------------------------------------------------------
    // NFC tag handling
    // -------------------------------------------------------------------------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!waitingForNfc) return;

        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            return;
        }

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;

        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            Toast.makeText(this, "不支援的 NFC 標籤類型", Toast.LENGTH_SHORT).show();
            return;
        }

        waitingForNfc = false;
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
        tvNfcHint.setText("正在讀取憑證...");

        nfcExecutor.execute(() -> readCredentialsAndConnect(isoDep));
    }

    private void readCredentialsAndConnect(IsoDep isoDep) {
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);

            // SELECT AID
            byte[] selectResponse = isoDep.transceive(SELECT_AID_APDU);
            if (!isSuccess(selectResponse)) {
                runOnUiThread(() -> showNfcError("SELECT AID 失敗"));
                return;
            }

            // READ credentials
            byte[] readResponse = isoDep.transceive(READ_APDU);
            if (!isSuccess(readResponse)) {
                runOnUiThread(() -> showNfcError("READ 指令失敗"));
                return;
            }

            // Strip trailing 90 00
            int payloadLen = readResponse.length - 2;
            if (payloadLen <= 0) {
                runOnUiThread(() -> showNfcError("回應資料為空"));
                return;
            }
            String json = new String(readResponse, 0, payloadLen, java.nio.charset.StandardCharsets.UTF_8);
            Log.d(TAG, "NFC JSON: " + json);

            JSONObject obj = new JSONObject(json);
            String ssid = obj.getString("ssid");
            String pass = obj.getString("pass");

            runOnUiThread(() -> {
                tvNfcHint.setText("已取得憑證，連線中...");
                connectAndSend(ssid, pass);
            });

        } catch (IOException e) {
            Log.e(TAG, "NFC IO error", e);
            runOnUiThread(() -> showNfcError("NFC 通訊失敗: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "NFC error", e);
            runOnUiThread(() -> showNfcError("錯誤: " + e.getMessage()));
        } finally {
            try { isoDep.close(); } catch (IOException ignored) {}
        }
    }

    private boolean isSuccess(byte[] response) {
        if (response == null || response.length < 2) return false;
        return response[response.length - 2] == (byte) 0x90
                && response[response.length - 1] == (byte) 0x00;
    }

    private void showNfcError(String msg) {
        tvNfcHint.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        waitingForNfc = true;
        enableNfcForegroundDispatch();
    }

    // -------------------------------------------------------------------------
    // Wi-Fi Direct + file transfer
    // -------------------------------------------------------------------------

    private void connectAndSend(String ssid, String passphrase) {
        wifiDirectManager.connectToGroup(ssid, passphrase, new WifiDirectManager.ConnectionCallback() {
            @Override
            public void onConnected(String peerIpAddress) {
                Log.d(TAG, "Connected to group, peer IP=" + peerIpAddress);
                tvNfcHint.setText("已連線，傳送中...");
                sendFiles(peerIpAddress);
            }

            @Override
            public void onConnectionFailed(String reason) {
                tvNfcHint.setText("連線失敗: " + reason);
                Toast.makeText(SendActivity.this, "Wi-Fi Direct 連線失敗", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDisconnected() {
                tvNfcHint.setText("連線中斷");
            }
        });
    }

    private void sendFiles(String peerIpAddress) {
        final String ip = peerIpAddress;
        FileTransferClient client = new FileTransferClient();
        client.sendFiles(this, ip, selectedUris, new FileTransferClient.Callback() {
            @Override
            public void onProgressUpdate(String fileName, int percent) {
                progressSend.setProgress(percent);
                TransferNotificationHelper.showProgressNotification(
                        SendActivity.this, fileName, percent);
            }

            @Override
            public void onFileSent(String fileName) {
                Toast.makeText(SendActivity.this, "已傳送：" + fileName, Toast.LENGTH_SHORT).show();

                HistoryRepository repo = new HistoryRepository(SendActivity.this);
                repo.insert(new TransferRecord(fileName, 0, null,
                        System.currentTimeMillis(), "SENT", "SUCCESS", ip));
            }

            @Override
            public void onAllFilesSent(int totalCount) {
                tvNfcHint.setText("傳送完成！");
                progressSend.setProgress(100);
                TransferNotificationHelper.showCompletionNotification(
                        SendActivity.this, totalCount, true);
            }

            @Override
            public void onError(String fileName, Exception e) {
                tvNfcHint.setText("傳送失敗");
                Toast.makeText(SendActivity.this,
                        "傳送錯誤：" + e.getMessage(), Toast.LENGTH_LONG).show();
                TransferNotificationHelper.showErrorNotification(SendActivity.this, fileName);
            }
        });
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        return result;
    }
}
