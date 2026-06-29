package com.kisslink.ui.home;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;
import com.kisslink.R;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.TransferProtocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 處理由「分享選單」送進來的 {@code ACTION_SEND}/{@code ACTION_SEND_MULTIPLE} intent， 將檔案/相片/純文字併入待傳清單。從 {@code
 * HomeActivity} 抽出的自包含 intent 接收邏輯。
 *
 * <p>分享的 URI 帶臨時讀取授權，於 Activity 期間有效（碰一下連線 → 傳送 在同一畫面內完成）。 處理完即把 intent 換成普通 intent（{@link
 * #consume}），避免旋轉/重綁時重複加入。
 */
public final class ShareIntentReceiver {

    private ShareIntentReceiver() {}

    /** 解析分享 intent 併入 {@code vm} 待傳清單；非分享 intent 則略過。 */
    public static void ingest(
            @NonNull Activity activity, @NonNull HomeViewModel vm, @Nullable Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean single = Intent.ACTION_SEND.equals(action);
        boolean multi = Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (!single && !multi) return;

        List<Uri> uris = new ArrayList<>();
        if (single) {
            Uri u = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (u != null) {
                uris.add(u);
            } else {
                // 沒有檔案串流 → 分享的是純文字/連結：加入待傳清單。
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (text != null && text.toString().trim().length() > 0) {
                    String content = text.toString().trim();
                    CharSequence subj = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                    if (subj != null && subj.toString().trim().length() > 0)
                        content = subj.toString().trim() + "\n\n" + content;
                    vm.addAllToSelection(Collections.singletonList(SendItem.text(content)));
                    toast(activity, activity.getString(R.string.share_added_text));
                    consume(activity);
                    return;
                }
            }
        } else {
            ArrayList<Uri> list =
                    IntentCompat.getParcelableArrayListExtra(
                            intent, Intent.EXTRA_STREAM, Uri.class);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) {
            toast(activity, activity.getString(R.string.share_unsupported));
            consume(activity);
            return;
        }

        List<SendItem> picked = new ArrayList<>();
        for (Uri uri : uris) {
            String mt = activity.getContentResolver().getType(uri);
            if (mt == null) mt = intent.getType();
            picked.add(
                    SendItem.fromUri(
                            activity.getContentResolver(),
                            uri,
                            TransferProtocol.itemTypeForMime(mt)));
        }
        vm.addAllToSelection(picked);
        toast(activity, activity.getString(R.string.share_added, uris.size()));
        consume(activity);
    }

    /** 消費掉分享 intent，換成普通 intent，避免後續生命週期重複處理。 */
    private static void consume(@NonNull Activity activity) {
        activity.setIntent(new Intent(activity, activity.getClass()));
    }

    private static void toast(@NonNull Activity activity, @NonNull String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
    }
}
