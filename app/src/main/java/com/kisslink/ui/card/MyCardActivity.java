package com.kisslink.ui.card;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.data.repository.UserProfileRepository;
import com.kisslink.model.BusinessCard;
import com.kisslink.model.UserProfile;
import com.kisslink.ui.ThemeManager;

public class MyCardActivity extends AppCompatActivity {

    private View viewModeContainer;
    private View editModeContainer;
    private Button btnEditCard;
    private Button btnShareCard;

    private EditText etMyCardName, etMyCardBio, etMyCardSchool;
    private EditText etMyCardMajor, etMyCardIg, etMyCardLineId;

    private UserProfileRepository repo;
    private boolean isEditMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_card);

        repo = UserProfileRepository.getInstance(this);

        viewModeContainer  = findViewById(R.id.viewModeContainer);
        editModeContainer  = findViewById(R.id.editModeContainer);
        btnEditCard        = findViewById(R.id.btnEditCard);
        btnShareCard       = findViewById(R.id.btnShareCard);

        etMyCardName    = findViewById(R.id.etMyCardName);
        etMyCardBio     = findViewById(R.id.etMyCardBio);
        etMyCardSchool  = findViewById(R.id.etMyCardSchool);
        etMyCardMajor   = findViewById(R.id.etMyCardMajor);
        etMyCardIg      = findViewById(R.id.etMyCardIg);
        etMyCardLineId  = findViewById(R.id.etMyCardLineId);

        findViewById(R.id.btnMyCardBack).setOnClickListener(v -> finish());
        btnEditCard.setOnClickListener(v -> {
            if (isEditMode) saveCard();
            else enterEditMode();
        });
        btnShareCard.setOnClickListener(v ->
                startActivity(new Intent(this, CardSharingActivity.class)));

        loadCard();
    }

    private void loadCard() {
        BusinessCard card = repo.getBusinessCard();
        UserProfile profile = repo.getUserProfile();
        populateViewMode(card, profile);
        setMode(false);
    }

    private void populateViewMode(BusinessCard card, UserProfile profile) {
        // 姓名
        TextView tvCardName = findViewById(R.id.tvCardName);
        if (tvCardName != null) {
            tvCardName.setText(card.hasName() ? card.getName() : "—");
        }

        // Bio
        TextView tvCardBio = findViewById(R.id.tvCardBio);
        if (tvCardBio != null) {
            String bio = card.getBio();
            if (bio != null && !bio.isEmpty()) {
                tvCardBio.setText(bio);
                tvCardBio.setVisibility(View.VISIBLE);
            } else {
                tvCardBio.setVisibility(View.GONE);
            }
        }

        // 學校 · 科系
        TextView tvCardSchoolMajor = findViewById(R.id.tvCardSchoolMajor);
        if (tvCardSchoolMajor != null) {
            String school = card.getSchool();
            String major  = card.getMajor();
            if ((school != null && !school.isEmpty()) || (major != null && !major.isEmpty())) {
                StringBuilder sb = new StringBuilder();
                if (school != null && !school.isEmpty()) sb.append(school);
                if (major  != null && !major.isEmpty()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(major);
                }
                tvCardSchoolMajor.setText(sb.toString());
                tvCardSchoolMajor.setVisibility(View.VISIBLE);
            } else {
                tvCardSchoolMajor.setVisibility(View.GONE);
            }
        }

        // IG
        TextView tvCardIg = findViewById(R.id.tvCardIg);
        if (tvCardIg != null) {
            String ig = card.getIg();
            if (ig != null && !ig.isEmpty()) {
                tvCardIg.setText("📸 " + (ig.startsWith("@") ? ig : "@" + ig));
                tvCardIg.setVisibility(View.VISIBLE);
            } else {
                tvCardIg.setVisibility(View.GONE);
            }
        }

        // LINE
        TextView tvCardLine = findViewById(R.id.tvCardLine);
        if (tvCardLine != null) {
            String lineId = card.getLineId();
            if (lineId != null && !lineId.isEmpty()) {
                tvCardLine.setText("💬 " + lineId);
                tvCardLine.setVisibility(View.VISIBLE);
            } else {
                tvCardLine.setVisibility(View.GONE);
            }
        }

        // 頭像
        ShapeableImageView ivCardAvatar = findViewById(R.id.ivCardAvatar);
        if (ivCardAvatar != null) {
            String avatarUri = (card.getAvatarUri() != null && !card.getAvatarUri().isEmpty())
                    ? card.getAvatarUri()
                    : (profile != null ? profile.getAvatarUri() : null);
            if (avatarUri != null) {
                ivCardAvatar.setImageURI(Uri.parse(avatarUri));
            } else {
                ivCardAvatar.setImageResource(R.drawable.avatar_placeholder);
            }
        }
    }

    private void enterEditMode() {
        BusinessCard card = repo.getBusinessCard();
        etMyCardName.setText(card.getName());
        etMyCardBio.setText(card.getBio());
        etMyCardSchool.setText(card.getSchool());
        etMyCardMajor.setText(card.getMajor());
        etMyCardIg.setText(card.getIg());
        etMyCardLineId.setText(card.getLineId());
        setMode(true);
    }

    private void saveCard() {
        UserProfile profile = repo.getUserProfile();
        String avatarUri = (profile != null) ? profile.getAvatarUri() : null;

        BusinessCard card = new BusinessCard(
                trim(etMyCardName),
                trim(etMyCardBio),
                avatarUri,
                trim(etMyCardIg),
                trim(etMyCardLineId),
                trim(etMyCardSchool),
                trim(etMyCardMajor)
        );
        repo.saveBusinessCard(card);
        populateViewMode(card, profile);
        setMode(false);
        Toast.makeText(this, getString(R.string.card_saved), Toast.LENGTH_SHORT).show();
    }

    private void setMode(boolean edit) {
        isEditMode = edit;
        viewModeContainer.setVisibility(edit ? View.GONE  : View.VISIBLE);
        editModeContainer.setVisibility(edit ? View.VISIBLE : View.GONE);
        btnEditCard.setText(edit ? getString(R.string.btn_save) : getString(R.string.btn_edit));
    }

    private static String trim(EditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }
}
