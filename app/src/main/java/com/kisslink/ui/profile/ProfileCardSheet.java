package com.kisslink.ui.profile;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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

    private final ActivityResultLauncher<PickVisualMediaRequest> avatarPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) return;
                if (ProfileStore.get(requireContext()).setAvatarFromUri(uri)) {
                    renderAvatar();
                    notifyHost();
                }
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

    // ── 傳送：卡片縮小飛向對方（有機感）後直接送 ──
    private void flyAndSend() {
        if (editing) saveAndExit();
        card.animate()
                .scaleX(0.28f).scaleY(0.28f)
                .translationY(-card.getHeight() * 0.7f)
                .alpha(0f)
                .setDuration(460)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (getActivity() instanceof Host) ((Host) getActivity()).sendMyProfileCard();
                    dismissAllowingStateLoss();
                })
                .start();
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
        Bitmap a = ProfileStore.get(requireContext()).loadAvatar();
        if (a != null) {
            ivAvatar.setPadding(0, 0, 0, 0);
            ivAvatar.setImageBitmap(a);
        } else {
            int pad = Math.round(26 * getResources().getDisplayMetrics().density);
            ivAvatar.setPadding(pad, pad, pad, pad);
            ivAvatar.setImageResource(R.drawable.ic_avatar_default);
        }
    }

    private void notifyHost() {
        if (getActivity() instanceof Host) ((Host) getActivity()).onProfileChanged();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (editing) saveAndExit();
        super.onDismiss(dialog);
    }
}
