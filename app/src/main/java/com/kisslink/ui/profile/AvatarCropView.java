package com.kisslink.ui.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 圓形頭像裁切視圖：載入來源圖後可拖曳平移、雙指縮放，圈內即為最終顯示範圍。
 *
 * <p>恆保證圓形被圖片完整覆蓋（縮放下限＝覆蓋圈、平移夾住不露白），按套用時把圈的外接方形
 * 渲染成方形點陣圖回傳（圓形遮罩交由顯示端的 ShapeableImageView 完成）。
 */
public class AvatarCropView extends View {

    private static final int INVALID_POINTER = -1;
    private static final float MAX_SCALE_MULT = 6f;   // 相對覆蓋下限的最大放大倍數

    @Nullable private Bitmap source;
    private final Matrix matrix = new Matrix();

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dimPath = new Path();

    private float cx, cy, radius;        // 裁切圈(view 座標)
    private float minScale = 1f;
    private final float marginPx;

    private final ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    private int activePointer = INVALID_POINTER;
    private final RectF mapped = new RectF();

    public AvatarCropView(Context c) { this(c, null); }

    public AvatarCropView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float density = getResources().getDisplayMetrics().density;
        marginPx = 28 * density;

        dimPaint.setColor(0xB3000000);            // 70% 黑遮罩(圈外)
        dimPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2 * density);

        scaleDetector = new ScaleGestureDetector(c, new ScaleListener());
    }

    /** 設定來源圖（已解碼、適度降採樣的點陣圖）。 */
    public void setImage(@NonNull Bitmap bmp) {
        this.source = bmp;
        if (getWidth() > 0 && getHeight() > 0) initMatrix();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - marginPx;
        if (source != null) initMatrix();
    }

    /** 初始：縮放到剛好覆蓋圈、置中。 */
    private void initMatrix() {
        if (source == null) return;
        int bw = source.getWidth(), bh = source.getHeight();
        if (bw <= 0 || bh <= 0) return;
        minScale = Math.max((2 * radius) / bw, (2 * radius) / bh);
        matrix.reset();
        matrix.postScale(minScale, minScale);
        // 置中：把縮放後的圖中心對到圈中心
        float dx = cx - (bw * minScale) / 2f;
        float dy = cy - (bh * minScale) / 2f;
        matrix.postTranslate(dx, dy);
    }

    private float currentScale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    /** 平移後夾住：確保圈始終被圖覆蓋（不露白）。 */
    private void clampPan() {
        if (source == null) return;
        mapped.set(0, 0, source.getWidth(), source.getHeight());
        matrix.mapRect(mapped);
        float left = cx - radius, right = cx + radius, top = cy - radius, bottom = cy + radius;
        float dx = 0, dy = 0;
        if (mapped.left > left) dx = left - mapped.left;
        else if (mapped.right < right) dx = right - mapped.right;
        if (mapped.top > top) dy = top - mapped.top;
        else if (mapped.bottom < bottom) dy = bottom - mapped.bottom;
        if (dx != 0 || dy != 0) matrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        performClick();
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = e.getPointerId(0);
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && activePointer != INVALID_POINTER) {
                    int idx = e.findPointerIndex(activePointer);
                    if (idx >= 0) {
                        float x = e.getX(idx), y = e.getY(idx);
                        matrix.postTranslate(x - lastX, y - lastY);
                        clampPan();
                        lastX = x;
                        lastY = y;
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int upIdx = e.getActionIndex();
                if (e.getPointerId(upIdx) == activePointer) {
                    int newIdx = upIdx == 0 ? 1 : 0;
                    activePointer = e.getPointerId(newIdx);
                    lastX = e.getX(newIdx);
                    lastY = e.getY(newIdx);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointer = INVALID_POINTER;
                break;
            default:
                break;
        }
        return true;
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector d) {
            if (source == null) return true;
            float factor = d.getScaleFactor();
            float cur = currentScale();
            float target = cur * factor;
            float max = minScale * MAX_SCALE_MULT;
            if (target < minScale) factor = minScale / cur;
            else if (target > max) factor = max / cur;
            matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
            clampPan();
            invalidate();
            return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (source == null) return;
        canvas.drawBitmap(source, matrix, imagePaint);

        // 圈外暗化：整塊 rect 挖掉中央圓(EVEN_ODD)
        dimPath.reset();
        dimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        dimPath.addCircle(cx, cy, radius, Path.Direction.CCW);
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        canvas.drawPath(dimPath, dimPaint);
        canvas.drawCircle(cx, cy, radius, ringPaint);
    }

    /** 把裁切圈的外接方形渲染成 {@code outSize} 方形點陣圖。 */
    @NonNull
    public Bitmap getCroppedBitmap(int outSize) {
        Bitmap out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Matrix render = new Matrix(matrix);
        render.postTranslate(-(cx - radius), -(cy - radius));   // 圈左上角 → 原點
        float s = outSize / (2f * radius);
        render.postScale(s, s);
        if (source != null) c.drawBitmap(source, render, imagePaint);
        return out;
    }
}
