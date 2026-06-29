package com.kisslink.wifidirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kisslink.wifidirect.WifiBand.Band;
import org.junit.Test;

/**
 * {@link WifiBand} 頻段分類的純單元測試。
 *
 * <p>鎖住 5GHz 門檻 {@link WifiBand#BAND_5GHZ_MIN_MHZ}（4900 MHz）與其邊界——此門檻 同時被 {@code
 * WifiDirectManager.canHostFastGroup} 用於「當 GO 是否會落在慢頻段」的決策， 任何漂移都會直接影響配對頻段選擇與 SCC 診斷標籤。
 */
public class WifiBandTest {

    // ── of(): 分類 ────────────────────────────────────────────

    @Test
    public void classifies24GHzChannels() {
        assertEquals(Band.BAND_2_4GHZ, WifiBand.of(2412)); // ch 1
        assertEquals(Band.BAND_2_4GHZ, WifiBand.of(2437)); // ch 6
        assertEquals(Band.BAND_2_4GHZ, WifiBand.of(2484)); // ch 14
    }

    @Test
    public void classifies5GHzChannels() {
        assertEquals(Band.BAND_5GHZ, WifiBand.of(5180)); // ch 36
        assertEquals(Band.BAND_5GHZ, WifiBand.of(5825)); // ch 165
    }

    @Test
    public void higherBandsAlsoCountAsFast() {
        // 5.9GHz / 6GHz（Wi-Fi 6E）≥ 門檻 → 仍歸入「快頻段」（非 2.4GHz）。
        assertEquals(Band.BAND_5GHZ, WifiBand.of(5955));
        assertEquals(Band.BAND_5GHZ, WifiBand.of(6175));
    }

    @Test
    public void nonPositiveFrequencyIsUnknown() {
        assertEquals(Band.UNKNOWN, WifiBand.of(0)); // 未連線
        assertEquals(Band.UNKNOWN, WifiBand.of(-1)); // 取不到
    }

    // ── 門檻邊界 ──────────────────────────────────────────────

    @Test
    public void thresholdBoundary_isInclusiveFor5GHz() {
        assertEquals(Band.BAND_2_4GHZ, WifiBand.of(WifiBand.BAND_5GHZ_MIN_MHZ - 1)); // 4899
        assertEquals(Band.BAND_5GHZ, WifiBand.of(WifiBand.BAND_5GHZ_MIN_MHZ)); // 4900
    }

    // ── label() ───────────────────────────────────────────────

    @Test
    public void labelsAreHumanReadable() {
        assertEquals("2.4GHz", WifiBand.label(2437));
        assertEquals("5GHz", WifiBand.label(5180));
        assertEquals("未知", WifiBand.label(0));
    }

    // ── isFastBand(): 與 canHostFastGroup 共用的「快頻段」判定 ──

    @Test
    public void isFastBand_trueFor5GHzAndUnknown_falseFor24GHz() {
        assertTrue(WifiBand.isFastBand(5180)); // 5GHz → 快
        assertTrue(WifiBand.isFastBand(0)); // 未知（未連 STA）→ 視為可當快 GO
        assertFalse(WifiBand.isFastBand(2412)); // 2.4GHz → 慢
    }
}
