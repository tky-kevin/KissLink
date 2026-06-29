package com.kisslink.nfc;

import com.kisslink.model.GroupCredential;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * NFC 傳輸用的憑證封裝，負責 {@link GroupCredential} ↔ JSON bytes 的序列化。
 *
 * <p>JSON 格式（最大 ~120 bytes，遠低於 APDU 上限）：
 *
 * <pre>
 *   {"s":"DIRECT-KL-AB12","p":"SecurePass16","i":"192.168.49.1","t":47890}
 * </pre>
 */
public final class NFCCredential {

    private NFCCredential() {}

    /** 將 {@link GroupCredential} 序列化為 UTF-8 JSON bytes，供 HCE 回應使用。 */
    public static byte[] toBytes(GroupCredential cred) {
        try {
            JSONObject json = new JSONObject();
            json.put("s", cred.getSsid());
            json.put("p", cred.getPassphrase());
            json.put("i", cred.getGoIpAddress());
            json.put("t", cred.getTransferPort());
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new RuntimeException("NFCCredential serialization error", e);
        }
    }

    /**
     * 將 NFC APDU response 的 payload bytes 反序列化為 {@link GroupCredential}。
     *
     * @param bytes 不含末尾 SW_OK（90 00）的 payload
     * @throws IllegalArgumentException 若格式不符
     */
    public static GroupCredential fromBytes(byte[] bytes) {
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            return new GroupCredential(
                    o.getString("s"), o.getString("p"), o.getString("i"), o.getInt("t"));
        } catch (JSONException e) {
            throw new IllegalArgumentException("NFCCredential parse error: " + e.getMessage(), e);
        }
    }
}
