package com.kisslink.nfc;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link APDUHelper} 的純邏輯單元測試——NFC APDU 的建構與解析。
 *
 * <p>這是 reader↔HCE 交握的線上格式，任何位元組偏移錯誤都會讓配對在裝置上靜默失敗，
 * 卻不易在整合測試中重現，故以純 JUnit 完整覆蓋編解碼與邊界。
 */
public class APDUHelperTest {

    // ── buildSelectAidApdu ────────────────────────────────────

    @Test
    public void buildSelectAidApdu_hasCorrectHeaderAndAid() {
        byte[] apdu = APDUHelper.buildSelectAidApdu();

        // 5 byte header + AID + 1 byte Le
        assertEquals(5 + APDUHelper.AID.length + 1, apdu.length);
        assertEquals((byte) 0x00, apdu[0]);       // CLA
        assertEquals((byte) 0xA4, apdu[1]);       // INS = SELECT
        assertEquals((byte) 0x04, apdu[2]);       // P1  = by name
        assertEquals((byte) 0x00, apdu[3]);       // P2
        assertEquals((byte) APDUHelper.AID.length, apdu[4]); // Lc

        for (int i = 0; i < APDUHelper.AID.length; i++) {
            assertEquals("AID byte " + i, APDUHelper.AID[i], apdu[5 + i]);
        }
        assertEquals((byte) 0x00, apdu[apdu.length - 1]); // Le
    }

    @Test
    public void buildSelectAidApdu_isAcceptedByIsSelectAid() {
        // round-trip：自己建的 SELECT 必被自己的辨識器接受
        assertTrue(APDUHelper.isSelectAid(APDUHelper.buildSelectAidApdu()));
    }

    // ── isSelectAid ───────────────────────────────────────────

    @Test
    public void isSelectAid_acceptsClass04Variant() {
        byte[] apdu = APDUHelper.buildSelectAidApdu();
        apdu[0] = 0x04; // 部分讀卡機送 CLA=0x04，仍應接受
        assertTrue(APDUHelper.isSelectAid(apdu));
    }

    @Test
    public void isSelectAid_rejectsNull() {
        assertFalse(APDUHelper.isSelectAid(null));
    }

    @Test
    public void isSelectAid_rejectsTooShort() {
        assertFalse(APDUHelper.isSelectAid(new byte[4]));
    }

    @Test
    public void isSelectAid_rejectsWrongInstruction() {
        byte[] apdu = APDUHelper.buildSelectAidApdu();
        apdu[1] = (byte) 0xB0; // 非 SELECT
        assertFalse(APDUHelper.isSelectAid(apdu));
    }

    @Test
    public void isSelectAid_rejectsWrongP1() {
        byte[] apdu = APDUHelper.buildSelectAidApdu();
        apdu[2] = 0x00; // 非 by-name
        assertFalse(APDUHelper.isSelectAid(apdu));
    }

    @Test
    public void isSelectAid_rejectsWrongLc() {
        byte[] apdu = APDUHelper.buildSelectAidApdu();
        apdu[4] = (byte) (APDUHelper.AID.length + 1);
        assertFalse(APDUHelper.isSelectAid(apdu));
    }

    @Test
    public void isSelectAid_rejectsForeignAid() {
        byte[] apdu = APDUHelper.buildSelectAidApdu();
        apdu[5] = (byte) 0xAB; // 竄改第一個 AID 位元組
        assertFalse(APDUHelper.isSelectAid(apdu));
    }

    // ── buildOkResponse / extractPayload ──────────────────────

    @Test
    public void buildOkResponse_appendsStatusWord() {
        byte[] payload = {1, 2, 3};
        byte[] resp = APDUHelper.buildOkResponse(payload);

        assertEquals(payload.length + 2, resp.length);
        assertEquals(1, resp[0]);
        assertEquals(2, resp[1]);
        assertEquals(3, resp[2]);
        assertEquals((byte) 0x90, resp[resp.length - 2]);
        assertEquals((byte) 0x00, resp[resp.length - 1]);
    }

    @Test
    public void buildOkResponse_thenExtractPayload_roundTrips() {
        byte[] payload = "{\"s\":\"DIRECT-KL-AB12\"}".getBytes();
        byte[] extracted = APDUHelper.extractPayload(APDUHelper.buildOkResponse(payload));
        assertArrayEquals(payload, extracted);
    }

    @Test
    public void extractPayload_emptyPayloadYieldsEmptyArray() {
        byte[] extracted = APDUHelper.extractPayload(APDUHelper.SW_OK.clone());
        assertNotNull(extracted);
        assertEquals(0, extracted.length);
    }

    @Test
    public void extractPayload_rejectsNonOkStatusWord() {
        // payload + 條件不滿足（69 85）→ 非成功 → null
        byte[] resp = {7, 7, (byte) 0x69, (byte) 0x85};
        assertNull(APDUHelper.extractPayload(resp));
    }

    @Test
    public void extractPayload_rejectsNull() {
        assertNull(APDUHelper.extractPayload(null));
    }

    @Test
    public void extractPayload_rejectsTooShort() {
        assertNull(APDUHelper.extractPayload(new byte[]{(byte) 0x90}));
    }
}
