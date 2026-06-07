package com.kisslink.ui.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.data.repository.UserProfileRepository;
import com.kisslink.model.UserProfile;
import com.kisslink.ui.ThemeManager;
import com.kisslink.ui.pairing.PairingActivity;
import com.kisslink.ui.settings.SettingsActivity;
import com.kisslink.util.PermissionHelper;

import java.util.ArrayList;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class MainActivity extends AppCompatActivity {

    private ShapeableImageView ivProfileAvatar;
    private TextView tvProfileName;
    private UserProfileRepository profileRepo;

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris == null || uris.isEmpty()) return;
                        ArrayList<Uri> selected = new ArrayList<>();
                        for (Uri uri : uris) {
                            try {
                                getContentResolver().takePersistableUriPermission(
                                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException ignored) {}
                            selected.add(uri);
                        }
                        startActivity(
                                PairingActivity.newIntent(this, PairingActivity.Role.SENDER, selected));
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profileRepo = UserProfileRepository.getInstance(this);

        ivProfileAvatar = findViewById(R.id.ivProfileAvatar);
        tvProfileName   = findViewById(R.id.tvProfileName);

        LinearLayout cardSend    = findViewById(R.id.cardSend);
        LinearLayout cardReceive = findViewById(R.id.cardReceive);
        LinearLayout btnHistory  = findViewById(R.id.btnHistory);
        LinearLayout btnProfile  = findViewById(R.id.btnProfile);

        cardSend.setOnClickListener(v    -> startSend());
        cardReceive.setOnClickListener(v -> startReceive());
        btnHistory.setOnClickListener(v  -> startActivity(new Intent(this, HistoryActivity.class)));
        btnProfile.setOnClickListener(v  -> startActivity(new Intent(this, SettingsActivity.class)));

        ivProfileAvatar.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfile();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.allGranted(grantResults)) {
            Toast.makeText(this, "需要 Wi-Fi 與檔案權限才能使用", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshProfile() {
        UserProfile profile = profileRepo.getUserProfile();
        String name = profile.getName();
        tvProfileName.setText((name != null && !name.isEmpty()) ? name : "設定你的名字");

        String avatarUri = profile.getAvatarUri();
        if (avatarUri != null) {
            ivProfileAvatar.setImageURI(Uri.parse(avatarUri));
        } else {
            ivProfileAvatar.setImageResource(R.drawable.avatar_placeholder);
        }
    }

    private void startSend() {
        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
            return;
        }
        filePickerLauncher.launch(new String[]{"*/*"});
    }

    private void startReceive() {
        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
            return;
        }
        startActivity(PairingActivity.newIntent(this, PairingActivity.Role.RECEIVER, null));
    }
}
