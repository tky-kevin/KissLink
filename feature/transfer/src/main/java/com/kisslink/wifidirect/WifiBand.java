package com.kisslink.wifidirect;

/**
 * Wi-Fi 頻率（MHz）→ 頻段的純分類工具。
 *
 * <p>為何重要：Single-Channel-Concurrency（SCC）會把 Wi-Fi Direct 的 P2P 群組頻段 釘在 GO 既有 STA 連線的頻段上；STA 落在
 * 2.4GHz 時，P2P 也被迫 2.4GHz → 吞吐遠低於 5GHz。 把協商頻段分類出來，可在診斷（FlightRecorder dump）中解釋「為何傳輸偏慢」。
 *
 * <p>純函式、不碰 Android framework，便於單元測試。門檻 {@link #BAND_5GHZ_MIN_MHZ} 與 {@code
 * WifiDirectManager.canHostFastGroup} 共用，確保「快頻段」判定單一真相。
 */
public final class WifiBand {

    private WifiBand() {}

    /** ≥ 此頻率（MHz）視為 5GHz（含 5GHz、5.9GHz、6GHz 等高頻段）。 */
    public static final int BAND_5GHZ_MIN_MHZ = 4900;

    public enum Band {
        BAND_2_4GHZ,
        BAND_5GHZ,
        UNKNOWN
    }

    /** 由頻率分類頻段。{@code freqMhz <= 0}（未連線/取不到）→ {@link Band#UNKNOWN}。 */
    public static Band of(int freqMhz) {
        if (freqMhz <= 0) return Band.UNKNOWN;
        return freqMhz >= BAND_5GHZ_MIN_MHZ ? Band.BAND_5GHZ : Band.BAND_2_4GHZ;
    }

    /** 人類可讀標籤：{@code "2.4GHz"} / {@code "5GHz"} / {@code "未知"}。 */
    public static String label(int freqMhz) {
        switch (of(freqMhz)) {
            case BAND_5GHZ:
                return "5GHz";
            case BAND_2_4GHZ:
                return "2.4GHz";
            default:
                return "未知";
        }
    }

    /** 是否為「快頻段」（5GHz 或未知；2.4GHz 為慢）。供當 GO 前的頻段可行性判定。 */
    public static boolean isFastBand(int freqMhz) {
        return of(freqMhz) != Band.BAND_2_4GHZ;
    }
}
