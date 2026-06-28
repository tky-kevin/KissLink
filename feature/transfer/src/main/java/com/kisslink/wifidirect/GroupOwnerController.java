package com.kisslink.wifidirect;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import com.kisslink.model.GroupCredential;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * GO 端（傳送方）流程：建立 Wi-Fi Direct 群組、產生/取回連線憑證、進入
 * {@link ConnectionState#HOSTING} 並啟動 {@link GoDetectionPoller} 等待 Client 加入。
 */
class GroupOwnerController {

    private static final String TAG = "WifiDirectManager";

    private static final String SSID_PREFIX = "DIRECT-KL-";
    private static final String PASSPHRASE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final WifiDirectCore core;
    private final GoDetectionPoller goPoller;

    GroupOwnerController(WifiDirectCore core, GoDetectionPoller goPoller) {
        this.core = core;
        this.goPoller = goPoller;
    }

    /**
     * 以 Group Owner 身份建立 Wi-Fi Direct 群組（傳送方呼叫）。
     * 成功後 {@link WifiDirectManager#getCredential()} 會發射 {@link GroupCredential}，
     * 狀態進入 {@link ConnectionState#HOSTING}。
     */
    @SuppressLint("MissingPermission") // 權限在呼叫端由 PermissionHelper 確認
    void createGroupAsGO() {
        if (core.starting || core.currentState() != ConnectionState.IDLE) {
            Log.w(TAG, "createGroupAsGO skipped, starting=" + core.starting
                    + " state=" + core.currentState());
            return;
        }
        core.starting = true; // 同步守衛:擋住重疊 coordinator 的第二次 createGroup(state 用 postValue 非同步,擋不住競態)

        // 檢查 Wi-Fi 是否開啟
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                core.appContext.getSystemService(Context.WIFI_SERVICE);
        if (wm != null && !wm.isWifiEnabled()) {
            core.postError("請先開啟 Wi-Fi 以建立連線");
            return;
        }

        if (core.ensureChannel() == null) {
            core.postError("Wi-Fi Direct 初始化失敗，請重啟 Wi-Fi");
            return;
        }

        // 1. 先移除可能殘存的舊 Group，避免 BUSY 錯誤。
        //    removeGroup 成功（表示真的有殘留 group）時，額外等 500 ms 讓驅動完成拆除，
        //    再呼叫 createGroup，否則 P2P 狀態機還在過渡期，會立刻回 BUSY(2)。
        core.p2pManager.removeGroup(core.getChannel(), new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                Log.d(TAG, "removeGroup success, settling 500ms before createGroup");
                core.mainHandler.postDelayed(() -> doCreateGroup(), 500);
            }
            @Override public void onFailure(int r) {
                Log.w(TAG, "removeGroup failed: " + r + " (expected if no group)");
                doCreateGroup();
            }
        });
    }

    private void doCreateGroup() {
        doCreateGroup(1);
    }

    @SuppressLint("MissingPermission")
    private void doCreateGroup(final int attempt) {
        core.dispatch(WifiDirectEvent.GO_CREATE_INITIATED);

        final String ssid       = SSID_PREFIX + generateShortId(); // e.g. "DIRECT-KL-A3F2"
        final String passphrase = generatePassphrase();            // 16 char

        // 注意：實測強制 setGroupOperatingBand(5GHZ) 在 Redmi Note 14 Pro / Xiaomi 14T 上
        // 會讓群組成形卻破壞 GO 的 192.168.49.x 子網路由（client TCP 連不上 192.168.49.1、
        // GO accept 逾時），且不是在 createGroup 失敗 → legacy fallback 不會觸發。故維持
        // 系統自選頻段。Wi-Fi Direct 頻段實質由裝置當前 STA Wi-Fi 連線（SCC）決定，
        // 速度優化應從「配對時讓手機連 5GHz AP 或斷 Wi-Fi」著手，而非強制 P2P 頻段。
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .build();

        core.startTimeout(() -> {
            Log.e(TAG, "createGroup timeout");
            core.dispatch(WifiDirectEvent.GO_CREATE_TIMED_OUT);
            core.postError("建立群組逾時，請確認 Wi-Fi 已開啟並重試");
        });

        Log.d(TAG, "Calling createGroup with custom config: " + ssid);
        core.p2pManager.createGroup(core.getChannel(), config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                core.cancelTimeout();
                Log.d(TAG, "createGroup (custom) success → requesting group info");
                fetchGroupInfo(ssid, passphrase);
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "createGroup (custom) failed: " + reason + ", trying legacy fallback");
                // 某些裝置不支援自訂 SSID/Passphrase，嘗試使用系統預設建立
                core.p2pManager.createGroup(core.getChannel(), new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        core.cancelTimeout();
                        Log.d(TAG, "createGroup (legacy) success → requesting group info");
                        fetchGroupInfo(null, null);
                    }

                    @Override
                    public void onFailure(int reason2) {
                        if (core.currentState() != ConnectionState.CREATING_GROUP) return;

                        if (WifiDirectCore.isTransientP2pError(reason2)
                                && attempt < WifiDirectCore.P2P_TRANSIENT_MAX_RETRY) {
                            Log.w(TAG, "createGroup failed " + WifiDirectCore.reasonToString(reason2)
                                    + ", transient retry " + (attempt + 1)
                                    + "/" + WifiDirectCore.P2P_TRANSIENT_MAX_RETRY);
                            // 先盡力移除殘留群組讓框架沉澱，延遲後重建（startTimeout 由 doCreateGroup 重設）
                            core.p2pManager.removeGroup(core.getChannel(), core.noopListener);
                            core.mainHandler.postDelayed(() -> {
                                if (core.currentState() == ConnectionState.CREATING_GROUP) {
                                    doCreateGroup(attempt + 1);
                                }
                            }, WifiDirectCore.P2P_TRANSIENT_RETRY_MS);
                        } else {
                            core.cancelTimeout();
                            core.dispatch(WifiDirectEvent.GO_CREATE_FAILED);
                            core.postError("建立 P2P 群組失敗：" + WifiDirectCore.reasonToString(reason2));
                        }
                    }
                });
            }
        });
    }

    /**
     * 呼叫 requestGroupInfo 取得系統實際採用的 SSID（可能微調）與 Passphrase，
     * 若 API 回傳 null 則 fallback 使用本機產生的值。
     */
    @SuppressLint("MissingPermission")
    private void fetchGroupInfo(String fallbackSsid, String fallbackPass) {
        core.p2pManager.requestGroupInfo(core.getChannel(), group -> {
            final String ssid;
            final String pass;

            if (group != null
                    && group.getNetworkName() != null
                    && group.getPassphrase() != null) {
                ssid = group.getNetworkName();
                pass = group.getPassphrase();
                Log.d(TAG, "Group info from system: ssid=" + ssid);
            } else {
                // 部分裝置 requestGroupInfo 會回傳 null，使用預先產生的值
                Log.w(TAG, "requestGroupInfo returned null, using fallback credentials");
                ssid = fallbackSsid;
                pass = fallbackPass;
            }

            if (ssid == null || pass == null) {
                Log.e(TAG, "Failed to get SSID or Passphrase from system or fallback");
                core.dispatch(WifiDirectEvent.GO_CREDENTIAL_UNAVAILABLE);
                core.postError("無法取得連線憑證，請重試");
                return;
            }

            GroupCredential cred = new GroupCredential(
                    ssid, pass, WifiDirectManager.GO_IP_ADDRESS, WifiDirectManager.TRANSFER_PORT);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "GroupCredential ready (ssid=" + ssid + ")");
            }
            core.credentialLd.postValue(cred);
            core.dispatch(WifiDirectEvent.GO_GROUP_READY);
            goPoller.start(); // 啟動 Client 偵測輪詢（廣播可能不可靠）
        });
    }

    /** 產生 4 字元隨機十六進位 ID，用於 SSID 尾綴。 */
    private static String generateShortId() {
        byte[] b = new byte[2];
        new SecureRandom().nextBytes(b);
        return String.format(Locale.ROOT, "%02X%02X", b[0] & 0xFF, b[1] & 0xFF);
    }

    /** 產生 16 字元英數字 Passphrase（符合 WPA2 最短 8 字元要求）。 */
    private static String generatePassphrase() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PASSPHRASE_CHARS.charAt(rng.nextInt(PASSPHRASE_CHARS.length())));
        }
        return sb.toString();
    }
}
