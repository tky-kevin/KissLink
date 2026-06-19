package com.kisslink.pairing.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.NFCCredential;
import com.kisslink.pairing.PairingToken;

import java.nio.charset.StandardCharsets;

/**
 * BLE 憑證側通道——<b>peripheral / GATT server</b> 端(NFC 中「被讀」= tag 的一方)。
 *
 * <p>開廣告(manufacturer data 帶 8-byte nonce 供 central 鎖定),並開一個 GATT 服務:
 * <ul>
 *   <li>central 把自己的 token 寫進 {@link BleConstants#CHAR_PEER_TOKEN} → {@link Callback#onPeerToken}。</li>
 *   <li>若本機當選 GO:{@link #publishCredential} 透過 notify 把憑證送給 central。</li>
 *   <li>若對方當選 GO:對方 write 憑證進 {@link BleConstants#CHAR_CREDENTIAL} → {@link Callback#onCredentialReceived}。</li>
 * </ul>
 */
public class BleCredentialServer {

    private static final String TAG = "BleCredentialServer";

    public interface Callback {
        void onPeerToken(@NonNull PairingToken peer);
        void onCredentialReceived(@NonNull GroupCredential credential);
        void onError(@NonNull String message);
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable private BluetoothGattServer gattServer;
    @Nullable private BluetoothLeAdvertiser advertiser;
    @Nullable private BluetoothGattCharacteristic credentialChar;
    @Nullable private BluetoothDevice connectedDevice;
    private final Runnable timeoutRunnable;

    public BleCredentialServer(@NonNull Context context, @NonNull Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.timeoutRunnable = () -> callback.onError("BLE 連線逾時，請重試");
    }

    @SuppressLint("MissingPermission")
    public void start(@NonNull PairingToken localToken) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            callback.onError("請先開啟藍牙");
            return;
        }
        advertiser = bm.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) {
            callback.onError("此裝置不支援 BLE 廣播");
            return;
        }

        // GATT 服務
        gattServer = bm.openGattServer(context, gattServerCallback);
        if (gattServer == null) { callback.onError("無法開啟 GATT server"); return; }

        BluetoothGattService svc = new BluetoothGattService(
                BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic peerTokenChar = new BluetoothGattCharacteristic(
                BleConstants.CHAR_PEER_TOKEN,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        credentialChar = new BluetoothGattCharacteristic(
                BleConstants.CHAR_CREDENTIAL,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE);
        credentialChar.addDescriptor(new BluetoothGattDescriptor(
                BleConstants.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));

        svc.addCharacteristic(peerTokenChar);
        svc.addCharacteristic(credentialChar);
        gattServer.addService(svc);

        // 廣告：只放 nonce(manufacturer data),省 31-byte 額度
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(BleConstants.MANUFACTURER_ID, localToken.nonce)
                .build();
        advertiser.startAdvertising(settings, data, advertiseCallback);
        Log.i(TAG, "GATT server up + advertising nonce");
        main.postDelayed(timeoutRunnable, 15000);
    }

    /** 本機當選 GO:把憑證透過 notify 推給已連線的 central。 */
    @SuppressLint("MissingPermission")
    public void publishCredential(@NonNull GroupCredential cred) {
        if (gattServer == null || credentialChar == null || connectedDevice == null) {
            Log.w(TAG, "publishCredential: not ready (server/char/device null)");
            return;
        }
        byte[] bytes = NFCCredential.toBytes(cred);
        credentialChar.setValue(bytes);
        gattServer.notifyCharacteristicChanged(connectedDevice, credentialChar, false);
        Log.i(TAG, "Credential published via notify (" + bytes.length + " bytes)");
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        main.removeCallbacks(timeoutRunnable);
        try { if (advertiser != null) advertiser.stopAdvertising(advertiseCallback); } catch (Exception ignored) {}
        try { if (gattServer != null) gattServer.close(); } catch (Exception ignored) {}
        advertiser = null;
        gattServer = null;
        credentialChar = null;
        connectedDevice = null;
        Log.d(TAG, "BLE server stopped");
    }

    // ══════════════════════════════════════════════════════════
    //  回調
    // ══════════════════════════════════════════════════════════

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartFailure(int errorCode) {
            Log.e(TAG, "Advertise failed: " + errorCode);
            main.post(() -> callback.onError("BLE 廣播失敗 (" + errorCode + ")"));
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // central 已連上 → 取消逾時(BLE 已通,後續走 live link + Wi-Fi 自有逾時)。
                main.removeCallbacks(timeoutRunnable);
                connectedDevice = device;
                Log.d(TAG, "Central connected");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (device != null && device.equals(connectedDevice)) connectedDevice = null;
                Log.d(TAG, "Central disconnected");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            connectedDevice = device;
            if (BleConstants.CHAR_PEER_TOKEN.equals(characteristic.getUuid())) {
                PairingToken peer = parseToken(value);
                if (peer != null) main.post(() -> callback.onPeerToken(peer));
            } else if (BleConstants.CHAR_CREDENTIAL.equals(characteristic.getUuid())) {
                GroupCredential cred = parseCredential(value);
                if (cred != null) main.post(() -> callback.onCredentialReceived(cred));
            }
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            // CCCD：central 開啟 notify
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                int offset, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null) value = new byte[0];
            if (gattServer != null) {
                gattServer.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }
    };

    // ══════════════════════════════════════════════════════════
    //  序列化
    // ══════════════════════════════════════════════════════════

    @Nullable
    private static PairingToken parseToken(@Nullable byte[] value) {
        if (value == null) return null;
        return PairingToken.fromUri(Uri.parse(new String(value, StandardCharsets.UTF_8)));
    }

    @Nullable
    private static GroupCredential parseCredential(@Nullable byte[] value) {
        if (value == null) return null;
        try { return NFCCredential.fromBytes(value); }
        catch (Exception e) {
            // 解析失敗不該靜默：留下診斷，否則只會表現為「卡在連線逾時」難以定位。
            Log.w(TAG, "parseCredential failed (" + value.length + "B): " + e.getMessage());
            return null;
        }
    }
}
