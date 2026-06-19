package com.kisslink.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 名片資料的單一儲存點（process 範圍 singleton）：
 * <ul>
 *   <li>姓名 + 自訂欄位 → SharedPreferences（JSON）。</li>
 *   <li>頭像 → 內部儲存 {@code avatar.png}（置中裁切＋縮放，避免肥大）。</li>
 *   <li>{@link #avatarThumbBytes} 產生小縮圖供連線後 HELLO 交換給對方顯示。</li>
 * </ul>
 */
public final class ProfileStore {

    private static final String PREFS = "kisslink_profile";
    private static final String K_NAME = "name";
    private static final String K_FIELDS = "fields";       // JSON array of {l,v}
    private static final String K_AVATAR_VER = "avatar_ver";
    private static final String AVATAR_FILE = "avatar.png";

    private static final int AVATAR_MAX = 512;     // 內部儲存頭像最大邊
    private static final int THUMB_MAX = 192;       // 交換縮圖最大邊
    private static final int THUMB_QUALITY = 80;

    private static volatile ProfileStore instance;

    private final Context app;
    private final SharedPreferences prefs;

    private ProfileStore(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static ProfileStore get(@NonNull Context ctx) {
        if (instance == null) {
            synchronized (ProfileStore.class) {
                if (instance == null) instance = new ProfileStore(ctx);
            }
        }
        return instance;
    }

    // ── 姓名 ──────────────────────────────────────────────────

    /** 顯示名稱；未設定時回退到預設人設「漫遊者」。 */
    @NonNull
    public String name() {
        String n = prefs.getString(K_NAME, "");
        if (n != null && !n.trim().isEmpty()) return n.trim();
        return app.getString(com.kisslink.R.string.default_name);
    }

    public boolean hasCustomName() {
        String n = prefs.getString(K_NAME, "");
        return n != null && !n.trim().isEmpty();
    }

    // ── Profile 讀寫 ──────────────────────────────────────────

    @NonNull
    public Profile load() {
        Profile p = Profile.empty();
        p.setName(prefs.getString(K_NAME, ""));
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_FIELDS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                p.addField(new Profile.Field(o.optString("l", ""), o.optString("v", "")));
            }
        } catch (Exception ignored) {}
        return p;
    }

    public void save(@NonNull Profile p) {
        JSONArray arr = new JSONArray();
        for (Profile.Field f : p.getFields()) {
            if ((f.getLabel() == null || f.getLabel().trim().isEmpty())
                    && (f.getValue() == null || f.getValue().trim().isEmpty())) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("l", f.getLabel() == null ? "" : f.getLabel().trim());
                o.put("v", f.getValue() == null ? "" : f.getValue().trim());
                arr.put(o);
            } catch (Exception ignored) {}
        }
        prefs.edit()
                .putString(K_NAME, p.getName() == null ? "" : p.getName().trim())
                .putString(K_FIELDS, arr.toString())
                .apply();
    }

    // ── 頭像 ──────────────────────────────────────────────────

    public File avatarFile() { return new File(app.getFilesDir(), AVATAR_FILE); }

    public boolean hasAvatar() { return avatarFile().exists(); }

    /** 頭像版本號（每次更新 +1）——供 UI 快取失效。 */
    public int avatarVersion() { return prefs.getInt(K_AVATAR_VER, 0); }

    @Nullable
    public Bitmap loadAvatar() {
        File f = avatarFile();
        if (!f.exists()) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    /** 從使用者選的圖片 Uri 匯入：置中裁正方、縮到 AVATAR_MAX、存成 png。 */
    public boolean setAvatarFromUri(@NonNull Uri uri) {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null) return false;
            Bitmap square = centerCropScale(src, AVATAR_MAX);
            try (FileOutputStream out = new FileOutputStream(avatarFile())) {
                square.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            prefs.edit().putInt(K_AVATAR_VER, avatarVersion() + 1).apply();
            if (square != src) src.recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void clearAvatar() {
        File f = avatarFile();
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
        prefs.edit().putInt(K_AVATAR_VER, avatarVersion() + 1).apply();
    }

    /** 交換用縮圖（JPEG bytes，≤THUMB_MAX）；無頭像回 null。 */
    @Nullable
    public byte[] avatarThumbBytes() {
        Bitmap b = loadAvatar();
        if (b == null) return null;
        Bitmap t = centerCropScale(b, THUMB_MAX);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        t.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, bos);
        if (t != b) t.recycle();
        b.recycle();
        return bos.toByteArray();
    }

    // ── 工具 ──────────────────────────────────────────────────

    private static Bitmap centerCropScale(@NonNull Bitmap src, int target) {
        int size = Math.min(src.getWidth(), src.getHeight());
        int left = (src.getWidth() - size) / 2;
        int top = (src.getHeight() - size) / 2;
        Bitmap sq = Bitmap.createBitmap(src, left, top, size, size);
        if (size <= target) return sq;
        float s = (float) target / size;
        Matrix m = new Matrix();
        m.setScale(s, s);
        Bitmap scaled = Bitmap.createBitmap(sq, 0, 0, size, size, m, true);
        if (scaled != sq) sq.recycle();
        return scaled;
    }
}
