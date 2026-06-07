package com.kisslink.model;

public class UserProfile {

    private String name;
    private String avatarUri;
    private String bio;

    public UserProfile() {}

    public UserProfile(String name, String avatarUri) {
        this.name      = name;
        this.avatarUri = avatarUri;
    }

    public UserProfile(String name, String avatarUri, String bio) {
        this.name      = name;
        this.avatarUri = avatarUri;
        this.bio       = bio;
    }

    public String getName()      { return name; }
    public String getAvatarUri() { return avatarUri; }
    public String getBio()       { return bio; }

    public void setName(String name)           { this.name = name; }
    public void setAvatarUri(String avatarUri) { this.avatarUri = avatarUri; }
    public void setBio(String bio)             { this.bio = bio; }
}
