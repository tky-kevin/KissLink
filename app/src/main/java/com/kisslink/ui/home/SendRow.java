package com.kisslink.ui.home;

import android.net.Uri;
import androidx.annotation.Nullable;
import com.kisslink.transfer.TransferProtocol;

/** Beam 清單一列的顯示資料（已選待送 / 傳輸中 / 接收中皆共用）。 */
public final class SendRow {
    public final String name;
    public final String sizeLabel; // 例如 "4.2 MB"，未知為 ""
    public final byte itemType; // TransferProtocol.ITEM_*
    @Nullable public Uri thumbUri; // 相片/影片縮圖來源（檔案為 null）；接收端收完後才補上
    @Nullable public Uri fileUri; // 檔案 Uri（用於點擊開啟）；接收端收完後才補上
    @Nullable public String mime; // MIME 類型

    public boolean done = false;
    public int percent = -1; // 傳輸中 0..100；-1 表示尚未/不適用
    public boolean incoming = false; // true=接收中（對方送來）
    public boolean removable = false; // true=待傳清單，顯示移除鈕
    public boolean highlight = false; // true=傳輸中當前列，底色高亮（accent_soft）

    public SendRow(String name, String sizeLabel, byte itemType, @Nullable Uri thumbUri) {
        this(name, sizeLabel, itemType, thumbUri, null, null);
    }

    public SendRow(
            String name,
            String sizeLabel,
            byte itemType,
            @Nullable Uri thumbUri,
            @Nullable String mime) {
        this(name, sizeLabel, itemType, thumbUri, null, mime);
    }

    public SendRow(
            String name,
            String sizeLabel,
            byte itemType,
            @Nullable Uri thumbUri,
            @Nullable Uri fileUri,
            @Nullable String mime) {
        this.name = name;
        this.sizeLabel = sizeLabel == null ? "" : sizeLabel;
        this.itemType = itemType;
        this.thumbUri = thumbUri;
        this.fileUri = fileUri;
        this.mime = mime;
    }

    public boolean isVisualMedia() {
        return itemType == TransferProtocol.ITEM_PHOTO;
    }
}
