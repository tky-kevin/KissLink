package com.kisslink.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.data.repository.UserProfileRepository;
import com.kisslink.model.BusinessCard;
import com.kisslink.model.UserProfile;
import com.kisslink.ui.ThemeManager;

public class SettingsActivity extends AppCompatActivity {

    private ShapeableImageView ivAvatar;
    private EditText etName, etBio, etSchool, etMajor, etIg, etLineId;
    private String currentAvatarUri;
    private UserProfileRepository repo;

    private final ActivityResultLauncher<String> avatarPicker =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) {}
                        currentAvatarUri = uri.toString();
                        ivAvatar.setImageURI(uri);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        repo = UserProfileRepository.getInstance(this);

        ivAvatar = findViewById(R.id.ivSettingsAvatar);
        etName   = findViewById(R.id.etName);
        etBio    = findViewById(R.id.etBio);
        etSchool = findViewById(R.id.etSchool);
        etMajor  = findViewById(R.id.etMajor);
        etIg     = findViewById(R.id.etIg);
        etLineId = findViewById(R.id.etLineId);

        LinearLayout avatarClickTarget = findViewById(R.id.avatarClickTarget);
        avatarClickTarget.setOnClickListener(v -> avatarPicker.launch("image/*"));

        MaterialButton btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> save());

        findViewById(R.id.btnSettingsBack).setOnClickListener(v -> finish());

        loadProfile();
    }

    private void loadProfile() {
        UserProfile profile = repo.getUserProfile();
        etName.setText(profile.getName());
        etBio.setText(profile.getBio());
        currentAvatarUri = profile.getAvatarUri();
        if (currentAvatarUri != null) {
            ivAvatar.setImageURI(Uri.parse(currentAvatarUri));
        }

        BusinessCard card = repo.getBusinessCard();
        etSchool.setText(card.getSchool());
        etMajor.setText(card.getMajor());
        etIg.setText(card.getIg());
        etLineId.setText(card.getLineId());
    }

    private void save() {
        String name = trim(etName);
        String bio  = trim(etBio);

        repo.saveUserProfile(new UserProfile(name, currentAvatarUri, bio));

        repo.saveBusinessCard(new BusinessCard(
                name, bio, currentAvatarUri,
                trim(etIg), trim(etLineId),
                trim(etSchool), trim(etMajor)
        ));

        Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private static String trim(EditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }
}
