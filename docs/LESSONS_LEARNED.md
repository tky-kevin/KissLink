# KissLink 踩坑紀錄與教訓

> 本文件記錄 KissLink 專案開發過程中遇到的所有已知問題及其根因分析。
> 目的：避免後續開發者（或未來的自己）重蹈覆轍。

最後更新：2026-06-14

---

## 一、NFC 相關

### 坑 1：標準 NDEF AID 衝突 — 系統選擇器彈窗

**嚴重度**：高 | **Commit**：`a233374`

**症狀**：Samsung 設備碰觸時彈出「選擇應用程式」對話框。

**根因**：標準 NDEF Type-4 AID（`D2760000850101`）被系統內建的 `NdefNfceeService` 同時註冊。當 KissLink 不在前台時，OS 無法自動解析兩個 HCE 服務，觸發 `ConflictResolverActivity`。

**解法**：改用私有 AID `F04B495353`。代價是失去 OS 冷啟動能力。

**教訓**：
- NFC AID 必須使用私有 AID，避免與系統服務衝突
- 冷啟動場景需要額外的啟動路徑設計

---

### 坑 2：NFC 雙向讀取導致角色翻轉

**嚴重度**：高 | **Commit**：`66f84f2`

**症狀**：重連失敗，BLE timeout，兩台設備永遠無法同步。

**根因**：一次持續的 NFC 碰觸在兩個方向都觸發讀取（物理天線是雙向的）。PairingCoordinator 沒有鎖定角色，後到的反方向 latch 覆寫角色，導致角色乒乓（flip-flop）和雙重 reset。

**解法**：`started` flag 在首次 latch 鎖定角色，後續 latch（包含同一接觸的反方向）全部忽略。

**教訓**：
- NFC 天線物理上是雙向的，一次接觸可能產生多次 latch
- 角色必須在第一次 latch 時鎖定，不可變更
- 所有後續 latch 必須被忽略（不是 reset）

---

### 坑 3：HCE Token 生死區

**嚴重度**：中 | **Commit**：`a3db4f0`

**症狀**：中斷配對後，設備無法被碰讀，直到 Activity 恢復前台。

**根因**：`teardownSession()` 清掉 HCE token，但 Activity 重新發布 token 有延遲，中間形成 NFC 死區。

**解法**：`teardownSession()` 不再清 token；`createCoordinator()` 統一（重新）發布 token。

**教訓**：
- HCE token 的生命週期必須有單一 owner
- Token 清除和發布必須是原子操作

---

## 二、Session 生命週期相關

### 坑 4：重疊 Coordinator — 自我連接

**嚴重度**：嚴重 | **Commit**：`73e9891`

**症狀**：GO 裝置同時建立兩個 PeerConnection，一個 `groupOwner=true`，一個 `groupOwner=false`。GO 連到自己的 Server Socket，檔案循環回到本機。

**根因**：force-stop 後快速重碰，殘留的 coordinator 仍然觸發 `onPaired`，帶著預設 role 驅動第二次 `establishPeer`。

**解法**：`sessionGen` 世代計數器 — 每次 `createCoordinator()` 遞增，listener callback 檢查 `captured gen != current gen` 就 no-op。

**教訓**：
- 跨 session 的回調必須有世代防護
- Coordinator 的生命週期必須由單一 owner 控制
- Force-stop 後的殘留狀態必須清理

---

### 坑 5：分散的 Session Reset — 重疊 Coordinator

**嚴重度**：嚴重 | **Commit**：`52f02df`

**症狀**：重複碰觸產生重疊的 coordinator 和中斷的連接。日誌顯示一次碰觸觸發三次 reset。

**根因**：Reset 邏輯分散在三個地方：
- `TransferActivity.rePair()`
- `PairingActivity.ensureFreshSession()` on bind
- `PairingActivity` 的 error-rePair in NFC callback

**解法**：收斂為單一決策點 `prepareForLatch()`，移除所有分散的 reset 站點。

**教訓**：
- **Reset 邏輯必須是單一決策點**，不可分散
- 多個 reset 站點是重疊 coordinator 的根本原因
- 這是純粹的架構問題，不是框架問題

---

### 坑 6：Service Churn + Wi-Fi 重入

**嚴重度**：高 | **Commit**：`bcf0e1e`

**症狀**：重連持續失敗，Service 反覆銷毀重建，兩個 coordinator 各自驅動 WifiDirectManager。

**根因**：`PairingActivity.onDestroy()` 呼叫 `stopService()`，但 Activity 在配對/RF 抖動中反覆重建，stop 拆掉進行中的 session 又重建，產生重疊 coordinator。

**解法**：
- `WifiDirectManager` 加入同步 `starting` 重入守衛
- `PairingActivity.onDestroy` 不再 `stopService()`
- Service 只在明確 Cancel 或 `onTaskRemoved` 時結束

**教訓**：
- Activity 的 `onDestroy` 不應 stop Service（Activity 會反覆重建）
- `postValue` 是異步的，僅靠 state check 不夠防重入
- 同步守衛（`starting` flag）是必要的

---

### 坑 7：進入配對畫面就斷線

**嚴重度**：中 | **Commit**：`cfa8b66`

**症狀**：活著的連接在進入配對畫面時被斷開。

**根因**：`ensureFreshSession()` 在 `coordinator.isFinished()` 時 reset，但已建立連接的 coordinator 整個生命週期都是 `isFinished()=true`。

**解法**：`ensureFreshSession()` 在 `peer != null`（連接仍活著）時 bail out。

**教訓**：
- `isFinished()` 的語意不夠明確，容易被誤用
- 「連接是否活著」應該有明確的判定方法，而不是依賴 coordinator 狀態

---

## 三、Wi-Fi Direct 相關

### 坑 8：Force-stop 後 Wi-Fi Group 殘留

**嚴重度**：中 | **Commit**：`57872ce`

**症狀**：force-stop 後重新開啟 App，Wi-Fi Direct 群組建立失敗。

**根因**：Force-stop 跳過 `removeGroup()`/`reset`，殘留的 P2P group 留在 OS 中。

**解法**：Service `onCreate` 時主動 `removeGroup()`。

**教訓**：
- Force-stop 不跑 `onDestroy`，這是 Android 設計
- Service 啟動時必須清理殘留狀態

---

### 坑 9：WiFi 已連接時無法建立連線（超時）

**嚴重度**：嚴重 | **狀態**：待修

**症狀**：WiFi 已連接到家用 AP 時，P2P 連接逾時失敗。WiFi 關閉時正常。

**根因**（多層）：

1. **TCP 路由失敗**：P2P 群組建立後，TCP socket 走預設路由（WiFi AP），不是 P2P 介面。`192.168.49.1` 在 P2P 介面上，預設路由不可達。

2. **`bindToP2pNetwork()` 異步且可能永不觸發**：`requestNetwork()` 呼叫 `onUnavailable()`，`bindProcessToNetwork()` 永遠不會被呼叫。

3. **TCP 與 network bind 競爭**：TCP 連接和 `requestNetwork()` 同時開始，即使 binding 最終成功，初始嘗試已失敗。

4. **P2P 框架 BUSY**：STA（Station Mode）活躍時，P2P `createGroup`/`connect` 可能回傳 BUSY，重試預算不足。

**解法**（待實作）：
- 使用 `Network.bindSocket()` 強制 TCP 走 P2P 介面
- 或在 P2P 操作前暫時斷開 WiFi AP
- 增加 P2P 重試預算

**教訓**：
- Wi-Fi Direct 和 WiFi STA 共用天線/驅動，會互相干擾
- P2P 網路可能對 `ConnectivityManager` 不可見
- TCP 路由必須明確綁定到 P2P 介面，不能依賴預設路由

---

### 坑 10：Socket Address Already in Use

**嚴重度**：低 | **Commit**：`52f02df`

**症狀**：快速重配對時 `acceptAsServer()` 綁定 port 失敗。

**根因**：前一個 socket 還在 TIME_WAIT。

**解法**：`acceptAsServer()` 加入 `SO_REUSEADDR`。

**教訓**：
- TCP socket 有 TIME_WAIT 狀態，快速重建需要 `SO_REUSEADDR`

---

## 四、BLE 相關

### 坑 11：BLE Notify-Before-Subscribe Race

**嚴重度**：中 | **Commit**：`a3db4f0`

**症狀**：快速重連時，GO 的 BLE notify 在 central 還沒 subscribe 之前就發送，credential 送達失敗。

**根因**：BLE GATT 的 subscribe 時序問題，GO 發送 notify 時 central 尚未完成 subscribe。

**解法**：non-GO central 加入 fallback 讀取邏輯。

**教訓**：
- BLE notify 可能在 subscribe 前觸發
- 必須設計 fallback read 路徑

---

## 五、傳輸相關

### 坑 12：斷線閃爍 — 錯誤訊息不當

**嚴重度**：低 | **Commit**：`c2fda4b`

**症狀**：斷線時閃爍顯示「配對失敗」，即使連接正常斷開。

**根因**：`onDisconnected` 發送 error("配對失敗")，但斷線不一定是錯誤。

**解法**：`onDisconnected` 改為發送 IDLE 而非 error。

**教訓**：
- 斷線和錯誤是不同的語意，不應混淆
- UI 訊息必須準確反映系統狀態

---

### 坑 13：TCP 連接重試與 P2P 重試混淆

**嚴重度**：低

**症狀**：兩層重試機制可能造成混淆。

**根因**：
- `WifiDirectManager` 重試 P2P 框架 BUSY/ERROR（3 次，1.5 秒間隔）
- `FileTransferService` 重試 TCP connect（30 次，400ms 間隔）

兩者服務不同的失敗模式，但可能被誤認為是同一層。

**教訓**：
- 不同層的重試機制必須明確分離
- 重試預算和逾時必須有清楚的文件說明

---

## 六、架構層教訓總結

### 教訓 1：單一決策點原則

> **所有 session reset 邏輯必須集中在一個方法中。**

分散的 reset 是重疊 coordinator 的根本原因。如果一開始就設計了 `prepareForLatch()` 單一決策點，坑 4、5、6 都不會存在。

### 教訓 2：State Owner 原則

> **每份跨層狀態必須有明確的唯一 owner。**

- HCE Token → FileTransferService
- Wi-Fi Group → WifiDirectManager（建立）/ FileTransferService（銷毀）← **不對稱但有意義**
- Peer Connection → FileTransferService
- Session Generation → FileTransferService

沒有明確 owner 的狀態會導致「誰負責銷毀」的模糊，引發殘留狀態問題。

### 教訓 3：世代防護原則

> **跨 session 的回調必須有世代計數器。**

`sessionGen` 是防止殘留 coordinator 干擾當前 session 的關鍵機制。任何異步回調（BLE、NFC、Wi-Fi）都可能在 session 切換後才抵達。

### 教訓 4：同步守衛原則

> **異步框架（`postValue`、`NetworkCallback`）需要同步守衛。**

`postValue` 是異步的，僅靠 state check 不够防重入。`starting` flag 提供同步保護，是必要的。

### 教訓 5：Service 生命週期原則

> **Activity 的 `onDestroy` 不應 stop Service。**

Activity 在配對/RF 抖動中會反覆重建。Service 的生命週期必須獨立於 Activity，只在明確 Cancel 或 `onTaskRemoved` 時結束。

### 教訓 6：Force-stop 恢復原則

> **Service 啟動時必須清理殘留狀態。**

Force-stop 不跑 `onDestroy`，殘留的 Wi-Fi group、BLE 連接等必須在 Service `onCreate` 時清理。

### 教訓 7：網路路由原則

> **Wi-Fi Direct TCP 連接必須明確綁定到 P2P 介面。**

不能依賴預設路由。當 WiFi 已連接到 AP 時，預設路由走 WiFi AP，P2P 介面不可達。必須使用 `Network.bindSocket()` 或暫時斷開 AP。

### 教訓 8：NFC 物理特性原則

> **NFC 天線物理上是雙向的，一次接觸可能產生多次 latch。**

角色必須在第一次 latch 時鎖定，後續 latch 必須被忽略。

### 教訓 9：BLE 時序原則

> **BLE notify 可能在 subscribe 前觸發，必須設計 fallback read。**

GATT 的 notify-before-subscribe 是已知的時序問題。

### 教訓 10：狀態語意原則

> **斷線和錯誤是不同的語意，不應混淆。**

UI 訊息必須準確反映系統狀態，避免不必要的恐慌。

---

## 七、架構債務現狀

| 問題 | 嚴重度 | 可修復性 | 備註 |
|------|--------|---------|------|
| God Object（FileTransferService） | 高 | 中 | 7 項職責混合 |
| 三套重疊狀態機 | 中 | 低 | 映射層容易漂移 |
| 狀態歸屬模糊 | 高 | 中 | 大部分已修正 |
| WifiDirectManager 轉換分散 | 中 | 低 | 16+ 個 setState 位置 |
| Magic delays | 低 | 中 | 部分是框架本質複雜度 |
| WiFi 已連接時超時 | 嚴重 | 高 | 待修，有明確解法 |
