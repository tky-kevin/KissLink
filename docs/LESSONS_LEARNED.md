# KissLink 踩坑紀錄與教訓

> 本文件記錄 KissLink 專案開發過程中遇到的所有已知問題及其根因分析。
> 目的：避免後續開發者（或未來的自己）重蹈覆轍。
>
> 讀法：**第〇章是總結與心得**（趕時間先讀這章）；第一～五章是逐坑技術細節；
> 第六章是原則清單；第七章是現況與待辦。

最後更新：2026-06-25

---

## 零、共同病根與開發心得（POSTMORTEM）

> 這一章把一路修過的 bug 往回收斂：它們不是各自獨立的意外，**絕大多數是同一個病在不同尺度發作**。
> 寫下來是為了下一個大專案能一開始就避開，而不是又一路打補丁。

### 0.1 一句話病根

**狀態被複製成多份，再靠「事件」手動對齊，而不是從單一來源推導出來。**

把症狀攤開看，它們是同一個病：

| 尺度 | Bug（坑） | 重複/錯置的「同一份真相」 |
|------|-----------|---------------------------|
| 欄位 | nonce 漂移（坑14） | identity 與 payload 綁在同一個可變來源，改一個動到另一個 |
| 型別 | SessionState 漂移 | 三套 enum，跨層映射散在多處呼叫端 |
| ViewModel | 「收到 N 個」橫幅不同步 | `received` map 與 `recvCount`/`recvBatchId` 兩份權威值 |
| 非同步 | 完成時漏打勾 | 逐檔 `FILE_DONE` 經會合併的 LiveData 投遞，快速檔事件被丟棄 |
| 生命週期 | 重建後清單剩 1 項 | `renderReady` 無條件清掉 VM 持久狀態，replay 只補回最後一筆 |
| 物件職責 | God Object（Service / HomeActivity） | 沒有元件「擁有」一份完整推導，於是萬事都塞進一個類別手動對齊 |
| Session | 重疊 coordinator / 角色乒乓（坑4–7） | reset 邏輯散在三處、角色未在第一次 latch 凍結 |

- 少觸發一個事件 → **漂移**（橫幅卡舊值、清單剩 1 項）。
- 兩個事件搶著改同一份 → **競態**（重疊 coordinator、NFC 角色翻轉）。
- 事件投遞本身會丟（LiveData 合併、postValue 非同步）→ **狀態殘缺**（漏打勾）。
- 把所有「對齊邏輯」塞進一個物件 → **上帝物件**（是前幾項的後果，不是獨立問題）。

### 0.2 為什麼會一路累積（過程因）

功能一個一個長出來，每加一個需求就用最省事的方式滿足它：**「加一個欄位 + 在當下方便的 handler 裡同步它」**。
同步線的數量約是 `狀態數 × 事件數`，隨功能成長是**乘法**膨脹，熵只增不減。死碼（`countReceived`/
`onIncomingBatch` 等被取代卻沒清掉的舊路徑）就是化石證據：留著兩套並行狀態機等著漂移。

### 0.3 解藥：把乘法變成加法

讓畫面/衍生值成為狀態的純函數：`view = f(state)`。同步線從 `狀態 × 事件` 變成**一條**（一個推導）。
具體奉行五條原則（與第六章的逐坑原則一致，這裡是上位的抽象）：

1. **單一真相來源（SSOT）** — 每份狀態只有一個擁有者。
   - 例：`SessionManager` 是 session 狀態唯一寫入者；`HomeViewModel` 是接收清單唯一擁有者，
     橫幅計數是它的**純投影**而非第二份權威值。
2. **推導，不複製** — 衍生值即時算，不另存一份再手動同步。
   - 例：完成狀態不靠收齊每個 `FILE_DONE`，改用「下一檔開始 ⇒ 前面皆完成」這個可靠訊號回填。
3. **不可表達非法狀態（make illegal states unrepresentable）** — 把不變量做成結構保證，勝過靠各處紀律。
   - 例：把 identity（nonce/goIntent，曝光後凍結）與 payload（可刷新）拆成不同生命週期的欄位，
     nonce 漂移從此結構上不可能發生。
4. **單向資料流** — 事件 → 改 state → 重新 render；render 不准回頭改餵給別的 render 的 state。
   - 例：`SessionRenderer` 是 `SessionState → 畫面` 的單向對應，beam 封裝其中。
5. **依賴注入當原則、不一定要框架** — 依賴從外面傳進來，而非自己 `getInstance()` 去抓。
   - 三個 presenter 都由建構子注入 view/VM/callback；**不需要 Hilt/Dagger**——痛點是狀態漂移，不是接線。

### 0.4 這次架構整理如何體現（worked example）

`audit/architecture-cleanup` 分支把上述原則套回專案：

- **狀態單一來源**：`SessionState` 退為純值物件；跨 enum 映射全收進 `SessionManager`（單一寫入者）。
- **God Object 拆解**：`HomeActivity` 1018 → 587 行，渲染層拆成三個內聚 presenter
  （`TransferListPresenter` / `SendStackPresenter` / `SessionRenderer`），各自單一擁有者、單向 render。
- **結構保證不變量**：`PairingToken` 的 identity/payload 分離，rendezvous 與選舉不變量由結構保證。
- **診斷而非猜測**：把飛行記錄器（`FlightRecorder`）一般化為跨子系統單一時間軸，失敗時落地 dump——
  因為最痛的 bug（BLE 競態、路由綁錯）純靠 code review 看不出來，**一定要實機看 log**（見坑 9、14）。

### 0.5 給下一個大專案的檢查清單

- 任何「衍生值」先問：能不能從來源**算**出來？能就別存第二份。
- 任何「狀態」先問：**誰是唯一擁有者**？沒有答案就先別寫。
- 任何「reset / 同步」先問：是不是該收斂成**單一決策點**？（坑 5 的教訓）
- 任何「非同步回調」先問：會不會**丟、會不會過期、會不會重入**？（LiveData 合併、`postValue`、世代防護）
- 類別變長時先問：是不是因為**沒有別人擁有某份推導**，才都塞進來？要拆的是「擁有權」，不是行數。
- 測試先問：我寫的單元測試**測得到這個 bug 嗎**？（見第七章：本專案最痛的 bug 正好落在測試盲區）

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

**嚴重度**：嚴重 | **狀態**：已修（實機 logcat 定位）

**症狀**：WiFi 已連接到家用 AP 時，P2P 群組已形成（雙方 `CONNECTED`）但 TCP socket 永遠建不起來——
GO 端 `acceptAsServer` 逾時、client 端每次 `connect` 2 秒逾時直到預算耗盡。WiFi 關閉時正常。

**真正根因（實機 logcat 推翻原本假設）**：
不是「沒綁 P2P」，而是 **`bindToP2pNetwork()` 綁錯到 Wi-Fi AP(STA)**。
診斷 log 證實：

- `requestNetwork(TRANSPORT_WIFI, removeCapability(INTERNET))` 會**同時符合** STA（有 INTERNET）與 P2P（無 INTERNET），
  系統回報「最佳」網路時常給 STA。`P2P network bound: ... hasINTERNET=true` 即誤配的鐵證。
- 綁到 STA 後，socket 被逼走 AP 介面（source IP `192.168.0.x`）→ `192.168.49.1` 不可達 → `SocketTimeoutException`。
- **反之「不綁」時**，client 第一發 source IP 是 `192.168.49.x`（p2p0）且收到 `ECONNREFUSED`——代表封包
  **有到達 GO**，只是 GO 當下還沒 listen（時序，retry 即可）。`192.168.49.1` 與 p2p0 同網段是**直連路由**，
  預設路由本來就會走 p2p0。

**解法（已實作）**：
- `ClientConnector.bindToP2pNetwork()` 改用 `registerNetworkCallback`（回報所有符合網路），
  **只綁無 INTERNET 的真正 P2P 網路；配到 STA 一律跳過**，讓預設路由走直連 p2p0。
- `PeerConnector.connectAsClient()` 改傳 `Supplier<Network>` 每次重試重抓——P2P 網路是 `CONNECTED`
  之後才非同步綁定，迴圈外抓一次永遠是 null（會略過 `bindSocket`）。

**教訓**：
- ⚠️ **不要綁到 STA**：對 `192.168.49.1` 這種與 p2p0 同網段的直連位址，「不綁」比「綁錯網路」更安全。
- `requestNetwork` 去掉 INTERNET 不等於「只要 P2P」——STA 仍符合；要在 callback 內以 `NET_CAPABILITY_INTERNET` 自行濾掉。
- 這類「必定失敗」的回歸，純靠 code review 看不出來，**一定要實機 logcat 看 socket 的 source IP 與失敗型別**（timeout vs refused 指向完全不同的方向）。
- 排查用的 PAIRSEQ 序列 log 以 `Log.isLoggable(TAG, DEBUG)` 控制，平時靜默、
  `adb shell setprop log.tag.<TAG> DEBUG` 即可叫出，免重編譯。

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

### 坑 14：BLE 掃不到對方 — 跨場次 nonce 不同源

**嚴重度**：中 | **狀態**：已修復（結構性，分支 `fix/pairing-nonce-identity`）

**症狀**：偶發某次配對卡在 LINKING——central 一直 `scanning…` 找不到 peripheral，最後 BLE 逾時。
BLE/Wi-Fi 本身都正常。典型觸發：一台螢幕關久再解鎖（Service 閒置拆除後重建）、另一台 app 被回收後重開。

**根因**：central 掃描用的 nonce 是 NFC 當下讀到的對方 HCE token 的**一次性快照**；但若對方在「碰一下」
之後換了 token，BLE 就廣播新 nonce，central 還在找舊的 → 永遠對不上。更深一層的結構性缺陷是
**`PairingToken` 把「身分」與「酬載」綁在同一個可變來源上**：

- `PairingToken.create()` 每次都重抽 `nonce` + `goIntent`；
- `LocalPairing` 的每個 setter（`setCanHost5G` / `setDisplayName`）都走 `create()`——
  於是「刷新一個酬載欄位」會連帶換掉整份身分（nonce/goIntent）；
- `FileTransferService.createCoordinator()` 在 dirty 重建（配對進行中）時又呼叫 `setCanHost5G(...)`，
  正好在對方已讀走快照之後重生 nonce → 不同源。

即「NFC 讀到的 nonce」≠「BLE 廣播的 nonce」。本質是 **TOCTOU + 身分生命週期綁錯物件**：身分需要在一場
配對內凍結，卻被綁在壽命更短、更易變動的 token/coordinator 上。

**修復（結構性，非補丁）**：把**身分（nonce + goIntent）**與**酬載（canHost5G + deviceName）**分離。
- `LocalPairing` 改持「身分生成一次、永不更動」+「酬載可刷新」；`current()` 永遠用穩定身分 + 最新酬載
  組裝 token（`PairingToken.withPayload`），任何 setter 都不再動到 nonce/goIntent。→ rendezvous 不變量
  （讀取端看到的 = 廣播端正在用的）由結構保證。
- canHost5G 也是選舉關鍵值（曝光後須凍結）：把 `setCanHost5G(...)` 從 `createCoordinator()`（dirty 重建會
  在配對中途呼叫）移到只在**閒置邊界**（`onCreate` / 使用者主動斷線）刷新。→ 選舉不變量（雙方以同一份快照
  算 GO，結果反對稱）亦由結構保證。

**診斷 log（保留，便於日後驗證）**：
- central：`scanning for peer nonce=<X>`；逾時仍在掃描時警告 `never saw peer advertising nonce=<X>`。
- peripheral：`advertising nonce=<Y>`。修復後 `<X>` 應恆等於 `<Y>`。

**教訓**：
- 跨裝置的識別鍵（nonce）必須保證「讀取端看到的」=「廣播端正在用的」同一份。
- **識別（identity）與酬載（payload）的生命週期需求相反時，務必拆開**：身分曝光後凍結、酬載可刷新；
  別讓「刷新酬載」連帶重生身分。把不變量做成結構保證，勝過靠各處紀律維持。
- 識別鍵的生命週期應對齊「它所識別的東西」（一場配對），而非綁在某個壽命不一致的物件（coordinator/process）。
- 偶發性連線問題優先把雙方的關鍵識別值印出來對比,比猜測快得多。

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

`audit/architecture-cleanup` 分支後，原本的架構債務大多已結清：

| 問題 | 狀態 | 備註 |
|------|------|------|
| God Object（FileTransferService） | ✅ 已處理 | 抽出 `SessionManager`（狀態/身份/進度橋接單一擁有者） |
| God Object（HomeActivity） | ✅ 已處理 | 1018 → 587 行；渲染拆成三個 presenter（見第〇章 0.4） |
| 三套重疊狀態機 | ✅ 已收斂 | 映射政策集中在 `SessionManager`；`SessionState` 退為純值 |
| 狀態歸屬模糊 | ✅ 已處理 | 接收清單/橫幅、session 狀態皆有單一擁有者 |
| WifiDirectManager 轉換分散 | 🟡 部分 | `setState` 已導向 `FlightRecorder` 單一時間軸；仍多點呼叫 |
| Magic delays | 🟡 接受 | 部分是 BLE/Wi-Fi 框架本質複雜度，已用 watchdog 自癒 |
| WiFi 已連接時超時 | ✅ 已修 | 見坑 9（不綁 STA、改傳 `Supplier<Network>`） |

### 7.1 待實作 / 待強化（功能面，非架構債）

> 由原 `KNOWN_ISSUES.md` 併入；皆為「會動到已穩定核心、需多機驗證」的可選項。

- **第三人觸碰切換（tag 側）**：連線中第三人觸碰的切換邏輯，僅當舊裝置這次擔任 **NFC reader** 才生效；
  擔任 **tag**（HCE 被讀）時無法辨識新對象，會被當「同對象 resume」。解法：tag 側在隨後 BLE 換 token
  取得對方 nonce 後再判定。需動配對核心 + 三機驗證。
- **「傳完再切換」時間窗**：要求新對象整段傳輸維持 BLE 廣播未逾時；傳大檔可能逾時。可延長廣播或加重試。
- **傳送端進度的應用層 ACK**：傳送端進度可能在實際送達前略早到 100%（TCP 送出緩衝，約 1MB 差）。
  可加反向 `TRANSFER_COMPLETE_ACK`（接收端寫盤 + CRC32 後回送）。屬觀感正確性，傳輸本身已正確；風險中等。
- **動態 GO IP（低優先）**：`GO_IP_ADDRESS` 硬編碼 `192.168.49.1`。實測所有 log 皆為此值，收益極低。
- **kill 後重連穩定度（OEM 限制）**：已用前景服務型別 + 開機/`onCreate` `removeGroup` 緩解；MIUI/Samsung
  仍可能秒殺背景前景服務。非程式可完全解決（需使用者關電池最佳化）。

### 7.2 測試覆蓋的盲區（重要）

> 呼應第〇章：本專案**最痛的 bug 全落在測試盲區**，這不是巧合，是該補強的方向。

- 現有單元測試（約 39 個）只覆蓋**純值/狀態映射/憑證解析**（`SessionState`、`SessionManager`、
  `ConnectionState`、`GroupCredential`），測得到「映射對不對」，**測不到** BLE 競態、LiveData 合併丟事件、
  UI 漂移、路由綁錯——這些全是執行期/整合行為。
- 唯一的儀器測試 `WifiDirectReceiverTest` 需要裝置，而 CI **沒有 emulator job → 從不執行**。
- 啟示：要嘛補 emulator/整合測試把關鍵流程跑起來，要嘛承認「實機 + FlightRecorder dump」才是這類 bug
  的真正防線（目前現實是後者）。

### 7.3 Release / ProGuard 風險（CI）

`android.yml` 每次 push `main` 都 `assembleRelease`（`minifyEnabled=true` + ProGuard，debug key 簽署）。
ProGuard/R8 可能把反射、Room、Compose 用到的成員默默裁掉，**release 版閃退但 debug 版正常、且無任何測試把關**。
建議：保留並持續驗證 `proguard-rules.pro` 的 keep 規則；發版前至少在實機 smoke test 一輪 release APK，
或在 CI 加一個 release 安裝/啟動的 smoke 步驟。
