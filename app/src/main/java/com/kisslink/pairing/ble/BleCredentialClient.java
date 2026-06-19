package com.kisslink.pairing.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.NFCCredential;
import com.kisslink.pairing.PairingToken;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * BLE 憑證側通道——<b>central / GATT client</b> 端(NFC 中「讀到對方」= reader 的一方)。
 *
 * <p>以對方 nonce 作 ScanFilter 精準鎖定 peripheral,連線後:
 * <ol>
 *   <li>把<b>自己的</b> token 寫進 {@link BleConstants#CHAR_PEER_TOKEN}(讓對方湊齊兩份 token)。</li>
 *   <li>開啟 {@link BleConstants#CHAR_CREDENTIAL} 的 notify。</li>
 *   <li>就緒({@link Callback#onReady}):接著由協調器依 GO 選舉結果決定
 *       {@link #publishCredential}(本機是 GO)或等待 notify({@link Callback#onCredentialReceived})。</li>
 * </ol>
 */
public class BleCredentialClient {

    private static final String TAG = "BleCredentialClient";

    public interface Callback {
        /** 已連線、自己的 token 已送達、notify 已開啟。 */
        void onReady();
        void onCredentialReceived(@NonNull GroupCredential credential);
        void onError(@NonNull String message);
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private PairingToken localToken;
    @Nullable private BluetoothLeScanner scanner;
    @Nullable private BluetoothGatt gatt;
    @Nullable private BluetoothGattCharacteristic credentialChar;
    @Nullable private BluetoothGattCharacteristic peerTokenChar;
    private boolean scanning = false;
    private final Runnable timeoutRunnable;

    // ── GATT 連線重試：吸收 Android BLE 常見的暫時性 status 133（密集重碰下高發），
    //    避免單次失敗就乾等 coordinator 看門狗；失敗的 gatt 務必 close 以免 client 介面堆疊洩漏。
    @Nullable private BluetoothDevice peerDevice;
    private int  gattAttempts = 0;
    private boolean servicesDiscovered = false;
    private volatile boolean ready = false;   // token 交握完成（onReady 已發）
    private static final int  MAX_GATT_ATTEMPTS = 3;
    private static final long GATT_RETRY_BACKOFF_MS = 400L;

    // 非 GO 端的憑證讀取後備:notify 可能因 notify-before-subscribe race 或丟包而沒收到,
    // 改主動 READ credentialChar(GO 在 notify 前已 setValue)。雙保險,杜絕「卡在連線逾時」。
    private boolean credentialDelivered = false;
    @Nullable private Runnable credentialReadPoll;
    private volatile boolean stopped = false;

    public BleCredentialClient(@NonNull Context context, @NonNull Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.timeoutRunnable = () -> callback.onError("BLE 連線逾時，請重試");
    }

    @SuppressLint("MissingPermission")
    public void start(@NonNull byte[] peerNonce, @NonNull PairingToken localToken) {
        this.localToken = localToken;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            callback.onError("請先開啟藍牙");
            return;
        }
        scanner = bm.getAdapter().getBluetoothLeScanner();
        if (scanner == null) { callback.onError("此裝置不支援 BLE 掃描"); return; }

        ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(BleConstants.MANUFACTURER_ID, peerNonce)
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        Log.i(TAG, "Scanning for peer nonce…");
        main.postDelayed(timeoutRunnable, 15000);
    }

    /** 本機當選 GO:把憑證 write 給 peripheral。 */
    @SuppressLint({"MissingPermission", "deprecation"})
    public void publishCredential(@NonNull GroupCredential cred) {
        if (gatt == null || credentialChar == null) {
            Log.w(TAG, "publishCredential: not ready");
            return;
        }
        byte[] bytes = NFCCredential.toBytes(cred);
        credentialChar.setValue(bytes);
        credentialChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gatt.writeCharacteristic(credentialChar);
        Log.i(TAG, "Credential written to peripheral (" + bytes.length + " bytes)");
    }

    /**
     * 非 GO 端在 onReady(已連上、CCCD 已開)後呼叫:啟動憑證讀取後備。
     * 若 GO 的 notify 沒到(notify-before-subscribe race / 丟包),改週期性 READ credentialChar，
     * GO 一旦 publishCredential 設好值即讀得到 → onCredentialReceived。GO 端本機不需呼叫。
     */
    @SuppressLint("MissingPermission")
    public void startCredentialReadFallback() {
        if (credentialDelivered || credentialReadPoll != null) return;
        credentialReadPoll = new Runnable() {
            int attempts = 0;
            @Override public void run() {
                if (credentialDelivered || gatt == null || credentialChar == null) return;
                if (attempts++ >= 15) { credentialReadPoll = null; return; } // ~9s 後交給 coordinator watchdog
                try { gatt.readCharacteristic(credentialChar); } catch (Exception ignored) {}
                main.postDelayed(this, 600);
            }
        };
        main.postDelayed(credentialReadPoll, 500); // 先給 notify 一點時間
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        stopped = true;
        main.removeCallbacks(timeoutRunnable);
        if (credentialReadPoll != null) { main.removeCallbacks(credentialReadPoll); credentialReadPoll = null; }
        try { if (scanning && scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
        scanning = false;
        scanner = null;
        gatt = null;
        credentialChar = null;
        peerTokenChar = null;
        Log.d(TAG, "BLE client stopped");
    }

    // ══════════════════════════════════════════════════════════
    //  掃描
    // ══════════════════════════════════════════════════════════

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) return;
            scanning = false;
            try { if (scanner != null) scanner.stopScan(this); } catch (Exception ignored) {}
            peerDevice = result.getDevice();
            connectGatt();
        }

        @Override public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed: " + errorCode);
            main.post(() -> callback.onError("BLE 掃描失敗 (" + errorCode + ")"));
        }
    };

    /** 連 GATT（可重試）：每次都用新的 connectGatt，前一次失敗的 gatt 已在 onConnectionStateChange close。 */
    @SuppressLint("MissingPermission")
    private void connectGatt() {
        if (stopped || peerDevice == null) return;
        gattAttempts++;
        Log.i(TAG, "Peer found, connecting GATT (attempt " + gattAttempts + ")…");
        gatt = peerDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    // ══════════════════════════════════════════════════════════
    //  GATT
    // ══════════════════════════════════════════════════════════

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // BLE 已連上 → 取消逾時。後續 token/憑證走 live link(快),
                // Wi-Fi 建群有 WifiDirectManager 自己的逾時保護,不該再算進 BLE 額度。
                main.removeCallbacks(timeoutRunnable);
                Log.d(TAG, "GATT connected → discovering services");
                g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                // 連線失敗(常見 status 133)或交握完成前掉線。務必 close 釋放 client 介面,
                // 否則密集重碰會讓失敗的 GATT 物件堆疊洩漏、BLE 越來越不穩。
                Log.w(TAG, "GATT disconnected (status=" + status + ")");
                try { g.close(); } catch (Exception ignored) {}
                if (gatt == g) gatt = null;
                if (ready || credentialDelivered) return;          // 交握已過,正常收尾,不介入
                if (!servicesDiscovered && gattAttempts < MAX_GATT_ATTEMPTS) {
                    main.postDelayed(BleCredentialClient.this::connectGatt, GATT_RETRY_BACKOFF_MS); // 早期 133 → 自動重試
                } else {
                    main.post(() -> callback.onError("BLE 連線失敗，請再碰一下重試"));               // 重試用盡或交握中掉線
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS || g.getService(BleConstants.SERVICE_UUID) == null) {
                main.post(() -> callback.onError("找不到 KissLink BLE 服務"));
                return;
            }
            servicesDiscovered = true; // 進入交握階段：之後的掉線不再當作可重試的早期 133
            peerTokenChar  = g.getService(BleConstants.SERVICE_UUID).getCharacteristic(BleConstants.CHAR_PEER_TOKEN);
            credentialChar = g.getService(BleConstants.SERVICE_UUID).getCharacteristic(BleConstants.CHAR_CREDENTIAL);
            if (peerTokenChar == null || credentialChar == null) {
                main.post(() -> callback.onError("BLE 特徵值缺失"));
                return;
            }
            // 先把 MTU 拉大:token(~90B)與憑證(~100B)都超過預設 23-byte MTU。
            g.requestMtu(247);
        }

        @SuppressLint({"MissingPermission", "deprecation"})
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.d(TAG, "MTU = " + mtu + " → writing own token");
            // 1) 寫自己的 token 給 peripheral
            peerTokenChar.setValue(localToken.toUri().getBytes(StandardCharsets.UTF_8));
            peerTokenChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            g.writeCharacteristic(peerTokenChar);
        }

        @SuppressLint({"MissingPermission", "deprecation"})
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (ch == null) return;
            if (BleConstants.CHAR_PEER_TOKEN.equals(ch.getUuid())) {
                // 2) token 已送達 → 開啟 credential 的 notify
                g.setCharacteristicNotification(credentialChar, true);
                BluetoothGattDescriptor cccd = credentialChar.getDescriptor(BleConstants.CCCD);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                } else {
                    ready = true;
                    main.post(callback::onReady);
                }
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (d != null && BleConstants.CCCD.equals(d.getUuid())) {
                // 3) notify 開啟 → 就緒
                ready = true;
                main.post(callback::onReady);
            }
        }

        @SuppressLint("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (ch != null && BleConstants.CHAR_CREDENTIAL.equals(ch.getUuid())) {
                deliverCredential(parseCredential(ch.getValue())); // notify 路徑
            }
        }

        @SuppressLint("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (ch != null && BleConstants.CHAR_CREDENTIAL.equals(ch.getUuid())
                    && status == BluetoothGatt.GATT_SUCCESS) {
                deliverCredential(parseCredential(ch.getValue())); // 讀取後備路徑
            }
        }
    };

    /** 憑證只交付一次(notify 與 read 後備擇一先到者),並停掉讀取輪詢。 */
    private void deliverCredential(@Nullable GroupCredential cred) {
        if (cred == null || credentialDelivered) return;
        credentialDelivered = true;
        if (credentialReadPoll != null) { main.removeCallbacks(credentialReadPoll); credentialReadPoll = null; }
        main.post(() -> callback.onCredentialReceived(cred));
    }

    @Nullable
    private static GroupCredential parseCredential(@Nullable byte[] value) {
        if (value == null) return null;
        try { return NFCCredential.fromBytes(value); }
        catch (Exception e) { return null; }
    }
}
