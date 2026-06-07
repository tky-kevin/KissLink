package com.kisslink.nfc;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import com.kisslink.model.BusinessCard;
import com.kisslink.model.GroupCredential;

import java.io.IOException;

/**
 * 接收方使用的 NFC Reader 管理器。
 *
 * <p>偵測到 HCE payload 後，依 type 欄位分派：
 * <ul>
 *   <li>"wifi"（或無 type）→ {@link OnCredentialReceived}</li>
 *   <li>"card"            → {@link OnCardReceived}</li>
 * </ul>
 */
public class NFCManager {

    private static final String TAG = "NFCManager";

    public interface OnCredentialReceived {
        void onReceived(GroupCredential credential);
    }

    public interface OnCardReceived {
        void onReceived(BusinessCard card);
    }

    public interface OnNfcError {
        void onError(String message);
    }

    private final NfcAdapter nfcAdapter;

    public NFCManager(Activity activity) {
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
    }

    public boolean isNfcAvailable() { return nfcAdapter != null; }
    public boolean isNfcEnabled()   { return nfcAdapter != null && nfcAdapter.isEnabled(); }

    // ══════════════════════════════════════════════════════════
    //  Reader 模式
    // ══════════════════════════════════════════════════════════

    /**
     * 啟用 NFC Reader 模式，同時處理 Wi-Fi 憑證與名片兩種 payload。
     */
    public void enableReaderMode(Activity activity,
                                 OnCredentialReceived onReceived,
                                 OnCardReceived onCardReceived,
                                 OnNfcError onError) {
        if (!isNfcAvailable()) {
            if (onError != null) onError.onError("此裝置不支援 NFC");
            return;
        }
        if (!isNfcEnabled()) {
            if (onError != null) onError.onError("請先在設定中開啟 NFC");
            return;
        }

        nfcAdapter.enableReaderMode(
                activity,
                tag -> handleTag(tag, onReceived, onCardReceived, onError),
                NfcAdapter.FLAG_READER_NFC_A
                        | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null);
        Log.d(TAG, "NFC Reader mode enabled");
    }

    /**
     * 向下相容的 3-param 版本（僅接收 Wi-Fi 憑證，忽略名片）。
     */
    public void enableReaderMode(Activity activity,
                                 OnCredentialReceived onReceived,
                                 OnNfcError onError) {
        enableReaderMode(activity, onReceived, null, onError);
    }

    public void disableReaderMode(Activity activity) {
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(activity);
            Log.d(TAG, "NFC Reader mode disabled");
        }
    }

    // ══════════════════════════════════════════════════════════
    //  標籤處理
    // ══════════════════════════════════════════════════════════

    private void handleTag(Tag tag,
                           OnCredentialReceived onReceived,
                           OnCardReceived onCardReceived,
                           OnNfcError onError) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            Log.w(TAG, "Tag does not support ISO-DEP");
            if (onError != null) onError.onError("不支援的 NFC 標籤類型");
            return;
        }

        try {
            isoDep.connect();
            isoDep.setTimeout(3000);

            byte[] selectApdu = APDUHelper.buildSelectAidApdu();
            Log.d(TAG, "Sending SELECT AID...");
            byte[] response = isoDep.transceive(selectApdu);

            byte[] payload = APDUHelper.extractPayload(response);
            if (payload == null) {
                Log.e(TAG, "Invalid APDU response: " + bytesToHex(response));
                if (onError != null) onError.onError("NFC 回應格式錯誤，請確認對方已就緒");
                return;
            }

            String type = NFCCredential.parseType(payload);
            if (NFCCredential.TYPE_CARD.equals(type)) {
                BusinessCard card = NFCCredential.cardFromBytes(payload);
                Log.i(TAG, "Business card received via NFC: " + card.getName());
                if (onCardReceived != null) onCardReceived.onReceived(card);
            } else {
                GroupCredential credential = NFCCredential.fromBytes(payload);
                Log.i(TAG, "Credential received via NFC: " + credential);
                if (onReceived != null) onReceived.onReceived(credential);
            }

        } catch (IOException e) {
            Log.e(TAG, "IsoDep transceive error", e);
            if (onError != null) onError.onError("NFC 通訊失敗，請再試一次");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Payload parse error", e);
            if (onError != null) onError.onError("資料格式錯誤：" + e.getMessage());
        } finally {
            try { isoDep.close(); } catch (IOException ignored) {}
        }
    }

    private static String bytesToHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}
