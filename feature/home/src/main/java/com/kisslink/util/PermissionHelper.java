package com.kisslink.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 權限請求工具類。
 */
public class PermissionHelper {

    public static final int REQUEST_CODE = 1001;

    /**
     * 取得 Wi-Fi Direct 與檔案傳輸所需的權限清單。
     */
    public static String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();

        // 基礎 Wi-Fi 與位置 (API 29+)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_WIFI_STATE);
        permissions.add(Manifest.permission.CHANGE_WIFI_STATE);

        // API 33+ 需要 NEARBY_WIFI_DEVICES 與 POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        // API 31+：BLE 憑證側通道需要的新版藍牙執行期權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }

        // 前景服務 (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        
        // API 34+ 前景服務類型權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC);
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE);
        }

        return permissions.toArray(new String[0]);
    }

    /**
     * 檢查是否已取得所有必要權限。
     */
    public static boolean hasPermissions(@NonNull Context context) {
        for (String p : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 請求權限。
     */
    public static void requestPermissions(@NonNull Activity activity) {
        ActivityCompat.requestPermissions(activity, getRequiredPermissions(), REQUEST_CODE);
    }

    /**
     * 在 {@code onRequestPermissionsResult} 中呼叫，判斷是否全部授予。
     */
    public static boolean allGranted(@NonNull int[] grantResults) {
        if (grantResults.length == 0) return false;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════
    //  配對前置檢查：連線必需的執行期權限 + 無線電開關
    // ══════════════════════════════════════════════════════════

    /** 待開啟的無線電，依配對流程的使用順序排序（NFC → 藍牙 → Wi-Fi）。 */
    public enum Radio { NFC, BLUETOOTH, WIFI }

    /**
     * 「碰觸 → 連線」流程實際用到的執行期權限子集（藍牙側通道 + Wi-Fi Direct）。
     * 與 {@link #getRequiredPermissions()} 不同：刻意不含媒體/通知權限——那些被拒不該擋住配對。
     */
    public static String[] getConnectivityPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_WIFI_STATE);
        p.add(Manifest.permission.CHANGE_WIFI_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        return p.toArray(new String[0]);
    }

    /** 是否已取得配對連線所需的執行期權限（媒體權限被拒不影響）。 */
    public static boolean hasConnectivityPermissions(@NonNull Context context) {
        for (String p : getConnectivityPermissions()) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNfcEnabled(@NonNull Context context) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        return adapter != null && adapter.isEnabled();
    }

    public static boolean isBluetoothEnabled(@NonNull Context context) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
    }

    public static boolean isWifiEnabled(@NonNull Context context) {
        WifiManager wm = (WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    /**
     * 個人熱點（SoftAP）是否開啟。
     *
     * <p>為何重要：Wi-Fi 晶片的並存介面額度多半只有兩個；熱點開著時 {@code ap0}(AP) +
     * {@code wlan0}(STA) 已佔滿，Wi-Fi Direct 想再開 {@code p2p} 介面會被 HAL 拒絕
     * （{@code bestIfaceCreationProposal is null}）→ createGroup/connect 一律回 BUSY(2)。
     * 重試無效，因為熱點不關介面就不會空出來。故配對前先擋下並提示關閉。
     *
     * <p>無公開 API 可查自身 SoftAP 狀態，改用反射呼叫隱藏的 {@code WifiManager#isWifiApEnabled()}。
     * 反射被新版系統擋下或拋例外時一律回 {@code false}（保守：偵測不到就不擋，避免誤擋正常配對）。
     */
    @SuppressLint("PrivateApi")
    public static boolean isHotspotEnabled(@NonNull Context context) {
        WifiManager wm = (WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;
        try {
            java.lang.reflect.Method m = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            m.setAccessible(true);
            Object r = m.invoke(wm);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Wi-Fi Direct 是否需要「定位服務」主開關開啟。
     *
     * <p>API 33+ 改用 {@code NEARBY_WIFI_DEVICES} 執行期權限，P2P 不再依賴定位服務；
     * 但 API 32 及以下（含本專案 minSdk 29）的 Wi-Fi P2P 探索/連線仍要求<b>定位服務主開關</b>
     * 開啟——權限授予但主開關關閉時，{@code discoverPeers}/{@code connect} 會靜默失敗、
     * 無錯誤回呼，使用者只看到配對卡住。故在舊版上需於配對前檢查並提示。
     */
    public static boolean isLocationServicesRequired() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * 定位服務主開關是否開啟。用 {@link android.location.LocationManager#isLocationEnabled()}
     * （API 28+，本專案 minSdk 29 下恆可用）。取不到服務時保守回 {@code false}。
     */
    public static boolean isLocationEnabled(@NonNull Context context) {
        android.location.LocationManager lm = (android.location.LocationManager)
                context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    /**
     * 回傳第一個尚未開啟的無線電（依 NFC → 藍牙 → Wi-Fi 順序）；全部已開啟回 {@code null}。
     * 供配對前置檢查決定要提示使用者開啟哪一項。
     */
    @Nullable
    public static Radio firstDisabledRadio(@NonNull Context context) {
        if (!isNfcEnabled(context))       return Radio.NFC;
        if (!isBluetoothEnabled(context)) return Radio.BLUETOOTH;
        if (!isWifiEnabled(context))      return Radio.WIFI;
        return null;
    }
}
