package com.kisslink.nfc;

/**
 * APDU（Application Protocol Data Unit）建構與解析工具。
 *
 * <h3>KissLink 自訂 AID</h3>
 * <pre>
 *   F0:4B:49:53:53  (F0 開頭表示私有，後四字節 = "KISS" ASCII)
 * </pre>
 *
 * <h3>SELECT AID APDU 格式</h3>
 * <pre>
 *   CLA INS  P1   P2   Lc  AID...             Le
 *   00  A4   04   00   05  F0 4B 49 53 53      00
 * </pre>
 *
 * <h3>成功回應格式</h3>
 * <pre>
 *   [credential JSON bytes] + 90 00
 * </pre>
 */
public final class APDUHelper {

    private APDUHelper() {}

    // ── AID ────────────────────────────────────────────────────
    public static final byte[] AID = {(byte)0xF0, 0x4B, 0x49, 0x53, 0x53};

    // ── Status Words ───────────────────────────────────────────
    /** 成功 */
    public static final byte[] SW_OK                   = {(byte)0x90, 0x00};
    /** 未知指令 */
    public static final byte[] SW_UNKNOWN_COMMAND      = {0x00, 0x00};
    /** 條件不滿足（憑證尚未就緒）*/
    public static final byte[] SW_CONDITIONS_NOT_MET   = {(byte)0x69, (byte)0x85};
    /** 錯誤 */
    public static final byte[] SW_ERROR                = {(byte)0x6F, 0x00};

    // ══════════════════════════════════════════════════════════
    //  Reader 端：建立 SELECT AID APDU
    // ══════════════════════════════════════════════════════════

    /** 建立發往 HCE 的 SELECT AID APDU 指令。 */
    public static byte[] buildSelectAidApdu() {
        // 00 A4 04 00 [Lc] [AID bytes] 00
        byte[] apdu = new byte[5 + AID.length + 1];
        apdu[0] = 0x00;              // CLA
        apdu[1] = (byte)0xA4;        // INS = SELECT
        apdu[2] = 0x04;              // P1  = by name (AID)
        apdu[3] = 0x00;              // P2
        apdu[4] = (byte) AID.length; // Lc
        System.arraycopy(AID, 0, apdu, 5, AID.length);
        apdu[5 + AID.length] = 0x00; // Le  = 任意長度
        return apdu;
    }

    // ══════════════════════════════════════════════════════════
    //  HCE 端：判斷 APDU 類型
    // ══════════════════════════════════════════════════════════

    /**
     * 判斷收到的 APDU 是否為 SELECT AID 且 AID 符合本應用。
     *
     * <p>Android HCE 框架雖已依 AID 過濾，但仍需確認 INS/P1/Lc 正確。
     */
    public static boolean isSelectAid(byte[] apdu) {
        if (apdu == null || apdu.length < 5 + AID.length) return false;
        if (apdu[0] != 0x00 && apdu[0] != 0x04)  return false; // CLA
        if (apdu[1] != (byte)0xA4)                return false; // INS = SELECT
        if (apdu[2] != 0x04)                      return false; // P1  = by name
        int lc = apdu[4] & 0xFF;
        if (lc != AID.length)                     return false;
        for (int i = 0; i < AID.length; i++) {
            if (apdu[5 + i] != AID[i])            return false;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════
    //  HCE 端：建立回應
    // ══════════════════════════════════════════════════════════

    /** 將 payload 封裝為 APDU 成功回應（payload + 0x90 0x00）。 */
    public static byte[] buildOkResponse(byte[] payload) {
        byte[] resp = new byte[payload.length + 2];
        System.arraycopy(payload, 0, resp, 0, payload.length);
        resp[payload.length]     = (byte)0x90;
        resp[payload.length + 1] = 0x00;
        return resp;
    }

    // ══════════════════════════════════════════════════════════
    //  Reader 端：解析回應
    // ══════════════════════════════════════════════════════════

    /** 確認回應末尾為 SW_OK，並擷取 payload。 */
    public static byte[] extractPayload(byte[] response) {
        if (response == null || response.length < 2) return null;
        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;
        if (sw1 != 0x90 || sw2 != 0x00) return null; // 非成功回應
        byte[] payload = new byte[response.length - 2];
        System.arraycopy(response, 0, payload, 0, payload.length);
        return payload;
    }
}
