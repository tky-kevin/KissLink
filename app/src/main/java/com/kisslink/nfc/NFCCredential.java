package com.kisslink.nfc;

import com.kisslink.model.BusinessCard;
import com.kisslink.model.GroupCredential;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * NFC payload serialization / deserialization.
 *
 * Wi-Fi format:
 * {"type":"wifi","s":"DIRECT-KL-AB12","p":"SecurePass16","i":"192.168.49.1","t":47890}
 *
 * Card format (v2 — student fields):
 * {"type":"card","n":"王小明","b":"bio text","ig":"@handle","li":"lineId","sc":"school","mj":"major"}
 */
public final class NFCCredential {

    public static final String TYPE_WIFI = "wifi";
    public static final String TYPE_CARD = "card";

    private NFCCredential() {}

    public static byte[] toBytes(GroupCredential cred) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_WIFI);
            json.put("s", cred.getSsid());
            json.put("p", cred.getPassphrase());
            json.put("i", cred.getGoIpAddress());
            json.put("t", cred.getTransferPort());
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new RuntimeException("NFCCredential serialization error", e);
        }
    }

    public static byte[] toCardBytes(BusinessCard card) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_CARD);
            putIfNotEmpty(json, "n",  card.getName());
            putIfNotEmpty(json, "b",  card.getBio());
            putIfNotEmpty(json, "ig", card.getIg());
            putIfNotEmpty(json, "li", card.getLineId());
            putIfNotEmpty(json, "sc", card.getSchool());
            putIfNotEmpty(json, "mj", card.getMajor());
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new RuntimeException("NFCCredential card serialization error", e);
        }
    }

    public static String parseType(byte[] bytes) {
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return o.optString("type", TYPE_WIFI);
        } catch (JSONException e) {
            return TYPE_WIFI;
        }
    }

    public static GroupCredential fromBytes(byte[] bytes) {
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return new GroupCredential(
                    o.getString("s"),
                    o.getString("p"),
                    o.getString("i"),
                    o.getInt("t")
            );
        } catch (JSONException e) {
            throw new IllegalArgumentException("NFCCredential parse error: " + e.getMessage(), e);
        }
    }

    public static BusinessCard cardFromBytes(byte[] bytes) {
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return new BusinessCard(
                    o.optString("n",  ""),
                    o.optString("b",  ""),
                    null,
                    o.optString("ig", ""),
                    o.optString("li", ""),
                    o.optString("sc", ""),
                    o.optString("mj", "")
            );
        } catch (JSONException e) {
            throw new IllegalArgumentException("NFCCredential card parse error: " + e.getMessage(), e);
        }
    }

    private static void putIfNotEmpty(JSONObject json, String key, String value)
            throws JSONException {
        if (value != null && !value.isEmpty()) json.put(key, value);
    }
}
