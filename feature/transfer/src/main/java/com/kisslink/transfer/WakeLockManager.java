package com.kisslink.transfer;

import android.app.Service;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Manages a partial wake lock to prevent CPU throttling during active transfers.
 */
final class WakeLockManager {

    private static final String TAG = "WakeLockManager";
    private static final long MAX_DURATION_MS = 10 * 60 * 1000L; // 10 minutes cap

    @Nullable private PowerManager.WakeLock wakeLock;

    private final Service service;

    WakeLockManager(@NonNull Service service) {
        this.service = service;
    }

    synchronized void acquire() {
        if (wakeLock == null) {
            PowerManager pm = service.getSystemService(PowerManager.class);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KissLink:transfer");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(MAX_DURATION_MS);
    }

    synchronized void release() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
