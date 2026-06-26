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
 * <h3>身分(identity)與酬載(payload)分離</h3>
 * <p>本類別把 token 拆成兩種生命週期:
 * <ul>
 *   <li><b>身分</b>——{@link PairingToken#nonce} + {@link PairingToken#goIntent}:本進程內
 *       <b>生成一次、永不更動</b>。它是 BLE 會合鍵與 GO 選舉權重,對方一旦經 NFC/BLE 拿到這份快照,
 *       就以它掃描與選舉;本機若中途換掉,對方就掃不到(nonce 漂移)或雙方選舉不再反對稱
 *       (goIntent 漂移)——這正是「配對卡在 BLE」的結構性根因。nonce 跨「不重疊」場次重用無妨。</li>
 *   <li><b>酬載</b>——{@link #canHost5G} + {@link #displayName}:可隨時刷新(由呼叫端在<b>閒置時</b>
 *       更新,使下一場曝光的值最新)。刷新酬載<b>不會</b>動到身分。</li>
 * </ul>
 * <p>{@link #current()} 永遠回傳「穩定身分 + 最新酬載」組裝出的 token,因此不論
 * Coordinator 重建幾次、Wi-Fi 能力如何翻動,本機對外的 nonce/goIntent 都是同一份——
 * 讓「對方掃到的」=「本機選舉用的」這個不變量由結構保證,而非靠各處紀律維持。
 */
public final class LocalPairing {

    /** 身分:生成一次後凍結(nonce + goIntent)。酬載欄位不寫回它,改在 {@link #current()} 時套用。 */
    private static volatile PairingToken identity;

    /** 對外顯示名稱（對方碰一下即看到）。酬載,可刷新;不參與選舉。預設裝置型號,可由名片姓名覆寫。 */
    private static volatile String displayName = Build.MODEL;

    /** 本機若當 GO 是否能在 5GHz 開群組（供 GO 選舉偏置）。酬載,可刷新。未知時樂觀 true。 */
    private static volatile boolean canHost5G = true;

    private LocalPairing() {}

    /**
     * 取得本機目前 token = <b>穩定身分</b>(nonce/goIntent,首次呼叫時生成)+ <b>最新酬載</b>(displayName/canHost5G)。
     * 多次呼叫的 nonce/goIntent 恆等,只有酬載反映最近一次 setter 的值。
     */
    @NonNull
    public static synchronized PairingToken current() {
        if (identity == null) identity = PairingToken.create(displayName, canHost5G);
        return identity.withPayload(displayName, canHost5G);
    }

    /**
     * 更新「本機能否在 5GHz 開群組」旗標（由 {@link com.kisslink.wifidirect.WifiDirectManager#canHostFastGroup}
     * 刷新）。只換酬載、<b>不</b>動身分,故 nonce/goIntent 不變。
     * <p>仍應在<b>閒置時</b>呼叫:酬載一旦在某場曝光給對方(canHost5G 參與選舉),該場就不該再變,
     * 否則雙方選舉可能不反對稱。配對進行中(尤其 dirty 重建)勿呼叫。
     */
    public static synchronized void setCanHost5G(boolean value) {
        canHost5G = value;
    }

    /**
     * 設定對外顯示名稱（名片姓名）。只換酬載、不動身分。
     * deviceName 不參與選舉,故隨時刷新皆安全。
     */
    public static synchronized void setDisplayName(@NonNull String name) {
        if (name.trim().isEmpty()) return;
        displayName = name;
    }
}
