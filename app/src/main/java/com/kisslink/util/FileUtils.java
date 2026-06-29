package com.kisslink.util;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.annotation.Nullable;
import com.kisslink.R;
import com.kisslink.transfer.TransferProtocol;
import java.util.Locale;

/** 檔案相關工具函式（SAF / ContentResolver 適配）。 */
public final class FileUtils {

    private FileUtils() {}

    /** 從 SAF Uri 取得顯示名稱（檔名）。 */
    public static String getFileName(ContentResolver cr, Uri uri) {
        String result = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (col >= 0) result = c.getString(col);
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "unknown_file";
    }

    /** 從 SAF Uri 取得檔案大小（bytes），若無法取得回傳 -1。 */
    public static long getFileSize(ContentResolver cr, Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int col = c.getColumnIndex(OpenableColumns.SIZE);
                    if (col >= 0 && !c.isNull(col)) return c.getLong(col);
                }
            }
        }
        return -1;
    }

    /**
     * 格式化位元組數為清單顯示字串，例如「12.3 MB」；未知大小（負值）回傳空字串。 UI（待傳清單、歷史）共用的單一真相，取代先前散落於 HomeActivity /
     * HistorySheet 的副本。
     */
    public static String sizeLabel(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 依項目類型 + MIME + 檔名挑選圖示，集中 SendListAdapter / HomeActivity 先前各自的副本： 名片→人像、相片/影片→圖片，其餘依
     * MIME（次之依副檔名）推測。
     */
    public static int iconFor(byte itemType, @Nullable String mime, @Nullable String name) {
        if (itemType == TransferProtocol.ITEM_VCARD) return R.drawable.ic_person;
        if (itemType == TransferProtocol.ITEM_PHOTO) return R.drawable.ic_image;
        if (itemType == TransferProtocol.ITEM_TEXT) return R.drawable.ic_file;
        if (mime != null) return guessIconFromMime(mime);
        return guessIcon(name);
    }

    /** 依 MIME 類型推測圖示資源 ID。 */
    public static int guessIconFromMime(String mime) {
        if (mime == null) return R.drawable.ic_file;
        if (mime.startsWith("image/")) return R.drawable.ic_image;
        if (mime.startsWith("video/")) return R.drawable.ic_file_video;
        if (mime.startsWith("audio/")) return R.drawable.ic_file_audio;
        if (mime.equals("application/pdf")) return R.drawable.ic_file_pdf;
        if (mime.contains("word")
                || mime.equals("application/msword")
                || mime.equals(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            return R.drawable.ic_file_word;
        if (mime.contains("excel")
                || mime.equals("application/vnd.ms-excel")
                || mime.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            return R.drawable.ic_file_excel;
        if (mime.contains("powerpoint")
                || mime.equals("application/vnd.ms-powerpoint")
                || mime.equals(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
            return R.drawable.ic_file_ppt;
        if (mime.equals("application/zip")
                || mime.equals("application/x-rar-compressed")
                || mime.equals("application/x-7z-compressed")
                || mime.equals("application/x-tar")
                || mime.equals("application/gzip")) return R.drawable.ic_file_zip;
        if (mime.equals("application/vnd.android.package-archive")) return R.drawable.ic_file_apk;
        return R.drawable.ic_file;
    }

    /** 依副檔名推測圖示資源 ID。 */
    public static int guessIcon(String fileName) {
        if (fileName == null) return R.drawable.ic_file;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".heic")
                || lower.endsWith(".heif")
                || lower.endsWith(".bmp")) return R.drawable.ic_image;
        if (lower.endsWith(".mp4")
                || lower.endsWith(".mkv")
                || lower.endsWith(".avi")
                || lower.endsWith(".mov")
                || lower.endsWith(".webm")
                || lower.endsWith(".3gp")) return R.drawable.ic_file_video;
        if (lower.endsWith(".mp3")
                || lower.endsWith(".flac")
                || lower.endsWith(".aac")
                || lower.endsWith(".wav")
                || lower.endsWith(".ogg")
                || lower.endsWith(".m4a")) return R.drawable.ic_file_audio;
        if (lower.endsWith(".pdf")) return R.drawable.ic_file_pdf;
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return R.drawable.ic_file_word;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return R.drawable.ic_file_excel;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return R.drawable.ic_file_ppt;
        if (lower.endsWith(".zip")
                || lower.endsWith(".rar")
                || lower.endsWith(".7z")
                || lower.endsWith(".tar")
                || lower.endsWith(".gz")) return R.drawable.ic_file_zip;
        if (lower.endsWith(".apk")) return R.drawable.ic_file_apk;
        return R.drawable.ic_file;
    }
}
