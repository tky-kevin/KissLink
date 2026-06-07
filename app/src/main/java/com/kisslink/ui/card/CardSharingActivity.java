package com.kisslink.ui.card;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.data.repository.UserProfileRepository;
import com.kisslink.model.UserProfile;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.ui.ThemeManager;

public class CardSharingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_sharing);

        Button btnCancel = findViewById(R.id.btnCardSharingCancel);
        btnCancel.setOnClickListener(v -> {
            KissLinkHCEService.clearCredential();
            finish();
        });

        UserProfileRepository repo = UserProfileRepository.getInstance(this);
        UserProfile profile = repo.getUserProfile();

        ShapeableImageView ivAvatar = findViewById(R.id.ivSharingAvatar);
        TextView tvName = findViewById(R.id.tvSharingName);

        if (profile != null) {
            String name = profile.getName();
            if (name != null && !name.isEmpty()) {
                tvName.setText(name);
            }
            String avatarUri = profile.getAvatarUri();
            if (avatarUri != null) {
                ivAvatar.setImageURI(Uri.parse(avatarUri));
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        KissLinkHCEService.clearCredential();
    }
}
