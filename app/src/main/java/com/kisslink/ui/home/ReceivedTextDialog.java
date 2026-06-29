package com.kisslink.ui.home;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Patterns;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kisslink.R;

/**
 * 顯示「收到的文字/連結」對話框（接收列表點選文字項時彈出）。
 *
 * <p>從 {@code HomeActivity} 抽出的自包含 UI 動作：全文可選取、連結可點擊/開啟、可複製。
 */
public final class ReceivedTextDialog {

    private ReceivedTextDialog() {}

    /** 彈出對話框顯示 {@code text}；若為連結，額外提供「開啟」。 */
    public static void show(@NonNull Activity activity, @NonNull String text) {
        boolean isLink = Patterns.WEB_URL.matcher(text).matches();
        MaterialAlertDialogBuilder b =
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(isLink ? R.string.share_link_title : R.string.share_text_title)
                        .setMessage(text)
                        .setPositiveButton(
                                R.string.action_copy, (d, w) -> copyToClipboard(activity, text))
                        .setNegativeButton(R.string.btn_cancel, null);
        if (isLink) {
            b.setNeutralButton(
                    R.string.action_open,
                    (d, w) -> {
                        try {
                            activity.startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse(text))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) {
                            toast(activity, R.string.share_open_failed);
                        }
                    });
        }
        androidx.appcompat.app.AlertDialog dialog = b.show();
        TextView msgTv = dialog.findViewById(android.R.id.message);
        if (msgTv != null) {
            msgTv.setAutoLinkMask(Linkify.WEB_URLS);
            msgTv.setMovementMethod(LinkMovementMethod.getInstance());
            msgTv.setTextIsSelectable(true);
        }
    }

    private static void copyToClipboard(@NonNull Activity activity, @NonNull String text) {
        ClipboardManager cm =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("KissLink", text));
            toast(activity, R.string.share_copied);
        }
    }

    private static void toast(@NonNull Activity activity, int resId) {
        Toast.makeText(activity, activity.getString(resId), Toast.LENGTH_LONG).show();
    }
}
