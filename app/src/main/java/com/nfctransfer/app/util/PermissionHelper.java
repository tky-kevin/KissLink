package com.nfctransfer.app.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.provider.Settings;

import android.content.pm.PackageManager;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionHelper {

    public static List<String> getMissingPermissions(Activity activity) {
        List<String> missing = new ArrayList<>();

        String[] required = buildRequiredPermissions();
        for (String permission : required) {
            if (ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }

    public static void requestAllPermissions(Activity activity, int requestCode) {
        List<String> missing = getMissingPermissions(activity);
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(
                    activity,
                    missing.toArray(new String[0]),
                    requestCode);
        }
    }

    public static boolean isNfcEnabled(Context context) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        return adapter != null && adapter.isEnabled();
    }

    public static void showNfcEnableDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("NFC 未開啟")
                .setMessage("此應用程式需要 NFC 功能。請前往設定開啟 NFC。")
                .setPositiveButton("前往設定", (dialog, which) -> {
                    activity.startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String[] buildRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        return perms.toArray(new String[0]);
    }
}
