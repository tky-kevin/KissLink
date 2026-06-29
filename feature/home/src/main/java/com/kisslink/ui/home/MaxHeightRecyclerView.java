package com.kisslink.ui.home;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 會「依內容自適應、超過上限才捲動」的 RecyclerView。
 *
 * <p>搭配 {@code layout_height="wrap_content"} 使用：以 {@code AT_MOST(maxHeight)} 量測， 內容小於上限時精確貼合內容高度（含
 * ItemDecoration 的上下留白 → 上下對稱）， 內容超過上限時固定在上限並可捲動（露出淡出邊）。
 *
 * <p>取代「以固定每列估計值算死高度」的做法——後者會因估計值大於實際列高， 把多餘空間累積在底部，造成下緣留白比上緣寬。
 */
public class MaxHeightRecyclerView extends RecyclerView {

    private int maxHeightPx = 0; // <=0 表示不限制

    public MaxHeightRecyclerView(Context context) {
        super(context);
    }

    public MaxHeightRecyclerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /** 設定高度上限（px）；之後內容超過此值即固定並可捲動。 */
    public void setMaxHeight(int px) {
        if (px != maxHeightPx) {
            maxHeightPx = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxHeightPx > 0) {
            heightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
