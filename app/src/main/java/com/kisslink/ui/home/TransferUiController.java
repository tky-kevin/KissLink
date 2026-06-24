package com.kisslink.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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

    private static final long STAGE_MIN_DWELL_MS = 750;

    private final Context context;
    private final Handler main;
    private final TextView tvHeadline;
    private final TextView tvSub;
    private final TextView tvPercent;
    private final TextView tvPercentUnit;
    private final View percentRow;

    // ── Stage ticker state ──
    private int stageShown = -1;
    private int stageTarget = 0;
    private boolean stageRunning = false;
    // 收到 CONNECTED 時不立刻切「已連線」,而是等 ticker 把最後一階段(TCP)走完並停留一拍再執行,
    // 避免 socket 建得太快、TCP 階段只閃一下沒有停留感。
    @Nullable private Runnable onStagesComplete;

    // ── 階段文字的動態「…」(極客感:跑動的省略號) ──
    private static final long ELLIPSIS_PERIOD_MS = 400;
    private String stageBase = "";
    private int ellipsisDots = 0;
    private boolean ellipsisRunning = false;

    // 傳輸中只在「進入傳輸」當下播一次動畫;速度頻繁更新不每次都動,避免閃爍。
    private boolean inTransfer = false;

    TransferUiController(@NonNull Context context,
                         @NonNull Handler main,
                         @NonNull TextView tvHeadline,
                         @NonNull TextView tvSub,
                         @NonNull TextView tvPercent,
                         @NonNull TextView tvPercentUnit,
                         @NonNull View percentRow) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.tvHeadline = tvHeadline;
        this.tvSub = tvSub;
        this.tvPercent = tvPercent;
        this.tvPercentUnit = tvPercentUnit;
        this.percentRow = percentRow;
    }

    // ══════════════════════════════════════════════════════════
    //  Headline / percent
    // ══════════════════════════════════════════════════════════

    void showHeadlineText(String title, @Nullable String sub) {
        stopEllipsis();
        inTransfer = false;
        hidePercent();
        tvHeadline.setVisibility(View.VISIBLE);
        boolean changed = !title.contentEquals(tvHeadline.getText());
        tvHeadline.setText(title);
        if (changed) Anim.fadeUp(tvHeadline);
        if (sub != null) {
            // 傳輸中曾把副標隱藏（只留速度），離開傳輸恢復顯示。
            boolean wasHidden = tvSub.getVisibility() != View.VISIBLE;
            tvSub.setVisibility(View.VISIBLE);
            boolean subChanged = !sub.contentEquals(tvSub.getText());
            tvSub.setText(sub);
            if (subChanged || wasHidden) Anim.fadeUp(tvSub);
        }
    }

    void showPercent(int pct) {
        stopEllipsis();
        inTransfer = false;
        tvHeadline.setVisibility(View.INVISIBLE);
        percentRow.setVisibility(View.VISIBLE);
        tvPercent.setText(pct >= 0 ? String.valueOf(pct) : "0");
    }

    /**
     * 傳輸中速度「英雄字」：大號數字（tabular）＋單位，借用 percentRow 的大字版位。
     * 不顯示副標（傳輸細節改由列表方塊呈現）；只在進入傳輸的第一幀播一次淡入上滑，
     * 之後速度持續更新只換字、不重播動畫，避免跳動閃爍。
     */
    void showSpeedHero(String number, String unit) {
        stopEllipsis();
        tvHeadline.setVisibility(View.INVISIBLE);
        tvSub.setVisibility(View.GONE);
        percentRow.setVisibility(View.VISIBLE);
        tvPercent.setText(number);
        tvPercentUnit.setText(unit);
        if (!inTransfer) { inTransfer = true; Anim.fadeUp(percentRow); }
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
        onStagesComplete = null;
        main.removeCallbacks(stageTick);
        stopEllipsis();
    }

    /**
     * 連線完成(CONNECTED)時呼叫:讓階段 ticker 把剩餘階段(含最後的「建立 TCP 通道」)依序走完、
     * 最後一階段再停留一拍,然後才執行 {@code whenDone}(切到「已連線」)。若 ticker 未在跑
     * (例如同裝置重貼 resume、傳輸結束回連線),則立即執行。
     */
    void completeStagesThen(@NonNull Runnable whenDone) {
        if (!stageRunning) { whenDone.run(); return; }
        onStagesComplete = whenDone;   // 由 stageTick 在抵達 target 並停留一拍後觸發
    }

    private final Runnable stageTick = new Runnable() {
        @Override public void run() {
            if (!stageRunning) return;
            if (stageShown < stageTarget) {
                stageShown++;
                showLoadingHeadline(stageLabel(stageShown),
                        context.getString(R.string.stage_tap_to_cancel));
                main.postDelayed(this, STAGE_MIN_DWELL_MS);
                return;
            }
            // 已抵達 target:若有待執行的「完成」回呼,代表最後一階段已停留滿一拍 → 收尾。
            if (onStagesComplete != null) {
                Runnable done = onStagesComplete;
                stopStageTicker();
                done.run();
                return;
            }
            main.postDelayed(this, STAGE_MIN_DWELL_MS);   // 持續等待後續階段或完成訊號
        }
    };

    // ── 階段文字 + 動態省略號 ────────────────────────────────────

    /**
     * 顯示「載入中」大標(結尾帶會跑動的「…」),換文字時播淡入上滑,省略號更新不重播動畫。
     * 供階段 ticker 與「重整連線」共用。
     */
    void showLoadingHeadline(String base, @Nullable String sub) {
        inTransfer = false;
        hidePercent();
        tvHeadline.setVisibility(View.VISIBLE);
        boolean changed = !base.equals(stageBase);
        stageBase = base;
        ellipsisDots = 0;
        renderStage();
        if (changed) Anim.fadeUp(tvHeadline);
        if (sub != null) {
            boolean subChanged = !sub.contentEquals(tvSub.getText());
            tvSub.setText(sub);
            if (subChanged) Anim.fadeUp(tvSub);
        }
        if (!ellipsisRunning) {
            ellipsisRunning = true;
            main.postDelayed(ellipsisTick, ELLIPSIS_PERIOD_MS);
        }
    }

    /**
     * 渲染「base + 三點」,但只讓前 1~3 點可見、其餘設為透明。
     * 三點永遠佔位 → 文字寬度固定、置中不抖動(避免點數變化時整行左右跳)。
     */
    private void renderStage() {
        int n = (ellipsisDots % 3) + 1;            // 1,2,3 循環(可見點數)
        SpannableString s = new SpannableString(stageBase + "...");
        int visibleEnd = stageBase.length() + n;
        int total = stageBase.length() + 3;
        if (visibleEnd < total) {
            s.setSpan(new ForegroundColorSpan(Color.TRANSPARENT),
                    visibleEnd, total, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvHeadline.setText(s);
    }

    private final Runnable ellipsisTick = new Runnable() {
        @Override public void run() {
            if (!ellipsisRunning) return;
            ellipsisDots++;
            renderStage();
            main.postDelayed(this, ELLIPSIS_PERIOD_MS);
        }
    };

    private void stopEllipsis() {
        ellipsisRunning = false;
        stageBase = "";
        main.removeCallbacks(ellipsisTick);
    }

    private String stageLabel(int step) {
        switch (step) {
            case 0:  return context.getString(R.string.stage_nfc);
            case 1:  return context.getString(R.string.stage_ble);
            case 2:  return context.getString(R.string.stage_elect);
            case 3:  return context.getString(R.string.stage_wifi);
            default: return context.getString(R.string.stage_tcp);
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

    /** 速度英雄字：純數字部分（搭配 {@link #speedUnit}）。 */
    static String speedNumber(long bps) {
        if (bps < 0) bps = 0;
        if (bps >= 1024L * 1024) return String.valueOf(Math.round(bps / (1024.0 * 1024)));
        if (bps >= 1024) return String.valueOf(Math.round(bps / 1024.0));
        return String.valueOf(bps);
    }

    /** 速度英雄字：單位部分（MB/s、KB/s、B/s）。 */
    static String speedUnit(long bps) {
        if (bps < 0) bps = 0;
        if (bps >= 1024L * 1024) return "MB/s";
        if (bps >= 1024) return "KB/s";
        return "B/s";
    }

    static String sizeLabel(long bytes) {
        return com.kisslink.util.FileUtils.sizeLabel(bytes);
    }

    static int iconForItem(SendItem it) {
        return com.kisslink.util.FileUtils.iconFor(it.itemType, it.mime, it.name);
    }

    /**
     * Maps a session phase to the stage ticker target index
     * (0=NFC, 1=BLE, 2=協商主機, 3=Wi-Fi Direct, 4=TCP通道).
     *
     * <p>The ticker only advances and dwells, so a jump (e.g. BLE→TCP) still walks
     * through the intermediate labels briefly — no phase needs to fire for its label
     * to show.
     */
    static int stageTargetFor(@NonNull com.kisslink.transfer.SessionState.Phase p) {
        switch (p) {
            case PAIRING_LATCHED:  return 0;
            case PAIRING_LINKING:  return 1;
            case PAIRING_ELECTING: return 2;
            case SOCKETING:        return 4;
            default:               return 3;   // CONNECTING（Wi-Fi Direct 建群/連線）
        }
    }

    @Nullable
    private static Bitmap decodeAvatar(@Nullable byte[] bytes) {
        if (bytes == null) return null;
        try { return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length); }
        catch (Exception e) { return null; }
    }
}
