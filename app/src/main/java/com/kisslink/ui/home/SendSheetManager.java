package com.kisslink.ui.home;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.kisslink.R;

/**
 * Builds and shows the send-sheet BottomSheetDialog (full selectable list with remove).
 *
 * <p>Keeps all the programmatic layout construction in one place so HomeActivity doesn't have to
 * deal with density math, view hierarchy, or dialog lifecycle.
 */
final class SendSheetManager {

    interface OnClearAll {
        void onClearAll();
    }

    @Nullable private BottomSheetDialog current;

    void showIfNotEmpty(
            @NonNull Activity activity,
            @NonNull SendListAdapter adapter,
            @NonNull OnClearAll onClearAll) {
        if (current != null && current.isShowing()) return;

        float d = activity.getResources().getDisplayMetrics().density;
        BottomSheetDialog dlg = new BottomSheetDialog(activity);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        content.setPadding(pad, pad, pad, Math.round(8 * d));

        // Title row (with trash icon)
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText(R.string.send_sheet_title);
        title.setTextColor(activity.getColor(R.color.beam_ink));
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        titleRow.addView(title);

        android.widget.ImageButton btnClear = new android.widget.ImageButton(activity);
        int btnSize = Math.round(36 * d);
        btnClear.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
        btnClear.setBackground(null);
        btnClear.setImageResource(R.drawable.ic_delete);
        btnClear.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int btnPad = Math.round(6 * d);
        btnClear.setPadding(btnPad, btnPad, btnPad, btnPad);
        btnClear.setOnClickListener(
                v -> {
                    onClearAll.onClearAll();
                    dismiss();
                });
        titleRow.addView(btnClear);
        content.addView(titleRow);

        // RecyclerView
        RecyclerView rv = new RecyclerView(activity);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        rv.setAdapter(adapter);
        LinearLayout.LayoutParams rvlp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rvlp.topMargin = Math.round(10 * d);
        rv.setLayoutParams(rvlp);
        content.addView(rv);

        dlg.setContentView(content);
        dlg.setOnDismissListener(
                x -> {
                    rv.setAdapter(null);
                    current = null;
                });
        current = dlg;
        dlg.show();
    }

    void dismiss() {
        if (current != null && current.isShowing()) current.dismiss();
        current = null;
    }

    boolean isShowing() {
        return current != null && current.isShowing();
    }
}
