package com.kisslink.model;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link GroupCredential} 的 JVM 單元測試（不需裝置）。
 *
 * <p>執行方式：Android Studio → 右鍵此檔案 → Run 'GroupCredentialTest' 或在終端機：{@code ./gradlew test}
 */
public class GroupCredentialTest {

    // ── 建構 ──────────────────────────────────────────────────

    @Test
    public void constructor_storesAllFields() {
        GroupCredential cred =
                new GroupCredential("DIRECT-KL-AB12", "SecurePass99", "192.168.49.1", 47890);

        assertEquals("DIRECT-KL-AB12", cred.getSsid());
        assertEquals("SecurePass99", cred.getPassphrase());
        assertEquals("192.168.49.1", cred.getGoIpAddress());
        assertEquals(47890, cred.getTransferPort());
    }

    // ── SSID 格式驗證 ──────────────────────────────────────────

    @Test
    public void ssid_startsWithDirectPrefix() {
        GroupCredential cred = makeCred("DIRECT-KL-1A2B");
        assertTrue(
                "SSID 必須以 'DIRECT-' 開頭（Wi-Fi Direct 規範要求）", cred.getSsid().startsWith("DIRECT-"));
    }

    @Test
    public void ssid_doesNotExceedWifiNameLimit() {
        // Wi-Fi SSID 上限 32 bytes
        GroupCredential cred = makeCred("DIRECT-KL-AB12");
        assertTrue(cred.getSsid().length() <= 32);
    }

    // ── Passphrase 格式驗證 ───────────────────────────────────

    @Test
    public void passphrase_meetsWpa2MinimumLength() {
        // WPA2 最短 8 個字元
        GroupCredential cred = makeCred("DIRECT-KL-AB12");
        assertTrue("Passphrase 長度必須 >= 8（WPA2 要求）", cred.getPassphrase().length() >= 8);
    }

    // ── IP 格式驗證 ────────────────────────────────────────────

    @Test
    public void goIpAddress_matchesAndroidP2pConvention() {
        // Android Wi-Fi Direct GO 的 IP 固定為 192.168.49.1
        GroupCredential cred = makeCred("DIRECT-KL-AB12");
        assertEquals("GO IP 應為 Android 保留的 192.168.49.1", "192.168.49.1", cred.getGoIpAddress());
    }

    // ── Port 範圍驗證 ──────────────────────────────────────────

    @Test
    public void transferPort_isInValidRange() {
        GroupCredential cred = makeCred("DIRECT-KL-AB12");
        int port = cred.getTransferPort();
        assertTrue("Port 必須在 1024–65535 範圍內", port >= 1024 && port <= 65535);
    }

    // ── toString ──────────────────────────────────────────────

    @Test
    public void toString_containsSsidAndIpPort() {
        GroupCredential cred =
                new GroupCredential("DIRECT-KL-AB12", "SecurePass99", "192.168.49.1", 47890);
        String str = cred.toString();

        assertTrue("toString 應含 SSID", str.contains("DIRECT-KL-AB12"));
        assertTrue("toString 應含 IP", str.contains("192.168.49.1"));
        assertTrue("toString 應含 Port", str.contains("47890"));
        // Passphrase 不應出現在 toString（安全考量）
        assertFalse("toString 不應洩漏 Passphrase", str.contains("SecurePass99"));
    }

    // ── helper ────────────────────────────────────────────────

    private static GroupCredential makeCred(String ssid) {
        return new GroupCredential(ssid, "SecurePass99", "192.168.49.1", 47890);
    }
}
