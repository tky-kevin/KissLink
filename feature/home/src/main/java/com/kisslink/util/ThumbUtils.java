package com.kisslink.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import androidx.annotation.Nullable;
import java.io.InputStream;

/**
 * 縮圖解碼工具——集中 SendListAdapter 與 HistorySheet 先前各自重複的影像/影片縮圖邏輯。
 *
 * <p>皆為 best-effort：失敗回傳 {@code null}（呼叫端維持原圖示）。請在背景執行緒呼叫。
 */
public final class ThumbUtils {

    private ThumbUtils() {}

    /** 依 content uri 的 MIME 自動分派影片/影像縮圖解碼。 */
    @Nullable
    public static Bitmap decode(Context ctx, Uri uri, int targetPx) {
        String mime = ctx.getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("video/")) {
            return decodeVideo(ctx, uri, targetPx);
        }
        return decodeImage(ctx, uri, targetPx);
    }

    /** 取影片第一格並等比縮放到至少覆蓋 {@code targetPx} 的方框。 */
    @Nullable
    public static Bitmap decodeVideo(Context ctx, Uri uri, int targetPx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(ctx, uri);
            Bitmap bm = retriever.getFrameAtTime(0);
            if (bm == null) return null;
            int w = bm.getWidth();
            int h = bm.getHeight();
            float scale = Math.max((float) targetPx / w, (float) targetPx / h);
            int nw = Math.round(w * scale);
            int nh = Math.round(h * scale);
            Bitmap scaled = Bitmap.createScaledBitmap(bm, nw, nh, true);
            if (scaled != bm) bm.recycle();
            return scaled;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 以 inSampleSize 取樣解碼影像縮圖，避免載入全尺寸 bitmap。 */
    @Nullable
    public static Bitmap decodeImage(Context ctx, Uri uri, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            int half = Math.min(bounds.outWidth, bounds.outHeight) / 2;
            while (half / sample > targetPx) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opt);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
