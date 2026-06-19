package com.kisslink.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.transfer.SendItem;

/**
 * Transfers all session-state→UI rendering logic out of HomeActivity.
 *
 * <p>Owns:
 * <ul>
 *   <li>Connection-stage ticker (NFC→BLE→Wi-Fi progression labels).</li>
 *   <li>Headline / percent / speed display helpers.</li>
 *   <li>Peer-identity avatar rendering.</li>
 * </ul>
 *
 * <p>HomeActivity delegates here after each {@code onSession} callback; the Activity
 * keeps only lifecycle, NFC, service binding, and user-intent forwarding.
 */
final class TransferUiController {

    private static final long STAGE_MIN_DWELL_MS = 650;

    private final Context context;
    private final Handler main;
    private final TextView tvHeadline;
    private final TextView tvSub;
    private final TextView tvPercent;
    private final View percentRow;

    // ── Stage ticker state ──
    private int stageShown = -1;
    private int stageTarget = 0;
    private boolean stageRunning = false;

    TransferUiController(@NonNull Context context,
                         @NonNull Handler main,
                         @NonNull TextView tvHeadline,
                         @NonNull TextView tvSub,
                         @NonNull TextView tvPercent,
                         @NonNull View percentRow) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.tvHeadline = tvHeadline;
        this.tvSub = tvSub;
        this.tvPercent = tvPercent;
        this.percentRow = percentRow;
    }

    // ══════════════════════════════════════════════════════════
    //  Headline / percent
    // ══════════════════════════════════════════════════════════

    void showHeadlineText(String title, @Nullable String sub) {
        hidePercent();
        tvHeadline.setVisibility(View.VISIBLE);
        boolean changed = !title.contentEquals(tvHeadline.getText());
        tvHeadline.setText(title);
        if (changed) Anim.fadeUp(tvHeadline);
        if (sub != null) {
            boolean subChanged = !sub.contentEquals(tvSub.getText());
            tvSub.setText(sub);
            if (subChanged) Anim.fadeUp(tvSub);
        }
    }

    void showPercent(int pct) {
        tvHeadline.setVisibility(View.INVISIBLE);
        percentRow.setVisibility(View.VISIBLE);
        tvPercent.setText(pct >= 0 ? String.valueOf(pct) : "0");
    }

    void showTransferHeadline(String speed, @Nullable String sub) {
        percentRow.setVisibility(View.GONE);
        tvHeadline.setVisibility(View.VISIBLE);
        tvHeadline.setText(speed);
        if (sub != null) tvSub.setText(sub);
    }

    void hidePercent() {
        percentRow.setVisibility(View.GONE);
        tvHeadline.setVisibility(View.VISIBLE);
    }

    // ══════════════════════════════════════════════════════════
    //  Stage ticker (NFC → BLE → Wi-Fi)
    // ══════════════════════════════════════════════════════════

    void runStageTicker(int target) {
        stageTarget = Math.max(stageTarget, target);
        if (!stageRunning) {
            stageRunning = true;
            stageShown = -1;
            main.post(stageTick);
        }
    }

    void stopStageTicker() {
        stageRunning = false;
        stageTarget = 0;
        stageShown = -1;
        main.removeCallbacks(stageTick);
    }

    private final Runnable stageTick = new Runnable() {
        @Override public void run() {
            if (!stageRunning) return;
            if (stageShown < stageTarget) {
                stageShown++;
                showHeadlineText(stageLabel(stageShown),
                        context.getString(R.string.stage_tap_to_cancel));
            }
            main.postDelayed(this, STAGE_MIN_DWELL_MS);
        }
    };

    private String stageLabel(int step) {
        switch (step) {
            case 0:  return context.getString(R.string.stage_nfc);
            case 1:  return context.getString(R.string.stage_ble);
            default: return context.getString(R.string.stage_wifi);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Peer identity rendering
    // ══════════════════════════════════════════════════════════

    void showPeerIdentity(@NonNull BeamStageView beam,
                          @Nullable String peerName,
                          @Nullable byte[] peerAvatar,
                          @NonNull String selfName,
                          @Nullable Bitmap selfAvatar) {
        beam.setPeerIdentity(peerName);
        beam.setPeerAvatar(decodeAvatar(peerAvatar));
        beam.setSelfAvatar(selfAvatar);
        beam.setSelfIdentity(selfName);
    }

    // ══════════════════════════════════════════════════════════
    //  Utility (pure, no state)
    // ══════════════════════════════════════════════════════════

    static String speedLabelInt(long bps) {
        if (bps < 0) bps = 0;
        if (bps >= 1024L * 1024) return Math.round(bps / (1024.0 * 1024)) + " MB/s";
        if (bps >= 1024) return Math.round(bps / 1024.0) + " KB/s";
        return bps + " B/s";
    }

    static String sizeLabel(long bytes) {
        return com.kisslink.util.FileUtils.sizeLabel(bytes);
    }

    static int iconForItem(SendItem it) {
        return com.kisslink.util.FileUtils.iconFor(it.itemType, it.mime, it.name);
    }

    /** Maps a session phase to the stage ticker target index (0=NFC, 1=BLE, 2=Wi-Fi). */
    static int stageTargetFor(@NonNull com.kisslink.transfer.SessionState.Phase p) {
        switch (p) {
            case PAIRING_LATCHED: return 0;
            case PAIRING_LINKING:
            case PAIRING_ELECTING: return 1;
            default: return 2;
        }
    }

    @Nullable
    private static Bitmap decodeAvatar(@Nullable byte[] bytes) {
        if (bytes == null) return null;
        try { return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length); }
        catch (Exception e) { return null; }
    }
}
