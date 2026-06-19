package com.kisslink.ui.profile;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

/** 名片類置中卡片對話框的共用視窗設定：透明底、暗化、背景模糊、進場淡入上滑。 */
final class CardDialogs {
    private CardDialogs() {}

    static void applyWindow(@Nullable Dialog d) {
        if (d == null || d.getWindow() == null) return;
        Window w = d.getWindow();
        // 全螢幕視窗：卡片在版面置中，翻飛動畫往上/往下都不會被視窗邊緣裁切；
        // 透明區域維持暗化/模糊，點透明區由各 sheet 處理關閉。
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 暗化/模糊由 0 開始，名片顯示時平滑淡入（dim 0→0.55、blur 0→48）
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        final boolean blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        if (blur) {
            w.setFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp0 = w.getAttributes();
            lp0.setBlurBehindRadius(0);
            w.setAttributes(lp0);
        }
        ValueAnimator bgIn = ValueAnimator.ofFloat(0f, 1f);
        bgIn.setDuration(360);
        bgIn.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f * p;
            if (blur) lp.setBlurBehindRadius((int) (48 * p));
            w.setAttributes(lp);
        });
        bgIn.start();
        // 進場：卡片淡入 + 輕微上滑 + 放大（有機感）
        View content = w.getDecorView();
        content.setAlpha(0f);
        content.setTranslationY(dp(content, 24));
        content.setScaleX(0.96f);
        content.setScaleY(0.96f);
        content.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(360)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .start();
    }

    private static float dp(View v, float d) {
        return d * v.getResources().getDisplayMetrics().density;
    }
}
