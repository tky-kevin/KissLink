package com.kisslink.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.kisslink.pairing.PairingToken;

import java.nio.charset.StandardCharsets;

/**
 * NFC Host Card Emulation——以<b>自訂 AID {@code F04B495353}</b> 對外回應本機配對 token。
 *
 * <h3>為何用自訂 AID(而非標準 NDEF Type-4 AID)?</h3>
 * <p>標準 NDEF AID {@code D2760000850101} 會與每台 Android 內建的
 * {@code com.android.nfc.ndef_nfcee.NdefNfceeS​ervice}(「嵌入式標記」)<b>搶同一個 AID</b>;
 * 當本 App 不在前景(無法 setPreferredService)時,系統無法自動二選一 → 跳出卡片模擬
 * 衝突選擇器(Samsung 的 ConflictResolverActivity)。改用私有 AID 後完全不與系統服務衝突。
 *
 * <h3>交握(reader 用 IsoDep)</h3>
 * <pre>
 *   reader → SELECT AID F04B495353
 *   HCE    → [token UTF-8 bytes] + 90 00
 * </pre>
 * reader 端見 {@link NfcTokenReader} / {@link com.kisslink.pairing.NfcPairingController}。
 */
public class KissLinkHCEService extends HostApduService {

    private static final String TAG = "KissLinkHCEService";

    /** 當前要回應的 token bytes(= {@link PairingToken#toUri()} 的 UTF-8)。 */
    private static volatile byte[] tokenBytes = null;

    /** 被讀(latch 為 tag)時的通知;在 NFC 執行緒呼叫,實作端自行切回主執行緒。 */
    public interface OnTagReadListener { void onTagRead(); }
    @Nullable private static volatile OnTagReadListener readListener;

    // ══════════════════════════════════════════════════════════
    //  靜態 API
    // ══════════════════════════════════════════════════════════

    /** 設定本機這場配對要對外回應的 token。 */
    public static void setActiveToken(PairingToken token) {
        tokenBytes = token.toUri().getBytes(StandardCharsets.UTF_8);
        Log.d(TAG, "Active token set (" + tokenBytes.length + " bytes)");
    }

    public static void clearToken() {
        tokenBytes = null;
        Log.d(TAG, "Active token cleared");
    }

    public static void setOnTagReadListener(@Nullable OnTagReadListener l) {
        readListener = l;
    }

    // ══════════════════════════════════════════════════════════
    //  HostApduService 回調
    // ══════════════════════════════════════════════════════════

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (!APDUHelper.isSelectAid(apdu)) {
            return APDUHelper.SW_UNKNOWN_COMMAND;
        }
        byte[] payload = tokenBytes;
        if (payload == null) {
            Log.w(TAG, "SELECT AID but no active token");
            return APDUHelper.SW_CONDITIONS_NOT_MET;
        }
        // 被讀 → 通知(latch 為 tag)。
        OnTagReadListener l = readListener;
        if (l != null) {
            try { l.onTagRead(); } catch (Exception e) { Log.w(TAG, "readListener error", e); }
        }
        Log.i(TAG, "Responding token (" + payload.length + " bytes)");
        return APDUHelper.buildOkResponse(payload);
    }

    @Override
    public void onDeactivated(int reason) {
        Log.d(TAG, "HCE deactivated: "
                + (reason == DEACTIVATION_LINK_LOSS ? "LINK_LOSS" : "DESELECTED"));
    }
}
