package com.kisslink.ui.card;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.model.BusinessCard;
import com.kisslink.ui.ThemeManager;

public class CardDisplayActivity extends AppCompatActivity {

    public static final String EXTRA_CARD = "business_card";

    public static Intent newIntent(Context context, BusinessCard card) {
        Intent intent = new Intent(context, CardDisplayActivity.class);
        intent.putExtra(EXTRA_CARD, card);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_display);

        BusinessCard card = getIntent().getParcelableExtra(EXTRA_CARD);
        if (card == null) { finish(); return; }

        populateCard(card);

        MaterialButton btnSave = findViewById(R.id.btnSaveToContacts);
        btnSave.setOnClickListener(v -> saveToContacts(card));

        findViewById(R.id.btnDisplayBack).setOnClickListener(v -> finish());

        playCardDropEntry();
    }

    private void populateCard(BusinessCard card) {
        // 姓名
        TextView tvName = findViewById(R.id.tvCardName);
        if (tvName != null) {
            tvName.setText(card.getName() != null ? card.getName() : "");
        }

        // Bio
        TextView tvBio = findViewById(R.id.tvCardBio);
        if (tvBio != null) {
            String bio = card.getBio();
            if (bio != null && !bio.isEmpty()) {
                tvBio.setText(bio);
                tvBio.setVisibility(View.VISIBLE);
            } else {
                tvBio.setVisibility(View.GONE);
            }
        }

        // 頭像
        ShapeableImageView ivAvatar = findViewById(R.id.ivCardAvatar);
        if (ivAvatar != null) {
            String avatarUri = card.getAvatarUri();
            if (avatarUri != null && !avatarUri.isEmpty()) {
                ivAvatar.setImageURI(Uri.parse(avatarUri));
            } else {
                ivAvatar.setImageResource(R.drawable.avatar_placeholder);
            }
        }

        // 學校 · 科系
        TextView tvSchoolMajor = findViewById(R.id.tvCardSchoolMajor);
        if (tvSchoolMajor != null) {
            String school = card.getSchool();
            String major  = card.getMajor();
            if ((school != null && !school.isEmpty()) || (major != null && !major.isEmpty())) {
                StringBuilder sb = new StringBuilder();
                if (school != null && !school.isEmpty()) sb.append(school);
                if (major  != null && !major.isEmpty()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(major);
                }
                tvSchoolMajor.setText(sb.toString());
                tvSchoolMajor.setVisibility(View.VISIBLE);
            } else {
                tvSchoolMajor.setVisibility(View.GONE);
            }
        }

        // IG
        TextView tvIg = findViewById(R.id.tvCardIg);
        if (tvIg != null) {
            String ig = card.getIg();
            if (ig != null && !ig.isEmpty()) {
                tvIg.setText("📸 " + (ig.startsWith("@") ? ig : "@" + ig));
                tvIg.setVisibility(View.VISIBLE);
            } else {
                tvIg.setVisibility(View.GONE);
            }
        }

        // LINE
        TextView tvLine = findViewById(R.id.tvCardLine);
        if (tvLine != null) {
            String lineId = card.getLineId();
            if (lineId != null && !lineId.isEmpty()) {
                tvLine.setText("💬 " + lineId);
                tvLine.setVisibility(View.VISIBLE);
            } else {
                tvLine.setVisibility(View.GONE);
            }
        }
    }

    private void playCardDropEntry() {
        // 卡片從「上方」掉下來到正常位置
        // 象徵「名片從對方手機飛來了」
        View card = findViewById(R.id.cardAnimRoot);
        if (card == null) return;
        card.setTranslationY(-800f);
        card.setAlpha(0f);
        card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(700)
                .setInterpolator(new OvershootInterpolator(0.6f))
                .start();
    }

    private void saveToContacts(BusinessCard card) {
        Intent intent = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
        putIfNotEmpty(intent, ContactsContract.Intents.Insert.NAME,  card.getName());
        putIfNotEmpty(intent, ContactsContract.Intents.Insert.NOTES, buildNotes(card));

        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, getString(R.string.no_contacts_app), Toast.LENGTH_SHORT).show();
        }
    }

    private String buildNotes(BusinessCard card) {
        StringBuilder notes = new StringBuilder();
        if (notEmpty(card.getBio()))    notes.append("Bio: ").append(card.getBio()).append("\n");
        if (notEmpty(card.getSchool())) notes.append("學校: ").append(card.getSchool()).append("\n");
        if (notEmpty(card.getMajor()))  notes.append("科系: ").append(card.getMajor()).append("\n");
        if (notEmpty(card.getIg()))     notes.append("IG: ").append(card.getIg()).append("\n");
        if (notEmpty(card.getLineId())) notes.append("Line: ").append(card.getLineId()).append("\n");
        return notes.toString().trim();
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private static void putIfNotEmpty(Intent intent, String key, String value) {
        if (value != null && !value.isEmpty()) intent.putExtra(key, value);
    }
}
