package com.kisslink.ui.profile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.profile.Profile;
import com.kisslink.profile.ProfileStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 個人名片——置中圓角卡片對話框（背景暗化＋模糊，右上角關閉）。
 *
 * <p>檢視模式：頭像、姓名、欄位唯讀，底部「修改 / 傳送名片」。按修改進編輯模式可換頭像、
 * 改姓名、增刪欄位。按傳送名片 → 卡片縮小飛向對方的有機動畫後，直接傳送（無視待傳清單）。
 */
public class ProfileCardSheet extends DialogFragment {

    public interface Host {
        void onProfileChanged();
        void sendMyProfileCard();
    }

    private View card;
    private ShapeableImageView ivAvatar, ivBadge;
    private EditText etName;
    private LinearLayout fieldsContainer;
    private MaterialButton btnEditToggle, btnAddField, btnSendCard;

    private boolean editing = false;
    private final List<View> fieldRows = new ArrayList<>();

    // 套用裁切後的結果：AvatarCropActivity 已存好頭像,這裡只需重繪並通知主畫面。
    private final ActivityResultLauncher<Intent> avatarCropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                    renderAvatar();
                    notifyHost();
                }
            });

    // 選圖後不直接存,先進圓形裁切頁讓使用者指定顯示範圍。
    private final ActivityResultLauncher<PickVisualMediaRequest> avatarPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) return;
                avatarCropLauncher.launch(AvatarCropActivity.newIntent(requireContext(), uri));
            });

    public static ProfileCardSheet newInstance() { return new ProfileCardSheet(); }

    @Override
    public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        setStyle(STYLE_NO_TITLE, R.style.Theme_KissLink_CardDialog);
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        return inflater.inflate(R.layout.dialog_profile, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        CardDialogs.applyWindow(getDialog());
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
        card            = v.findViewById(R.id.card);
        ivAvatar        = v.findViewById(R.id.ivCardAvatar);
        ivBadge         = v.findViewById(R.id.ivAvatarEditBadge);
        etName          = v.findViewById(R.id.etName);
        fieldsContainer = v.findViewById(R.id.fieldsContainer);
        btnEditToggle   = v.findViewById(R.id.btnEditToggle);
        btnAddField     = v.findViewById(R.id.btnAddField);
        btnSendCard     = v.findViewById(R.id.btnSendCard);
        v.findViewById(R.id.btnClose).setOnClickListener(x -> dismiss());
        // 全螢幕視窗：點卡片外的透明區域 → 關閉（卡片本身為 NestedScrollView，會自行吃掉點擊）
        v.findViewById(R.id.dialogRoot).setOnClickListener(x -> { if (isCancelable()) dismiss(); });

        Profile p = ProfileStore.get(requireContext()).load();
        etName.setText(p.getName());
        if (p.getName().isEmpty()) etName.setHint(ProfileStore.get(requireContext()).name());
        for (Profile.Field f : p.getFields()) addFieldRow(f.getLabel(), f.getValue());
        renderAvatar();

        btnEditToggle.setOnClickListener(x -> toggleEdit());
        btnAddField.setOnClickListener(x -> { addFieldRow("", ""); applyEditState(); });
        btnSendCard.setOnClickListener(x -> flyAndSend());
        View.OnClickListener avatarClick = x -> { if (editing) avatarPicker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()); };
        ivAvatar.setOnClickListener(avatarClick);
        ivBadge.setOnClickListener(avatarClick);

        applyEditState();
    }

    // ── 傳送：名片往上後翻（繞 X 軸）、上浮縮小淡出後送出；縮小採減速（前快後慢＝遠離快再放慢）──
    private void flyAndSend() {
        if (editing) saveAndExit();

        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(card.getHeight() / 2f);
        // 加大相機距離，降低 3D 翻轉的透視變形，讓後翻自然不誇張
        card.setCameraDistance(8000f * getResources().getDisplayMetrics().density);

        ObjectAnimator flip = ObjectAnimator.ofFloat(card, View.ROTATION_X, 0f, 118f); // 往後翻
        ObjectAnimator lift = ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, 0f, -card.getHeight() * 1.0f);
        ObjectAnimator sx = ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, 0.4f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, 0.4f);
        ObjectAnimator fade = ObjectAnimator.ofFloat(card, View.ALPHA, 1f, 0f);

        // 背景暗化 + 模糊同步淡出：避免卡片飛走後黑底/模糊還殘留一小段才回到主畫面
        final Window win = getDialog() != null ? getDialog().getWindow() : null;
        ValueAnimator clearBg = ValueAnimator.ofFloat(1f, 0f);
        clearBg.addUpdateListener(a -> {
            if (win == null) return;
            float pr = (float) a.getAnimatedValue();
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.dimAmount = 0.55f * pr;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lp.setBlurBehindRadius((int) (48 * pr));
            }
            win.setAttributes(lp);
        });

        AnimatorSet set = new AnimatorSet();
        set.playTogether(flip, lift, sx, sy, fade, clearBg);
        set.setDuration(640);
        // 減速：前半段快速縮小遠離、後半段放慢
        set.setInterpolator(new DecelerateInterpolator(1.8f));
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                card.setLayerType(View.LAYER_TYPE_NONE, null);
                if (getActivity() instanceof Host) ((Host) getActivity()).sendMyProfileCard();
                dismissAllowingStateLoss();
            }
        });
        card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        set.start();
    }

    // ── 編輯切換 ──
    private void toggleEdit() {
        if (editing) saveAndExit();
        else { editing = true; applyEditState(); }
    }

    private void applyEditState() {
        etName.setEnabled(editing);
        btnAddField.setVisibility(editing ? View.VISIBLE : View.GONE);
        ivBadge.setVisibility(editing ? View.VISIBLE : View.GONE);
        btnEditToggle.setText(editing ? R.string.profile_done : R.string.profile_edit);
        btnEditToggle.setIconResource(editing ? R.drawable.ic_check : R.drawable.ic_edit);
        for (View row : fieldRows) {
            row.findViewById(R.id.etFieldLabel).setEnabled(editing);
            row.findViewById(R.id.etFieldValue).setEnabled(editing);
            row.findViewById(R.id.ibRemoveField).setVisibility(editing ? View.VISIBLE : View.GONE);
            row.setVisibility(editing || !rowBlank(row) ? View.VISIBLE : View.GONE);
        }
    }

    private boolean rowBlank(View row) {
        return ((EditText) row.findViewById(R.id.etFieldLabel)).getText().toString().trim().isEmpty()
                && ((EditText) row.findViewById(R.id.etFieldValue)).getText().toString().trim().isEmpty();
    }

    private void saveAndExit() {
        Profile p = Profile.empty();
        p.setName(etName.getText().toString().trim());
        for (View row : new ArrayList<>(fieldRows)) {
            String l = ((EditText) row.findViewById(R.id.etFieldLabel)).getText().toString().trim();
            String val = ((EditText) row.findViewById(R.id.etFieldValue)).getText().toString().trim();
            if (l.isEmpty() && val.isEmpty()) { fieldsContainer.removeView(row); fieldRows.remove(row); continue; }
            p.addField(new Profile.Field(l, val));
        }
        ProfileStore.get(requireContext()).save(p);
        editing = false;
        applyEditState();
        notifyHost();
    }

    private void addFieldRow(String label, String value) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_profile_field, fieldsContainer, false);
        ((EditText) row.findViewById(R.id.etFieldLabel)).setText(label);
        ((EditText) row.findViewById(R.id.etFieldValue)).setText(value);
        ImageButton remove = row.findViewById(R.id.ibRemoveField);
        remove.setOnClickListener(x -> { fieldsContainer.removeView(row); fieldRows.remove(row); });
        fieldsContainer.addView(row);
        fieldRows.add(row);
    }

    private void renderAvatar() {
        // 真實頭像與預設字符走同一條顯示路徑(固定 centerCrop/零內距),避免切換內距時被縮小/裁切。
        ivAvatar.setPadding(0, 0, 0, 0);
        ivAvatar.setImageBitmap(ProfileStore.get(requireContext()).loadAvatarForDisplay(AVATAR_DISPLAY_PX));
    }

    private static final int AVATAR_DISPLAY_PX = 256;

    private void notifyHost() {
        if (getActivity() instanceof Host) ((Host) getActivity()).onProfileChanged();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (editing) saveAndExit();
        super.onDismiss(dialog);
    }
}
