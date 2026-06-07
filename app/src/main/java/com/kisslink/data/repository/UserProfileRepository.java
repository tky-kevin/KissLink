package com.kisslink.data.repository;

import android.content.Context;
import android.content.SharedPreferences;

import com.kisslink.model.BusinessCard;
import com.kisslink.model.UserProfile;

public class UserProfileRepository {

    private static final String PREFS_NAME      = "user_profile";
    private static final String KEY_USER_NAME   = "user_name";
    private static final String KEY_USER_AVATAR = "user_avatar_uri";
    private static final String KEY_USER_BIO    = "user_bio";

    private static final String KEY_CARD_NAME   = "card_name";
    private static final String KEY_CARD_BIO    = "card_bio";
    private static final String KEY_CARD_AVATAR = "card_avatar_uri";
    private static final String KEY_CARD_IG     = "card_ig";
    private static final String KEY_CARD_LINE   = "card_line";
    private static final String KEY_CARD_SCHOOL = "card_school";
    private static final String KEY_CARD_MAJOR  = "card_major";

    private static volatile UserProfileRepository instance;
    private final SharedPreferences prefs;

    private UserProfileRepository(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static UserProfileRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (UserProfileRepository.class) {
                if (instance == null) instance = new UserProfileRepository(context);
            }
        }
        return instance;
    }

    public UserProfile getUserProfile() {
        return new UserProfile(
                prefs.getString(KEY_USER_NAME,   ""),
                prefs.getString(KEY_USER_AVATAR, null),
                prefs.getString(KEY_USER_BIO,    "")
        );
    }

    public void saveUserProfile(UserProfile profile) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_USER_NAME, profile.getName())
                .putString(KEY_USER_BIO,  orEmpty(profile.getBio()));
        if (profile.getAvatarUri() != null) {
            editor.putString(KEY_USER_AVATAR, profile.getAvatarUri());
        } else {
            editor.remove(KEY_USER_AVATAR);
        }
        editor.apply();
    }

    public BusinessCard getBusinessCard() {
        return new BusinessCard(
                prefs.getString(KEY_CARD_NAME,   ""),
                prefs.getString(KEY_CARD_BIO,    ""),
                prefs.getString(KEY_CARD_AVATAR, null),
                prefs.getString(KEY_CARD_IG,     ""),
                prefs.getString(KEY_CARD_LINE,   ""),
                prefs.getString(KEY_CARD_SCHOOL, ""),
                prefs.getString(KEY_CARD_MAJOR,  "")
        );
    }

    public void saveBusinessCard(BusinessCard card) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_CARD_NAME,   orEmpty(card.getName()))
                .putString(KEY_CARD_BIO,    orEmpty(card.getBio()))
                .putString(KEY_CARD_IG,     orEmpty(card.getIg()))
                .putString(KEY_CARD_LINE,   orEmpty(card.getLineId()))
                .putString(KEY_CARD_SCHOOL, orEmpty(card.getSchool()))
                .putString(KEY_CARD_MAJOR,  orEmpty(card.getMajor()));
        if (card.getAvatarUri() != null) {
            editor.putString(KEY_CARD_AVATAR, card.getAvatarUri());
        } else {
            editor.remove(KEY_CARD_AVATAR);
        }
        editor.apply();
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
