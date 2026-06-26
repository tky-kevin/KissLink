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

import com.kisslink.diag.FlightRecorder;
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

    /** PAIRSEQ 診斷：預設靜默，{@code adb shell setprop log.tag.BleCredentialClient DEBUG} 可叫出。 */
    private static void seq(String msg) {
        FlightRecorder.seq(TAG, msg);
    }

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
    @Nullable private String scanNonceB64;   // 正在掃描的對方 nonce(NFC 讀到的),供逾時診斷
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

    // 交握逾時：GATT 連上後到 onReady 之間（discover→MTU→寫 token→CCCD）任一步驟卡住
    // （BLE stack 偶發不回呼）→ 在預算內主動 close+重連，避免靜待外層 coordinator 看門狗或無限等待。
    private static final long HANDSHAKE_TIMEOUT_MS = 7000L;
    private final Runnable handshakeTimeout = this::onHandshakeStall;

    // 連線階段逾時：connectGatt 後若遲遲沒有 STATE_CONNECTED 回呼——Android BLE 已知病灶:密集重連時
    // connectGatt 可能「完全不回呼」(peripheral 端甚至已見 central connected),或舊連線殘留卡住。
    // 此時 handshakeTimeout 與 133 重試都尚未武裝(兩者都在回呼內才設),只能乾等 coordinator 的 15s。
    // 故在 connectGatt 後立即武裝此逾時,在預算內 close+重連,把「靜默卡死」轉成「快速重連」。
    private static final long CONNECT_TIMEOUT_MS = 4000L;
    private final Runnable connectTimeout = this::onConnectStall;

    // 掃描階段重啟：偶發掃不到對方廣播(系統掃描節流/丟包)→ 停掉重掃一兩次,常能救回。
    private static final long SCAN_RESTART_MS = 5000L;
    private static final int  MAX_SCAN_RESTARTS = 2;
    private int scanRestarts = 0;
    @Nullable private java.util.List<ScanFilter> scanFilters;
    @Nullable private ScanSettings scanSettings;
    private final Runnable scanRestart = this::onScanRestart;

    public BleCredentialClient(@NonNull Context context, @NonNull Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.timeoutRunnable = () -> {
            // 仍在掃描卻逾時 = 整段都沒看到對方的 nonce 廣播。最常見成因是「不同源 nonce」:
            // NFC 讀到的是對方舊場次 token,但對方已重建 coordinator 改廣播新 nonce(見 LESSONS 坑14)。
            if (scanning) {
                FlightRecorder.event(TAG, "BLE timeout: never saw peer advertising nonce="
                        + scanNonceB64 + " — peer not advertising it (likely stale/cross-session nonce mismatch)");
            }
            callback.onError("BLE 連線逾時，請重試");
        };
    }

    private static String b64(@Nullable byte[] n) {
        return n == null ? "null" : android.util.Base64.encodeToString(
                n, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
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

        scanFilters = Collections.singletonList(new ScanFilter.Builder()
                .setManufacturerData(BleConstants.MANUFACTURER_ID, peerNonce)
                .build());
        scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        scanNonceB64 = b64(peerNonce);
        scanner.startScan(scanFilters, scanSettings, scanCallback);
        seq("scanning for peer nonce=" + scanNonceB64);
        main.postDelayed(scanRestart, SCAN_RESTART_MS);
        main.postDelayed(timeoutRunnable, 15000);
    }

    /** 本機當選 GO:把憑證 write 給 peripheral。 */
    @SuppressLint({"MissingPermission", "deprecation"})
    @SuppressWarnings("deprecation") // setValue/writeCharacteristic 的新多載需 API 33;minSdk 29 保留舊 API
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
        main.removeCallbacks(handshakeTimeout);
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(scanRestart);
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
            main.removeCallbacks(scanRestart);
            try { if (scanner != null) scanner.stopScan(this); } catch (Exception ignored) {}
            peerDevice = result.getDevice();
            connectGatt();
        }

        @Override public void onScanFailed(int errorCode) {
            FlightRecorder.event(TAG, "Scan failed: " + errorCode);
            main.post(() -> callback.onError("BLE 掃描失敗 (" + errorCode + ")"));
        }
    };

    /** 連 GATT（可重試）：每次都用新的 connectGatt，前一次失敗的 gatt 已在 onConnectionStateChange close。 */
    @SuppressLint("MissingPermission")
    private void connectGatt() {
        if (stopped || peerDevice == null) return;
        gattAttempts++;
        seq("peer found, connecting GATT (attempt " + gattAttempts + ")…");
        gatt = peerDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        // connectGatt 可能完全不回呼 → 武裝連線階段逾時,逾時即 close+重連(見 onConnectStall)。
        main.removeCallbacks(connectTimeout);
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    /**
     * 交握逾時（GATT 已連上但 discover/MTU/token/CCCD 卡住未達 onReady）：
     * 主動斷掉目前 gatt（其 DISCONNECTED 因 g!=gatt 會被忽略），在重試額度內換一條新連線重來；
     * 用盡才回報錯誤。把「靜默卡住」轉成「快速重連」，多數暫時性 BLE 卡頓可自癒。
     */
    private void onHandshakeStall() {
        abortGattAndRetry("handshake stalled", "BLE 交握逾時，請再碰一下重試");
    }

    /** 連線階段逾時：connectGatt 後遲遲沒有 STATE_CONNECTED 回呼 → close+重連（同交握逾時的自癒路徑）。 */
    private void onConnectStall() {
        abortGattAndRetry("connect stalled (no STATE_CONNECTED callback)", "BLE 連線逾時，請再碰一下重試");
    }

    /**
     * 統一的「close 目前 gatt + 在預算內重連」：connect / handshake 兩種卡死共用。
     * 把「靜默卡死」轉成「快速重連」，多數暫時性 BLE 卡頓可自癒；用盡額度才回報錯誤。
     */
    @SuppressLint("MissingPermission")
    private void abortGattAndRetry(@NonNull String why, @NonNull String exhaustMsg) {
        if (stopped || ready || credentialDelivered) return;
        FlightRecorder.event(TAG, why + " (servicesDiscovered=" + servicesDiscovered
                + ", attempt=" + gattAttempts + ") → reconnect/abort");
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(handshakeTimeout);
        BluetoothGatt old = gatt;
        gatt = null;                       // 先脫鉤 → 舊 gatt 的 DISCONNECTED 會被 g!=gatt 忽略
        try { if (old != null) { old.disconnect(); old.close(); } } catch (Exception ignored) {}
        servicesDiscovered = false;
        credentialChar = null;
        peerTokenChar = null;
        if (gattAttempts < MAX_GATT_ATTEMPTS) {
            main.postDelayed(this::connectGatt, GATT_RETRY_BACKOFF_MS);
        } else {
            main.post(() -> callback.onError(exhaustMsg));
        }
    }

    /** 掃描階段重啟：仍在掃且尚未見到對方廣播 → 停掉重掃,吸收系統掃描節流/丟包。 */
    @SuppressLint("MissingPermission")
    private void onScanRestart() {
        if (stopped || !scanning || scanner == null
                || scanFilters == null || scanSettings == null) return;
        if (scanRestarts >= MAX_SCAN_RESTARTS) return; // 餘下交給 15s overall timeout 收尾
        scanRestarts++;
        FlightRecorder.event(TAG, "scan restart #" + scanRestarts
                + " (no advert seen yet for nonce=" + scanNonceB64 + ")");
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        try { scanner.startScan(scanFilters, scanSettings, scanCallback); } catch (Exception ignored) {}
        main.postDelayed(scanRestart, SCAN_RESTART_MS);
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
                main.removeCallbacks(connectTimeout); // 連上 → 連線階段逾時解除
                // 連上後改由「交握逾時」看門狗保護後續步驟（見 onHandshakeStall）。
                main.removeCallbacks(handshakeTimeout);
                main.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS);
                seq("GATT connected → discovering services");
                g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                // 連線失敗(常見 status 133)或交握完成前掉線。務必 close 釋放 client 介面,
                // 否則密集重碰會讓失敗的 GATT 物件堆疊洩漏、BLE 越來越不穩。
                try { g.close(); } catch (Exception ignored) {}
                // 來自「已被手動換掉的舊 gatt」的回呼 → 忽略，避免與交握逾時重連互相打架（重複重試/誤判失敗）。
                if (g != gatt) return;
                FlightRecorder.event(TAG, "GATT disconnected (status=" + status
                        + ", servicesDiscovered=" + servicesDiscovered + ", attempts=" + gattAttempts + ")");
                main.removeCallbacks(connectTimeout);
                main.removeCallbacks(handshakeTimeout);
                gatt = null;
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
            seq("services discovered → requestMtu");
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
        @SuppressWarnings("deprecation")
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            seq("MTU = " + mtu + " (status=" + status + ") → writing own token");
            // 1) 寫自己的 token 給 peripheral
            peerTokenChar.setValue(localToken.toUri().getBytes(StandardCharsets.UTF_8));
            peerTokenChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            g.writeCharacteristic(peerTokenChar);
        }

        @SuppressLint({"MissingPermission", "deprecation"})
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (ch == null) return;
            if (BleConstants.CHAR_PEER_TOKEN.equals(ch.getUuid())) {
                // 2) token 已送達 → 開啟 credential 的 notify
                seq("own token written (status=" + status + ") → enabling notify");
                g.setCharacteristicNotification(credentialChar, true);
                BluetoothGattDescriptor cccd = credentialChar.getDescriptor(BleConstants.CCCD);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                } else {
                    seq("no CCCD → READY (handshake done)");
                    ready = true;
                    main.removeCallbacks(handshakeTimeout);
                    main.post(callback::onReady);
                }
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (d != null && BleConstants.CCCD.equals(d.getUuid())) {
                // 3) notify 開啟 → 就緒
                seq("CCCD written (status=" + status + ") → READY (handshake done)");
                ready = true;
                main.removeCallbacks(handshakeTimeout);
                main.post(callback::onReady);
            }
        }

        @SuppressLint("deprecation")
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (ch != null && BleConstants.CHAR_CREDENTIAL.equals(ch.getUuid())) {
                deliverCredential(parseCredential(ch.getValue())); // notify 路徑
            }
        }

        @SuppressLint("deprecation")
        @SuppressWarnings("deprecation")
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
        catch (Exception e) {
            // 解析失敗不該靜默：留下診斷，否則只會表現為「卡在連線逾時」難以定位。
            FlightRecorder.event(TAG, "parseCredential failed (" + value.length + "B): " + e.getMessage());
            return null;
        }
    }
}
