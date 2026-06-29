package com.kisslink.nfc;

import android.net.Uri;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.kisslink.pairing.PairingToken;
import java.nio.charset.StandardCharsets;

/**
 * reader 端 NFC 讀取工具——對被碰到的標籤(對方 HCE)送 SELECT AID,取回並解析對方的 {@link PairingToken}。純函式、無狀態,供 {@link
 * com.kisslink.pairing.NfcPairingController} 使用。
 *
 * <p>對應的「被讀」端(peripheral/HCE)是 {@link KissLinkHCEService}。
 */
public final class NfcTokenReader {

    private static final String TAG = "NfcTokenReader";

    private NfcTokenReader() {}

    /**
     * 對 HCE 送 SELECT AID,取回 token bytes 並解析。在背景執行緒呼叫。 重試數次:碰觸瞬間射頻常不穩(transient「tag lost / connect
     * 失敗」),快速重試多半能救回。
     *
     * @return 解析出的對方 token;讀取/解析失敗回 {@code null}。
     */
    @Nullable
    public static PairingToken readToken(@NonNull Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) return null;
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    if (!iso.isConnected()) iso.connect();
                    iso.setTimeout(5000);
                    byte[] resp = iso.transceive(APDUHelper.buildSelectAidApdu());
                    byte[] payload = APDUHelper.extractPayload(resp);
                    if (payload != null) {
                        return PairingToken.fromUri(
                                Uri.parse(new String(payload, StandardCharsets.UTF_8)));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "readToken attempt " + attempt + " failed: " + e.getMessage());
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return null;
        } finally {
            try {
                iso.close();
            } catch (Exception ignored) {
            }
        }
    }
}
