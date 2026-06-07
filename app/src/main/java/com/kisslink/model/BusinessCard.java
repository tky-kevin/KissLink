package com.kisslink.model;

import android.os.Parcel;
import android.os.Parcelable;

public class BusinessCard implements Parcelable {

    private String name;
    private String bio;
    private String avatarUri;
    private String ig;
    private String lineId;
    private String school;
    private String major;

    public BusinessCard() {}

    public BusinessCard(String name, String bio, String avatarUri,
                        String ig, String lineId, String school, String major) {
        this.name      = name;
        this.bio       = bio;
        this.avatarUri = avatarUri;
        this.ig        = ig;
        this.lineId    = lineId;
        this.school    = school;
        this.major     = major;
    }

    protected BusinessCard(Parcel in) {
        name      = in.readString();
        bio       = in.readString();
        avatarUri = in.readString();
        ig        = in.readString();
        lineId    = in.readString();
        school    = in.readString();
        major     = in.readString();
    }

    public static final Creator<BusinessCard> CREATOR = new Creator<BusinessCard>() {
        @Override
        public BusinessCard createFromParcel(Parcel in) { return new BusinessCard(in); }
        @Override
        public BusinessCard[] newArray(int size)        { return new BusinessCard[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(bio);
        dest.writeString(avatarUri);
        dest.writeString(ig);
        dest.writeString(lineId);
        dest.writeString(school);
        dest.writeString(major);
    }

    public String getName()      { return name; }
    public String getBio()       { return bio; }
    public String getAvatarUri() { return avatarUri; }
    public String getIg()        { return ig; }
    public String getLineId()    { return lineId; }
    public String getSchool()    { return school; }
    public String getMajor()     { return major; }

    public void setName(String name)           { this.name = name; }
    public void setBio(String bio)             { this.bio = bio; }
    public void setAvatarUri(String avatarUri) { this.avatarUri = avatarUri; }
    public void setIg(String ig)               { this.ig = ig; }
    public void setLineId(String lineId)       { this.lineId = lineId; }
    public void setSchool(String school)       { this.school = school; }
    public void setMajor(String major)         { this.major = major; }

    public boolean hasName() {
        return name != null && !name.trim().isEmpty();
    }
}
