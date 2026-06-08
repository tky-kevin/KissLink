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

    public BleCredentialClient(@NonNull Context context, @NonNull Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
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

    @SuppressLint("MissingPermission")
    public void stop() {
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
            BluetoothDevice device = result.getDevice();
            Log.i(TAG, "Peer found, connecting GATT…");
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        }

        @Override public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed: " + errorCode);
            main.post(() -> callback.onError("BLE 掃描失敗 (" + errorCode + ")"));
        }
    };

    // ══════════════════════════════════════════════════════════
    //  GATT
    // ══════════════════════════════════════════════════════════

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected → discovering services");
                g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.w(TAG, "GATT disconnected");
            }
        }

        @SuppressLint("MissingPermission")
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS || g.getService(BleConstants.SERVICE_UUID) == null) {
                main.post(() -> callback.onError("找不到 KissLink BLE 服務"));
                return;
            }
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
                    main.post(callback::onReady);
                }
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (d != null && BleConstants.CCCD.equals(d.getUuid())) {
                // 3) notify 開啟 → 就緒
                main.post(callback::onReady);
            }
        }

        @SuppressLint("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (ch != null && BleConstants.CHAR_CREDENTIAL.equals(ch.getUuid())) {
                GroupCredential cred = parseCredential(ch.getValue());
                if (cred != null) main.post(() -> callback.onCredentialReceived(cred));
            }
        }
    };

    @Nullable
    private static GroupCredential parseCredential(@Nullable byte[] value) {
        if (value == null) return null;
        try { return NFCCredential.fromBytes(value); }
        catch (Exception e) { return null; }
    }
}
