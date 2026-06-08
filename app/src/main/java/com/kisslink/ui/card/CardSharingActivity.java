package com.kisslink.ui.card;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.data.repository.UserProfileRepository;
import com.kisslink.model.BusinessCard;
import com.kisslink.model.UserProfile;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.ui.ThemeManager;

public class CardSharingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_sharing);

        // 1. 取得名片資料
        UserProfileRepository repo = UserProfileRepository.getInstance(this);
        BusinessCard card    = repo.getBusinessCard();
        UserProfile  profile = repo.getUserProfile();

        // 2. 填入卡片 View
        populateCard(card, profile);

        // 3. Bug Fix：設定 HCE（之前忘記呼叫！）
        KissLinkHCEService.setBusinessCard(card);

        // 4. 播放飛入動畫
        playCardFlyUpEntry();

        // 5. 取消按鈕
        findViewById(R.id.btnCardSharingCancel).setOnClickListener(v -> {
            KissLinkHCEService.clearCredential();
            finish();
        });
    }

    private void populateCard(BusinessCard card, UserProfile profile) {
        // 姓名
        TextView tvName = findViewById(R.id.tvCardName);
        if (tvName != null) {
            tvName.setText(card != null && card.hasName() ? card.getName() : "—");
        }

        // Bio
        TextView tvBio = findViewById(R.id.tvCardBio);
        if (tvBio != null) {
            String bio = card != null ? card.getBio() : null;
            if (bio != null && !bio.isEmpty()) {
                tvBio.setText(bio);
                tvBio.setVisibility(View.VISIBLE);
            } else {
                tvBio.setVisibility(View.GONE);
            }
        }

        // 學校 · 科系
        TextView tvSchoolMajor = findViewById(R.id.tvCardSchoolMajor);
        if (tvSchoolMajor != null) {
            String school = card != null ? card.getSchool() : null;
            String major  = card != null ? card.getMajor()  : null;
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
            String ig = card != null ? card.getIg() : null;
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
            String lineId = card != null ? card.getLineId() : null;
            if (lineId != null && !lineId.isEmpty()) {
                tvLine.setText("💬 " + lineId);
                tvLine.setVisibility(View.VISIBLE);
            } else {
                tvLine.setVisibility(View.GONE);
            }
        }

        // 頭像：優先 card.getAvatarUri()，fallback profile.getAvatarUri()，再 fallback placeholder
        ShapeableImageView ivAvatar = findViewById(R.id.ivCardAvatar);
        if (ivAvatar != null) {
            String avatarUri = (card != null && card.getAvatarUri() != null && !card.getAvatarUri().isEmpty())
                    ? card.getAvatarUri()
                    : (profile != null ? profile.getAvatarUri() : null);
            if (avatarUri != null && !avatarUri.isEmpty()) {
                ivAvatar.setImageURI(Uri.parse(avatarUri));
            } else {
                ivAvatar.setImageResource(R.drawable.avatar_placeholder);
            }
        }
    }

    private void playCardFlyUpEntry() {
        // 卡片從「下方偏移」飛入到「正常位置」
        // 象徵「我的名片出現了，準備傳送」
        View card = findViewById(R.id.cardAnimRoot);
        if (card == null) return;
        card.setTranslationY(800f);
        card.setAlpha(0f);
        card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(650)
                .setInterpolator(new DecelerateInterpolator(2.0f))
                .start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        KissLinkHCEService.clearCredential();
    }
}
