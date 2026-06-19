package com.kisslink.pairing;

import android.os.Build;

import androidx.annotation.NonNull;

/**
 * App 範圍的本機配對 token 單一來源。
 *
 * <p>為什麼需要它?碰觸配對可能從<b>任何前景畫面</b>發生(主畫面、傳輸畫面…),
 * 而 NFC 對外當標籤(HCE)需要一份 token,GO 選舉也要用<b>同一份</b> token。
 * 若各畫面各自產生 token,會出現「HCE 廣播的 token ≠ Coordinator 選舉用的 token」,
 * 或主畫面根本沒設 token(HCE 無 NDEF → 對方讀不到 → 一直震動)。
 *
 * <p>因此把本機 token 收斂到這裡:任何畫面的 {@link com.kisslink.nfc.NfcForegroundHelper}
 * 都用 {@link #current()} 設 HCE,Service 的 {@link PairingCoordinator} 也用 {@link #current()}。
 * token 在 App 進程生命週期內保持穩定(nonce 跨「不重疊」的連續場次重用無妨),
 * 確保「主畫面讀到的對方 token」與「對方 Coordinator 廣播的 token」一致。
 */
public final class LocalPairing {

    private static volatile PairingToken token;
    /** 對外顯示名稱（對方碰一下即看到）。預設裝置型號，可由名片姓名覆寫。 */
    private static volatile String displayName = Build.MODEL;
    /** 本機若當 GO 是否能在 5GHz 開群組（供 GO 選舉偏置）。未知時樂觀 true。 */
    private static volatile boolean canHost5G = true;

    private LocalPairing() {}

    /** 取得本機目前 token(首次呼叫時產生)。 */
    @NonNull
    public static synchronized PairingToken current() {
        if (token == null) token = PairingToken.create(displayName, canHost5G);
        return token;
    }

    /**
     * 更新「本機能否在 5GHz 開群組」旗標（由 {@link com.kisslink.wifidirect.WifiDirectManager#canHostFastGroup}
     * 在設定 HCE token 前刷新）。值有變才換發 token（nonce 跨非重疊場次重用無妨），
     * 確保對方碰一下讀到的能力旗標是最新的，GO 選舉才會把「能跑 5GHz」的那台選為 GO。
     * 應在閒置時呼叫，避免換掉正在配對中的 token。
     */
    public static synchronized void setCanHost5G(boolean value) {
        if (token != null && value == canHost5G) return;
        canHost5G = value;
        token = PairingToken.create(displayName, canHost5G);
    }

    /**
     * 設定對外顯示名稱（名片姓名）。若名稱有變且目前 token 已存在，
     * 換發一份帶新名稱的 token（nonce 跨非重疊場次重用無妨）。
     * 應在「閒置時」呼叫，避免換掉正在配對中的 token。
     */
    public static synchronized void setDisplayName(@NonNull String name) {
        if (name.trim().isEmpty()) return;
        if (name.equals(displayName)) return;
        displayName = name;
        if (token != null) token = PairingToken.create(displayName, canHost5G);
    }

    /** 強制換一份新 token(目前未使用;保留給未來需要輪替 nonce 的情境)。 */
    @NonNull
    public static synchronized PairingToken renew() {
        token = PairingToken.create(displayName, canHost5G);
        return token;
    }
}
