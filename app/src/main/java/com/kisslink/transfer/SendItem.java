package com.kisslink.transfer;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kisslink.util.FileUtils;

/**
 * 一個待送出的項目(雙向 peer 模型下,任一端都能送)。
 * 來源可為 {@link Uri}(檔案 / 照片)、raw bytes(名片 vCard)或純文字。
 */
public final class SendItem {

    public final byte    itemType;  // TransferProtocol.ITEM_*
    public final String  name;
    public final String  mime;
    public final long    size;      // bytes；未知為 -1
    @Nullable public final Uri    uri;    // FILE / PHOTO
    @Nullable public final byte[] bytes;  // VCARD / TEXT

    private SendItem(byte itemType, String name, String mime, long size,
                     @Nullable Uri uri, @Nullable byte[] bytes) {
        this.itemType = itemType;
        this.name = name;
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.size = size;
        this.uri = uri;
        this.bytes = bytes;
    }

    /** 由 SAF/MediaStore Uri 建立檔案項目。 */
    public static SendItem fromUri(@NonNull ContentResolver cr, @NonNull Uri uri, byte itemType) {
        String name = FileUtils.getFileName(cr, uri);
        long   size = FileUtils.getFileSize(cr, uri);
        String mime = cr.getType(uri);
        return new SendItem(itemType, name, mime, size, uri, null);
    }

    /** 名片(vCard)：直接以 bytes 攜帶。{@code fileName} 為顯示用檔名（例如「漫遊者的名片」）。 */
    public static SendItem vcard(@NonNull String fileName, @NonNull byte[] vcf) {
        String fn = fileName.isEmpty() ? "contact" : fileName;
        return new SendItem(TransferProtocol.ITEM_VCARD, fn, "text/vcard", vcf.length, null, vcf);
    }

    /** 純文字/連結。{@code content} 為全文（顯示於列表時 layout 會自動截斷）；以 UTF-8 bytes 攜帶。 */
    public static SendItem text(@NonNull String content) {
        byte[] b = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new SendItem(TransferProtocol.ITEM_TEXT, content, "text/plain", b.length, null, b);
    }
}
