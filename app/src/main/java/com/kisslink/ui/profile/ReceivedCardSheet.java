package com.kisslink.ui.profile;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.kisslink.R;
import com.kisslink.profile.Profile;

import java.util.Locale;

/**
 * 收到名片——與個人名片相同的置中卡片介面，但唯讀，底部換成「儲存到聯絡人」。
 */
public class ReceivedCardSheet extends DialogFragment {

    private static final String ARG_VCARD = "vcard";

    public static ReceivedCardSheet newInstance(@NonNull byte[] vcard) {
        ReceivedCardSheet s = new ReceivedCardSheet();
        Bundle b = new Bundle();
        b.putByteArray(ARG_VCARD, vcard);
        s.setArguments(b);
        return s;
    }

    @Override
    public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        setStyle(STYLE_NO_TITLE, R.style.Theme_KissLink_CardDialog);
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        return inflater.inflate(R.layout.dialog_received_card, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        CardDialogs.applyWindow(getDialog());
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
        byte[] vcf = getArguments() != null ? getArguments().getByteArray(ARG_VCARD) : null;
        Profile p = vcf != null ? Profile.fromVCard(vcf) : Profile.empty();

        TextView tvName = v.findViewById(R.id.tvName);
        tvName.setText(p.getName().isEmpty() ? "—" : p.getName());

        // 名片頭像（隨 vCard PHOTO 攜帶）；無則維持預設圖示。
        com.google.android.material.imageview.ShapeableImageView ivAvatar =
                v.findViewById(R.id.ivCardAvatar);
        if (p.getPhoto() != null && p.getPhoto().length > 0) {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory
                    .decodeByteArray(p.getPhoto(), 0, p.getPhoto().length);
            if (bm != null) {
                ivAvatar.setPadding(0, 0, 0, 0);
                ivAvatar.setImageBitmap(bm);
            }
        }

        ViewGroup container = v.findViewById(R.id.fieldsContainer);
        for (Profile.Field f : p.getFields()) {
            if (f.getValue() == null || f.getValue().trim().isEmpty()) continue;
            View row = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_profile_field, container, false);
            EditText l = row.findViewById(R.id.etFieldLabel);
            EditText val = row.findViewById(R.id.etFieldValue);
            l.setText(f.getLabel()); l.setEnabled(false);
            val.setText(f.getValue()); val.setEnabled(false);
            row.findViewById(R.id.ibRemoveField).setVisibility(View.GONE);
            container.addView(row);
        }

        v.findViewById(R.id.btnClose).setOnClickListener(x -> dismiss());
        // 全螢幕視窗：點卡片外的透明區域 → 關閉
        v.findViewById(R.id.dialogRoot).setOnClickListener(x -> { if (isCancelable()) dismiss(); });
        MaterialButton save = v.findViewById(R.id.btnSaveContact);
        save.setOnClickListener(x -> saveToContacts(p));

        // 反向動畫：名片往下後翻、落入定位（傳送端「往上後翻飛出」的反演）
        final View card = v.findViewById(R.id.card);
        card.setAlpha(0f);   // 先隱藏，避免入場動畫啟動前閃現一幀
        card.post(() -> playReceiveFlip(card));
    }

    /** 收到名片的入場：傳送端「後翻飛出」的反演 —— 從上方翻入、放大落定。 */
    private void playReceiveFlip(View card) {
        if (card.getHeight() == 0) return;
        card.setCameraDistance(8000f * getResources().getDisplayMetrics().density);
        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(card.getHeight() / 2f);
        final float lift = -card.getHeight() * 1.0f;

        card.setTranslationY(lift);
        card.setScaleX(0.4f);
        card.setScaleY(0.4f);
        card.setRotationX(118f);

        ObjectAnimator flip = ObjectAnimator.ofFloat(card, View.ROTATION_X, 118f, 0f);
        ObjectAnimator drop = ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, lift, 0f);
        ObjectAnimator sx = ObjectAnimator.ofFloat(card, View.SCALE_X, 0.4f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.4f, 1f);
        ObjectAnimator fade = ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f);

        // 背景暗化 + 模糊同步淡入：與傳送的淡出對稱
        final android.view.Window win = getDialog() != null ? getDialog().getWindow() : null;
        ValueAnimator bgIn = ValueAnimator.ofFloat(0f, 1f);
        bgIn.addUpdateListener(a -> {
            if (win == null) return;
            float pr = (float) a.getAnimatedValue();
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.dimAmount = 0.55f * pr;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                lp.setBlurBehindRadius((int) (48 * pr));
            }
            win.setAttributes(lp);
        });

        AnimatorSet set = new AnimatorSet();
        set.playTogether(flip, drop, sx, sy, fade, bgIn);
        set.setDuration(640);
        // 減速：先快後慢（與傳送對稱）
        set.setInterpolator(new DecelerateInterpolator(1.8f));
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                card.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        set.start();
    }

    /** 以系統聯絡人「新增」介面開啟，預填解析到的欄位。 */
    private void saveToContacts(Profile p) {
        try {
            Intent i = new Intent(ContactsContract.Intents.Insert.ACTION);
            i.setType(ContactsContract.RawContacts.CONTENT_TYPE);
            if (!p.getName().isEmpty()) i.putExtra(ContactsContract.Intents.Insert.NAME, p.getName());
            for (Profile.Field f : p.getFields()) {
                String label = f.getLabel() == null ? "" : f.getLabel().toLowerCase(Locale.ROOT);
                String val = f.getValue();
                if (val == null || val.trim().isEmpty()) continue;
                if (label.contains("電話") || label.contains("phone") || label.contains("手機") || label.contains("tel")) {
                    i.putExtra(ContactsContract.Intents.Insert.PHONE, val);
                } else if (label.contains("mail") || label.contains("信箱")) {
                    i.putExtra(ContactsContract.Intents.Insert.EMAIL, val);
                } else if (label.contains("公司") || label.contains("company") || label.contains("org")) {
                    i.putExtra(ContactsContract.Intents.Insert.COMPANY, val);
                } else if (label.contains("職") || label.contains("title")) {
                    i.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, val);
                } else if (label.contains("地址") || label.contains("addr")) {
                    i.putExtra(ContactsContract.Intents.Insert.POSTAL, val);
                } else if (label.contains("備註") || label.contains("note")) {
                    i.putExtra(ContactsContract.Intents.Insert.NOTES, val);
                }
            }
            startActivity(i);
            dismiss();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.card_no_contact_app, Toast.LENGTH_SHORT).show();
        }
    }
}
