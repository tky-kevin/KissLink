package com.kisslink.ui.home;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.kisslink.R;
import com.kisslink.pairing.NfcPairingController;
import com.kisslink.pairing.PairingToken;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.util.PermissionHelper;

/**
 * HomeActivity 的 NFC 配對層封裝。
 *
 * <p>職責：
 * <ul>
 *   <li>持有並管理 {@link NfcPairingController} 的生命週期（enable/disable）。</li>
 *   <li>回應 NFC latch（reader/tag）並做前置就緒檢查。</li>
 *   <li>無線電 (NFC/藍牙/Wi-Fi) 未開啟時彈提示對話框。</li>
 * </ul>
 *
 * <p>Activity 只需在生命週期節點呼叫 {@link #onResume}、{@link #onPause}、{@link #onNewIntent}，
 * 並在 binder 就緒後呼叫 {@link #onBinderReady}。
 */
public final class HomeNfcDelegate {

    /** Activity 需提供的回調介面。 */
    public interface Host {
        /** 呼叫時 binder 確定不為 null；由此執行 latch 後的配對邏輯。 */
        @NonNull FileTransferService.TransferBinder requireBinder();

        /** 觸發觸覺回饋。 */
        void haptic();

        /** 顯示短 Toast。 */
        void toast(@NonNull String message);

        /** 是否已授予連線所需的執行期權限。 */
        boolean hasConnectivityPermissions();

        /** 請求執行期權限。 */
        void requestPermissions();
    }

    private final AppCompatActivity activity;
    private final Host host;

    @Nullable private NfcPairingController nfc;
    private boolean resumed = false;
    private boolean binderReady = false;

    /** radio 提示是否正在畫面上（避免同一次貼合連觸疊出第二個對話框）。 */
    private boolean radioPromptShowing = false;

    public HomeNfcDelegate(@NonNull AppCompatActivity activity, @NonNull Host host) {
        this.activity = activity;
        this.host = host;
    }

    // ── 生命週期 ─────────────────────────────────────────────

    public void onResume() {
        resumed = true;
        enableIfReady();
    }

    public void onPause() {
        resumed = false;
        if (nfc != null) nfc.disable();
    }

    public void onNewIntent(@NonNull Intent intent) {
        if (nfc != null) nfc.handleIntent(intent);
    }

    /** binder 就緒（onServiceConnected）或重新綁定時呼叫。 */
    public void onBinderReady() {
        binderReady = true;
        FileTransferService.TransferBinder b = host.requireBinder();
        if (nfc != null) {
            nfc.setLocalToken(b.localToken());
        }
        enableIfReady();
    }

    /** binder 斷開時呼叫（onServiceDisconnected）。 */
    public void onBinderLost() {
        binderReady = false;
    }

    /** 重置 latch 狀態（在無線電提示 dismiss 後解鎖）。 */
    public void resetLatched() {
        if (nfc != null) nfc.resetLatched();
    }

    // ── 就緒檢查 + 啟用 ──────────────────────────────────────

    private void enableIfReady() {
        if (!resumed || !binderReady) return;
        ensureController();
        FileTransferService.TransferBinder b = host.requireBinder();
        nfc.setLocalToken(b.localToken());
        nfc.enable();
        nfc.handleIntent(activity.getIntent());
    }

    private void ensureController() {
        if (nfc != null) return;
        nfc = new NfcPairingController(activity, new NfcPairingController.Callback() {
            @Override public void onPeerToken(@NonNull PairingToken peer) {
                host.haptic();
                if (!connectivityReadyOrPrompt()) return;
                FileTransferService.TransferBinder b = host.requireBinder();
                b.onNfcLatchedAsReader(peer);
            }
            @Override public void onTagRead() {
                host.haptic();
                if (!connectivityReadyOrPrompt()) return;
                FileTransferService.TransferBinder b = host.requireBinder();
                b.onNfcLatchedAsTag();
            }
            @Override public void onError(@NonNull String message) {
                host.toast(message);
            }
        });
    }

    /**
     * 建立連線前就緒檢查：先確認權限，再確認 NFC/藍牙/Wi-Fi 已開啟。
     *
     * @return true 表示就緒，false 表示已彈提示（呼叫端應放棄本次配對）。
     */
    public boolean connectivityReadyOrPrompt() {
        // 短路評估：權限不足時不查無線電，無線電未開時不查熱點（避免無謂的反射呼叫）。
        boolean hasPerms = host.hasConnectivityPermissions();
        PermissionHelper.Radio off = hasPerms ? PermissionHelper.firstDisabledRadio(activity) : null;
        // 熱點開啟時 P2P 介面建不出來（ap0+wlan0 佔滿介面額度）→ 一律 BUSY(2)，重試無效。
        // 與其讓使用者乾等 25 秒逾時，不如先擋下並提示關閉熱點。
        boolean hotspotOn = hasPerms && off == null && PermissionHelper.isHotspotEnabled(activity);
        switch (evaluateReadiness(hasPerms, off != null, hotspotOn)) {
            case NEED_PERMS:
                host.requestPermissions();
                host.toast(activity.getString(R.string.conn_need_perms));
                if (nfc != null) nfc.resetLatched();
                return false;
            case NEED_RADIO:
                promptEnableRadio(off);
                return false;
            case HOTSPOT_ON:
                promptDisableHotspot();
                return false;
            case READY:
            default:
                return true;
        }
    }

    /** 配對前置就緒狀態。 */
    enum Readiness { READY, NEED_PERMS, NEED_RADIO, HOTSPOT_ON }

    /**
     * 純函式版的就緒判定，不碰 Android 以便單元測試。
     *
     * <p>優先序固定為 權限 → 無線電 → 熱點：權限缺失時最優先（連 API 都不能呼叫），
     * 其次無線電未開，最後才是熱點佔用介面。鎖住此優先序避免日後被改動打亂。
     */
    static Readiness evaluateReadiness(boolean hasPerms, boolean anyRadioOff, boolean hotspotOn) {
        if (!hasPerms) return Readiness.NEED_PERMS;
        if (anyRadioOff) return Readiness.NEED_RADIO;
        if (hotspotOn) return Readiness.HOTSPOT_ON;
        return Readiness.READY;
    }

    /** 提示關閉個人熱點，提供「前往設定」直達熱點/無線設定頁。 */
    private void promptDisableHotspot() {
        if (radioPromptShowing) return;
        radioPromptShowing = true;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.conn_hotspot_title)
                .setMessage(R.string.conn_hotspot_on)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.action_open_settings, (dd, w) -> {
                    try {
                        Intent tether = new Intent(Intent.ACTION_MAIN).setClassName(
                                "com.android.settings", "com.android.settings.TetherSettings");
                        activity.startActivity(tether);
                    } catch (Exception e) {
                        // OEM（如 MIUI）的熱點頁元件名不同 → 退回通用無線設定頁。
                        try {
                            activity.startActivity(new Intent(
                                    android.provider.Settings.ACTION_WIRELESS_SETTINGS));
                        } catch (Exception e2) {
                            host.toast(activity.getString(R.string.conn_hotspot_on));
                        }
                    }
                })
                .setOnDismissListener(d -> {
                    radioPromptShowing = false;
                    if (nfc != null) nfc.resetLatched();
                })
                .show();
    }

    /** 提示開啟未啟用的無線電，提供「前往設定」直達系統設定頁。 */
    private void promptEnableRadio(@NonNull PermissionHelper.Radio radio) {
        if (radioPromptShowing) return;
        final int msgRes;
        final Intent settings;
        switch (radio) {
            case NFC:
                msgRes = R.string.conn_need_nfc;
                settings = new Intent(android.provider.Settings.ACTION_NFC_SETTINGS);
                break;
            case BLUETOOTH:
                msgRes = R.string.conn_need_bluetooth;
                settings = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                break;
            case WIFI:
            default:
                msgRes = R.string.conn_need_wifi;
                settings = new Intent(android.provider.Settings.Panel.ACTION_WIFI);
                break;
        }
        radioPromptShowing = true;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.conn_need_title)
                .setMessage(msgRes)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.action_open_settings, (dd, w) -> {
                    try { activity.startActivity(settings); }
                    catch (Exception e) { host.toast(activity.getString(msgRes)); }
                })
                .setOnDismissListener(d -> {
                    radioPromptShowing = false;
                    if (nfc != null) nfc.resetLatched();
                })
                .show();
    }
}
