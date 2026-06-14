package com.kisslink.transfer;

import android.os.Handler;
import android.util.Log;

/**
 * Manages idle teardown timer for the transfer service.
 * When the app is in background and not transferring, the service will be
 * stopped after {@link #IDLE_TEARDOWN_MS} to release Wi-Fi Direct resources.
 */
final class IdleTeardownManager {

    private static final String TAG = "IdleTeardownManager";
    private static final long IDLE_TEARDOWN_MS = 45_000;

    private final Handler mainHandler;
    private Runnable onTeardown;
    private boolean uiBound = false;
    private boolean transferring = false;

    private final Runnable idleTeardown = () -> {
        if (!uiBound && !transferring) {
            Log.i(TAG, "Idle in background → stopping service to release Wi-Fi Direct");
            if (onTeardown != null) onTeardown.run();
        }
    };

    IdleTeardownManager(Handler mainHandler, Runnable onTeardown) {
        this.mainHandler = mainHandler;
        this.onTeardown = onTeardown;
    }

    void setUiBound(boolean bound) {
        uiBound = bound;
        if (bound) {
            cancel();
        }
    }

    void setTransferring(boolean transferring) {
        this.transferring = transferring;
        if (!transferring) {
            scheduleIfIdle();
        }
    }

    void cancel() {
        mainHandler.removeCallbacks(idleTeardown);
    }

    void scheduleIfIdle() {
        cancel();
        if (!uiBound && !transferring) {
            mainHandler.postDelayed(idleTeardown, IDLE_TEARDOWN_MS);
        }
    }

    boolean isIdle() {
        return !uiBound && !transferring;
    }
}
