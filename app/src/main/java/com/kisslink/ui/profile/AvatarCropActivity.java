package com.kisslink.ui.profile;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import com.kisslink.R;
import com.kisslink.profile.ProfileStore;
import dagger.hilt.android.AndroidEntryPoint;
import java.io.IOException;

/**
 * 圓形頭像裁切頁：載入使用者選的圖 → 拖曳/縮放選範圍 → 套用後存入 {@link ProfileStore}。
 *
 * <p>解碼走 {@link ImageDecoder}（自動套用 EXIF 方向、降採樣大圖、輸出軟體點陣圖以便畫到 軟體 Canvas）。套用成功回 {@link
 * #RESULT_OK}，呼叫端據此重繪頭像。
 */
@AndroidEntryPoint
public class AvatarCropActivity extends AppCompatActivity {

    private static final String EXTRA_URI = "extra_source_uri";
    private static final int OUTPUT_SIZE = 512;
    private static final int DECODE_MAX_EDGE = 2048;

    /** 建立啟動 Intent（帶來源圖 Uri 與讀取權限）。 */
    @NonNull
    public static Intent newIntent(@NonNull Context ctx, @NonNull Uri sourceUri) {
        return new Intent(ctx, AvatarCropActivity.class)
                .putExtra(EXTRA_URI, sourceUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private AvatarCropView cropView;

    @Override
    protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_avatar_crop);
        cropView = findViewById(R.id.cropView);

        Uri uri =
                getIntent() != null
                        ? IntentCompat.getParcelableExtra(getIntent(), EXTRA_URI, Uri.class)
                        : null;
        if (uri == null) {
            finish();
            return;
        }

        Bitmap src;
        try {
            src = decodeBounded(uri);
        } catch (Exception e) {
            Toast.makeText(this, R.string.share_unsupported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        cropView.setImage(src);

        findViewById(R.id.btnCropCancel)
                .setOnClickListener(
                        v -> {
                            setResult(RESULT_CANCELED);
                            finish();
                        });
        findViewById(R.id.btnCropApply).setOnClickListener(v -> apply());
    }

    private void apply() {
        Bitmap cropped = cropView.getCroppedBitmap(OUTPUT_SIZE);
        boolean ok = ProfileStore.get(this).saveAvatarBitmap(cropped);
        setResult(ok ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    /** 以 ImageDecoder 解碼：套 EXIF 方向、降採樣到 ≤{@link #DECODE_MAX_EDGE}、輸出軟體點陣圖。 */
    @NonNull
    private Bitmap decodeBounded(@NonNull Uri uri) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(
                source,
                (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int maxEdge = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    int sample = 1;
                    while (maxEdge / sample > DECODE_MAX_EDGE) sample *= 2;
                    if (sample > 1) decoder.setTargetSampleSize(sample);
                });
    }
}
