package com.kisslink.ui.card;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    private ShapeableImageView ivCardAvatar;
    private TextView tvCardName, tvCardBio;
    private LinearLayout rowSchool, rowIg, rowLine;
    private TextView tvCardSchoolMajor, tvCardIg, tvCardLine;

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

        ivCardAvatar       = findViewById(R.id.ivCardAvatar);
        tvCardName         = findViewById(R.id.tvCardName);
        tvCardBio          = findViewById(R.id.tvCardBio);
        rowSchool          = findViewById(R.id.rowSchool);
        rowIg              = findViewById(R.id.rowIg);
        rowLine            = findViewById(R.id.rowLine);
        tvCardSchoolMajor  = findViewById(R.id.tvCardSchoolMajor);
        tvCardIg           = findViewById(R.id.tvCardIg);
        tvCardLine         = findViewById(R.id.tvCardLine);

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

        loadCard();
    }

    private void loadCard() {
        BusinessCard card = repo.getBusinessCard();
        UserProfile profile = repo.getUserProfile();
        populateViewMode(card, profile);
        setMode(false);
    }

    private void populateViewMode(BusinessCard card, UserProfile profile) {
        tvCardName.setText(card.hasName() ? card.getName() : "—");

        String bio = card.getBio();
        if (bio != null && !bio.isEmpty()) {
            tvCardBio.setText(bio);
            tvCardBio.setVisibility(View.VISIBLE);
        } else {
            tvCardBio.setVisibility(View.GONE);
        }

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
            rowSchool.setVisibility(View.VISIBLE);
        } else {
            rowSchool.setVisibility(View.GONE);
        }

        String ig = card.getIg();
        if (ig != null && !ig.isEmpty()) {
            tvCardIg.setText(ig.startsWith("@") ? ig : "@" + ig);
            rowIg.setVisibility(View.VISIBLE);
        } else {
            rowIg.setVisibility(View.GONE);
        }

        String lineId = card.getLineId();
        if (lineId != null && !lineId.isEmpty()) {
            tvCardLine.setText(lineId);
            rowLine.setVisibility(View.VISIBLE);
        } else {
            rowLine.setVisibility(View.GONE);
        }

        String avatarUri = (card.getAvatarUri() != null && !card.getAvatarUri().isEmpty())
                ? card.getAvatarUri()
                : (profile != null ? profile.getAvatarUri() : null);
        if (avatarUri != null) {
            ivCardAvatar.setImageURI(Uri.parse(avatarUri));
        } else {
            ivCardAvatar.setImageResource(R.drawable.avatar_placeholder);
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
