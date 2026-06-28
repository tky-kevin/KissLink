package com.kisslink.pairing;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Random;

/**
 * {@link PairingToken} 的純邏輯單元測試，聚焦 GO 選舉 {@link PairingToken#shouldBeGroupOwner}。
 *
 * <p>選舉必須<b>反對稱</b>：雙方各持相同的兩份 token 計算，必得相反結果，否則會雙雙搶當 GO
 * 或雙雙退讓 → 連線失敗。此性質難以在裝置整合測試中覆蓋所有分支，故以純 JUnit 驗證。
 *
 * <p>刻意避開 {@code toUri/fromUri/nonceB64}——它們依賴 {@code android.net.Uri} 與
 * {@code android.util.Base64}，需 Robolectric；本檔只測不碰 Android framework 的純方法。
 */
public class PairingTokenTest {

    private static PairingToken token(int goIntent, boolean canHost5G, byte... nonce) {
        byte[] n = new byte[PairingToken.NONCE_LEN];
        System.arraycopy(nonce, 0, n, 0, Math.min(nonce.length, n.length));
        return new PairingToken(PairingToken.VERSION, n, goIntent, "dev", canHost5G);
    }

    // ── 頻段相容性優先（規則 1）─────────────────────────────────

    @Test
    public void bandCompat_restrictedDeviceBecomesOwner_evenWithLowerGoIntent() {
        // 被 2.4GHz 綁住者（canHost5G=false）優先當 GO，群組才落在兩端都能加入的頻段。
        PairingToken restricted = token(0, false, (byte) 1);   // 低 goIntent 但受限
        PairingToken capable    = token(255, true, (byte) 2);  // 高 goIntent 但能跑 5G

        assertTrue("受限端應當 GO", restricted.shouldBeGroupOwner(capable));
        assertFalse("能跑 5G 端應退讓", capable.shouldBeGroupOwner(restricted));
    }

    // ── goIntent tiebreak（規則 2）──────────────────────────────

    @Test
    public void goIntent_higherWins_whenBandEqual() {
        PairingToken hi = token(200, true, (byte) 1);
        PairingToken lo = token(100, true, (byte) 2);

        assertTrue(hi.shouldBeGroupOwner(lo));
        assertFalse(lo.shouldBeGroupOwner(hi));
    }

    // ── nonce tiebreak（規則 3）─────────────────────────────────

    @Test
    public void nonce_lexicographicallyLargerWins_whenGoIntentEqual() {
        PairingToken bigNonce   = token(50, true, (byte) 0x02);
        PairingToken smallNonce = token(50, true, (byte) 0x01);

        assertTrue(bigNonce.shouldBeGroupOwner(smallNonce));
        assertFalse(smallNonce.shouldBeGroupOwner(bigNonce));
    }

    @Test
    public void nonce_comparedUnsigned() {
        // 0x80 應大於 0x01（無號比較）——若用有號 byte 比較會判反。
        PairingToken high = token(50, true, (byte) 0x80);
        PairingToken low  = token(50, true, (byte) 0x01);

        assertTrue(high.shouldBeGroupOwner(low));
        assertFalse(low.shouldBeGroupOwner(high));
    }

    // ── 同分（天文機率）：雙方皆 false，交由上層重抽 ──────────────

    @Test
    public void identicalTokens_neitherBecomesOwner() {
        PairingToken a = token(50, true, (byte) 0x05);
        PairingToken b = token(50, true, (byte) 0x05);

        assertFalse(a.shouldBeGroupOwner(b));
        assertFalse(b.shouldBeGroupOwner(a));
    }

    // ── 反對稱性：隨機壓力測試 ──────────────────────────────────

    @Test
    public void shouldBeGroupOwner_isAntisymmetric_overRandomTokens() {
        Random rng = new Random(42); // 固定種子 → 可重現
        for (int i = 0; i < 5000; i++) {
            byte[] n1 = new byte[PairingToken.NONCE_LEN];
            byte[] n2 = new byte[PairingToken.NONCE_LEN];
            rng.nextBytes(n1);
            rng.nextBytes(n2);
            PairingToken a = new PairingToken(1, n1, rng.nextInt(256), "a", rng.nextBoolean());
            PairingToken b = new PairingToken(1, n2, rng.nextInt(256), "b", rng.nextBoolean());

            boolean aOwns = a.shouldBeGroupOwner(b);
            boolean bOwns = b.shouldBeGroupOwner(a);
            // 8 隨機位元組碰撞機率 ~2^-64，視為恆不同 → 必反對稱。
            assertNotEquals("antisymmetry violated at i=" + i, aOwns, bOwns);
        }
    }

    // ── 建構子：goIntent 遮罩到 0..255 ──────────────────────────

    @Test
    public void constructor_masksGoIntentToByte() {
        assertEquals(0, token(256, true, (byte) 1).goIntent);   // 256 & 0xFF
        assertEquals(44, token(300, true, (byte) 1).goIntent);  // 300 & 0xFF
        assertEquals(255, token(-1, true, (byte) 1).goIntent);  // -1 & 0xFF
    }

    @Test
    public void constructor_nullDeviceNameBecomesEmpty() {
        PairingToken t = new PairingToken(1, new byte[PairingToken.NONCE_LEN], 0, null);
        assertEquals("", t.deviceName);
    }

    @Test
    public void constructor_defaultsCanHost5GTrue() {
        PairingToken t = new PairingToken(1, new byte[PairingToken.NONCE_LEN], 0, "x");
        assertTrue(t.canHost5G);
    }

    // ── withPayload：保留身分、只換酬載 ─────────────────────────

    @Test
    public void withPayload_keepsIdentity_swapsPayload() {
        byte[] nonce = {9, 8, 7, 6, 5, 4, 3, 2};
        PairingToken orig = new PairingToken(1, nonce, 123, "old", true);
        PairingToken next = orig.withPayload("new", false);

        // 身分凍結
        assertEquals(orig.version, next.version);
        assertArrayEquals(orig.nonce, next.nonce);
        assertEquals(orig.goIntent, next.goIntent);
        // 酬載已換
        assertEquals("new", next.deviceName);
        assertFalse(next.canHost5G);
        // 選舉結果不因換酬載而漂移（goIntent/nonce 相同、canHost5G 相同時）
        PairingToken peer = token(50, false, (byte) 1);
        assertEquals(orig.withPayload("x", false).shouldBeGroupOwner(peer),
                next.shouldBeGroupOwner(peer));
    }

    // ── sameSession ───────────────────────────────────────────

    @Test
    public void sameSession_matchesOnNonceOnly() {
        byte[] nonce = {1, 2, 3, 4, 5, 6, 7, 8};
        PairingToken a = new PairingToken(1, nonce.clone(), 10, "a", true);
        PairingToken b = new PairingToken(1, nonce.clone(), 99, "b", false); // 不同酬載/goIntent

        assertTrue("nonce 相同即同場次", a.sameSession(b));
    }

    @Test
    public void sameSession_differsOnDifferentNonce() {
        PairingToken a = token(10, true, (byte) 1);
        PairingToken b = token(10, true, (byte) 2);
        assertFalse(a.sameSession(b));
    }

    @Test
    public void sameSession_nullIsFalse() {
        assertFalse(token(10, true, (byte) 1).sameSession(null));
    }
}
