package com.kisslink.ui.home;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 有機感淡入上滑——畫面內容變動時的統一過場（呼應 design C 的 fadeUp）。 */
final class Anim {
    private Anim() {}

    /** 內容淡入 + 輕微上滑（用於文字/橫幅/按鈕等元件變動時）。 */
    static void fadeUp(View v) {
        if (v == null) return;
        float dy = 14 * v.getResources().getDisplayMetrics().density;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate().alpha(1f).translationY(0f)
                .setDuration(340)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    /** 變為可見時才播淡入上滑；本來就可見則不打擾。 */
    static void revealFadeUp(View v) {
        if (v == null) return;
        if (v.getVisibility() != View.VISIBLE) {
            v.setVisibility(View.VISIBLE);
            fadeUp(v);
        }
    }
}
