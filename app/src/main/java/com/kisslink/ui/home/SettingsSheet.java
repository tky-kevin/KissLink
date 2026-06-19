package com.kisslink.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.kisslink.R;
import com.kisslink.util.ThemePrefs;

/**
 * 設定 BottomSheet——目前提供外觀模式切換（跟隨系統 / 亮色 / 深色）。
 *
 * <p>選擇後立即透過 {@link ThemePrefs#setMode} 套用並儲存偏好，
 * AppCompatDelegate 會自動重建 Activity。
 */
public class SettingsSheet extends BottomSheetDialogFragment {

    public static SettingsSheet newInstance() {
        return new SettingsSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LinearLayout rowSystem = view.findViewById(R.id.rowSystem);
        LinearLayout rowLight  = view.findViewById(R.id.rowLight);
        LinearLayout rowDark   = view.findViewById(R.id.rowDark);
        ImageView checkSystem  = view.findViewById(R.id.checkSystem);
        ImageView checkLight   = view.findViewById(R.id.checkLight);
        ImageView checkDark    = view.findViewById(R.id.checkDark);

        // 顯示目前選中的模式
        refreshChecks(checkSystem, checkLight, checkDark);

        rowSystem.setOnClickListener(v -> applyMode(ThemePrefs.MODE_SYSTEM, checkSystem, checkLight, checkDark));
        rowLight .setOnClickListener(v -> applyMode(ThemePrefs.MODE_LIGHT,  checkSystem, checkLight, checkDark));
        rowDark  .setOnClickListener(v -> applyMode(ThemePrefs.MODE_DARK,   checkSystem, checkLight, checkDark));
    }

    private void applyMode(int mode,
                           ImageView checkSystem,
                           ImageView checkLight,
                           ImageView checkDark) {
        ThemePrefs.setMode(requireContext(), mode);
        refreshChecks(checkSystem, checkLight, checkDark);
        // AppCompatDelegate 觸發 Activity 重建，dismissAllowingStateLoss 防止 FragmentManager 異常
        dismissAllowingStateLoss();
    }

    private void refreshChecks(ImageView checkSystem, ImageView checkLight, ImageView checkDark) {
        int cur = ThemePrefs.getMode(requireContext());
        setVisible(checkSystem, cur == ThemePrefs.MODE_SYSTEM);
        setVisible(checkLight,  cur == ThemePrefs.MODE_LIGHT);
        setVisible(checkDark,   cur == ThemePrefs.MODE_DARK);
    }

    private static void setVisible(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
