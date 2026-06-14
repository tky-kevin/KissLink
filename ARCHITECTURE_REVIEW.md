# KissLink 架構檢視與重構規劃

最後更新：2026-06-14

---

## 一、架構問題總覽

### 1.1 God Object：FileTransferService（607 行 / 24 欄位 / 17 私有方法）

這是專案最大的單一問題。該 Service 同時承擔 **7 項職責**：

| 職責 | 相關欄位/方法 | 行數佔比 |
|------|-------------|---------|
| **NFC 碰觸決策** | `handleLatch`、`proceedWithLatch`、`pendingReset`、`RESET_SETTLE_MS`、`maybeSwitchToPendingPeer` | ~80 行 |
| **Session 生命週期 + Generation Guard** | `sessionGen`、`createCoordinator`、`teardownSession`、`teardownPeer` | ~130 行 |
| **TCP Socket 建立** | `establishPeer`、`acceptAsServer`、`connectAsClient`（30 次重試） | ~90 行 |
| **進度橋接** | `peerProgressSrc`、`peerProgressObs`、`fromTransfer()` | ~40 行 |
| **Peer 身份管理** | `connectedPeerToken`、`peerNameFromHello`、`peerAvatarBytes`、`pendingSwitchPeer`、`transferring` | ~50 行 |
| **前景服務 / 通知 / Wake Lock** | `createNotificationChannel`、`buildNotification`、`acquireWakeLock`、`releaseWakeLock` | ~50 行 |
| **閒置拆除** | `IDLE_TEARDOWN_MS`、`uiBound`、`idleTeardown`、`cancelIdleTeardown`、`scheduleIdleTeardownIfIdle` | ~30 行 |

**核心問題**：NFC 碰觸決策、Session 重建設定、TCP 連接建立這三塊邏輯深度交織，改任何一塊都可能影響其他兩塊。

### 1.2 三套重疊的狀態機

| 狀態機 | Enum 值數量 | 位置 |
|--------|-----------|------|
| `SessionState.Phase` | 14 | `transfer/SessionState.java` |
| `PairingCoordinator.Phase` | 6 | `pairing/PairingCoordinator.java` |
| `ConnectionState` | 7 | `wifidirect/ConnectionState.java` |

**映射關係**：
```
PairingCoordinator.Phase ──mapPhase()──→ SessionState.Phase
ConnectionState ──fromConnection()──→ SessionState.Phase
TransferProgress.Phase ──fromTransfer()──→ SessionState.Phase
```

**問題**：
- `CREATING_GROUP`、`HOSTING` 只能透過 `fromConnection()` 產生，`mapPhase()` 永遠不會產生它們
- `RESETTING` 只在 `FileTransferService.proceedWithLatch()` 中產生
- 三個映射點分散在三個不同類別，狀態來源難以追蹤
- 加新狀態需同步改三處，映射層容易漂移

### 1.3 狀態/生命週期歸屬模糊

| 狀態 | 建立者 | 銷毀者 | 問題 |
|------|--------|--------|------|
| Wi-Fi Group | `WifiDirectManager.createGroupAsGO()` | `FileTransferService.teardownSession()` + `onDestroy()` + `onCreate()` | 建立在 Manager、銷毀在 Service |
| HCE Token | `FileTransferService.createCoordinator()` | `FileTransferService.onDestroy()` | ✅ 已修正，歸屬明確 |
| Peer Connection | `FileTransferService.startPeer()` | `teardownPeer()` 但可從 6 條路徑觸發 | 銷毀路徑過多 |
| `transferring` flag | `peerProgressObs` observer | `teardownSession()`、`onDisconnected()`、`interruptPairing()` | 可從 `sessionLd` 派生，獨立布林值可能不同步 |

### 1.4 WifiDirectManager 狀態轉換分散

16+ 個位置觸發 `setState()`，分散在：
- P2P `ActionListener`（`onSuccess` / `onFailure`）
- `BroadcastReceiver`（`onConnectionInfoAvailable`、`onDisconnected`、`onP2pStateChanged`）
- `startGoPoll` / `startClientPoll`（輪詢）
- `startTimeout`（逾時回呼）
- `NetworkCallback.onAvailable`（**背景執行緒**）

**關鍵約束**：
- `stateLd` 必須用 `postValue()`（`NetworkCallback` 在背景執行緒觸發）
- `starting` 布林值是必要的重入防護，**不可移除**
- `postValue` 會合併快速連續更新，中間狀態可能被丟棄（實務上可接受）

### 1.5 時序耦合（Magic Delays）

| 常數 | 值 | 用途 | 風險 |
|------|-----|------|------|
| `RESET_SETTLE_MS` | 1800ms | 髒狀態拆除後的沉澱時間 | 偶爾不足，已知問題 |
| `STAGE_MIN_DWELL_MS` | ? | 各階段最小顯示時間 | UI 覺感用 |
| `IDLE_TEARDOWN_MS` | 45s | 閒置自動拆除 | 選檔器場景可能誤觸 |
| `SO_TIMEOUT` | 7000ms | Socket 活性偵測 | 合理但硬編碼 |
| `HEARTBEAT_INTERVAL` | 2500ms | 心跳間隔 | 合理但硬編碼 |

---

## 二、檔案耦合度分析

### 2.1 耦合矩陣

```
                FTS  WDM  PC   PS   Peer TP  SS  CS
FileTransferService  ─    H    H    M    M    L   M   L
WifiDirectManager    M    ─    M    ─    ─    ─   L   H
PairingCoordinator   M    H    ─    M    ─    ─   ─   L
PeerConnection       H    ─    ─    ─    ─    H   L   ─
TransferProtocol     L    ─    ─    ─    L    ─   ─   ─
SessionState         M    L    ─    ─    L    ─   ─   L
ConnectionState      L    H    L    ─    ─    ─   L   ─

H = 高耦合  M = 中耦合  L = 低耦合  ─ = 無直接耦合
FTS=FileTransferService, WDM=WifiDirectManager, PC=PairingCoordinator,
PS=ProfileStore, TP=TransferProtocol, SS=SessionState, CS=ConnectionState
```

### 2.2 可切斷的耦合

| 耦合 | 來源 | 目標 | 解法 | 價值 |
|------|------|------|------|------|
| FTS → TransferRepository | `startPeer()` L500 | `getInstance()` | 注入 `TransferLogger` 函式介面 | 高 |
| FTS → ProfileStore | `startPeer()` L489 | `get(this)` | 傳入構造參數（已有 selfName/selfAvatarThumb） | 中 |
| FTS → KissLinkHCEService | `createCoordinator()`/`onDestroy()` | 靜態方法 | Token Holder 介面（但靜態單例是務實選擇） | 低 |
| PeerConnection → TransferRepository | `onItemCompleted` callback | `getInstance()` | 透過 Listener 回呼由 Service 處理 | 高 |

---

## 三、重構可行性與 CP 值評估

### 3.1 評估維度說明

- **可行性**：技術風險、是否需實機測試、是否涉及核心傳輸路徑
- **CP 值**：改善幅度 / (工作量 × 風險)
- **風險等級**：LOW / MEDIUM / HIGH / VERY HIGH

### 3.2 各重構項目評估

#### 項目 A：提取 SessionManager（從 FileTransferService）

| 維度 | 評估 |
|------|------|
| **目標** | 將 NFC 碰觸決策 + Session 生命週期 + Peer 身份管理 + 進度橋接抽出為 `SessionManager`，Service 退回薄殼 |
| **可行性** | ⚠️ 高風險 |
| **工作量** | 大（~300 行搬移 + 測試） |
| **風險** | HIGH — Session 管理深度交織，threading contract（`@MainThread`）必須保留，`sessionGen` 必須保持原子性 |
| **實機測試** | 必須，所有 NFC→配對→傳輸→斷線路徑 |
| **預期收益** | 高 — God Object 從 607 行降至 ~200 行，職責清晰 |
| **CP 值** | ★★★☆☆ 風險偏高但收益最大 |

#### 項目 B：提取閒置拆除 + 通知 + Wake Lock

| 維度 | 評估 |
|------|------|
| **目標** | 將 `IdleTeardownManager`、`ServiceNotificationHelper`、`WakeLockManager` 從 Service 抽出 |
| **可行性** | ✅ 高可行 |
| **工作量** | 小（~80 行搬移） |
| **風險** | LOW — 自包含邏輯，不涉及核心傳輸路徑 |
| **實機測試** | 最少 — 驗證通知顯示、Wake Lock 防 CPU 休眠、閒置計時器觸發 |
| **預期收益** | 中 — Service 減 ~80 行，清晰度提升 |
| **CP 值** | ★★★★★ 低風險高回報的入門重構 |

#### 項目 C：收斂三套狀態機

| 維度 | 評估 |
|------|------|
| **目標** | 統一為單一 Phase 推進，`ConnectionState` 和 `PairingCoordinator.Phase` 退為邊界 adapter |
| **可行性** | ⚠️ 極高風險 |
| **工作量** | 極大（改三個 enum + 所有映射 + 所有觀察者） |
| **風險** | VERY HIGH — 觸及最敏感的狀態傳播路徑，`postValue` 異步性、`observeForever` 模式都需重新驗證 |
| **實機測試** | 必須，每一條狀態轉換路徑 |
| **預期收益** | 高 — 消除映射漂移風險，狀態追蹤單一化 |
| **CP 值** | ★★☆☆☆ 風險遠大於收益，不建議一次做 |

#### 項目 D：收斂 WifiDirectManager 狀態轉換

| 維度 | 評估 |
|------|------|
| **目標** | 將 16+ 個 `setState()` 調用收斂為統一 `transition()` 方法 |
| **可行性** | ⚠️ 高風險 |
| **工作量** | 中（~200 行重構） |
| **風險** | HIGH — `starting` 守衛、`postValue` 異步性、P2P 回呼順序都有微妙依賴 |
| **實機測試** | 必須，GO/Client 兩側的群組建立 + 斷線重連 |
| **預期收益** | 中 — 狀態轉換可預測性提升 |
| **CP 值** | ★★★☆☆ 中等收益但高風險 |

#### 項目 E：分離 TCP Socket 建立邏輯

| 維度 | 評估 |
|------|------|
| **目標** | 將 `establishPeer`、`acceptAsServer`、`connectAsClient` 抽出為 `PeerConnector` |
| **可行性** | ✅ 中高可行 |
| **工作量** | 中（~90 行搬移 + 30 次重試邏輯） |
| **風險** | MEDIUM — TCP 連接時序與 Wi-Fi Direct 群組形成時序敏感相關 |
| **實機測試** | 必須，GO 和 Client 兩側的 TCP 連接 |
| **預期收益** | 中 — Service 再減 ~90 行 |
| **CP 值** | ★★★★☆ 可行且有實質收益 |

#### 項目 F：修復 `transferring` flag 同步問題

| 維度 | 評估 |
|------|------|
| **目標** | 將 `transferring` 改為從 `sessionLd` 派生，消除獨立布林值 |
| **可行性** | ✅ 高可行 |
| **工作量** | 小（~15 行改動） |
| **風險** | LOW — 改為 computed value 不涉及狀態傳播 |
| **實機測試** | 最少 — 驗證第三人觸碰切換場景 |
| **預期收益** | 小 — 消除一個潛在的不同步窗口 |
| **CP 值** | ★★★★★ 小投入大回報 |

---

## 四、建議重構路線圖

### 原則

> **不建議大改寫**——多數複雜度是 Wi-Fi Direct / BLE / NFC 的本質複雜度，重寫只會把已修的 edge case 重踩一遍。應以增量、實機測試當回歸網的方式處理。

### Phase 1：低風險快速修復（1-2 天）

**目標**：改善可維護性，不觸及核心路徑

| # | 動作 | 風險 | 預期效果 |
|---|------|------|---------|
| 1.1 | 提取 `IdleTeardownManager` | LOW | Service 減 ~30 行 |
| 1.2 | 提取 `ServiceNotificationHelper` | LOW | Service 減 ~30 行 |
| 1.3 | 提取 `WakeLockManager` | LOW | Service 減 ~20 行 |
| 1.4 | 修復 `transferring` flag 為 computed value | LOW | 消除同步窗口 |
| 1.5 | 更新 `TransferProtocol` Javadoc 移除過時的 HANDSHAKE 描述 | LOW | 文件正確性 |

### Phase 2：中風險結構改善（3-5 天）

**目標**：分離 TCP 建立邏輯，減少 Service 耦合

| # | 動作 | 風險 | 預期效果 |
|---|------|------|---------|
| 2.1 | 提取 `PeerConnector`（TCP 重試 + Socket 建立） | MEDIUM | Service 減 ~90 行 |
| 2.2 | 將 TransferRepository 依賴從 PeerConnection 回呼中移除 | MEDIUM | 解耦傳輸層與持久層 |
| 2.3 | 重構 `startPeer()` 中的 progress observer lambda 為獨立方法 | LOW | 可讀性提升 |

### Phase 3：高風險核心重構（1-2 週，含實機測試）

**目標**：提取 SessionManager，根本解決 God Object

| # | 動作 | 風險 | 預期效果 |
|---|------|------|---------|
| 3.1 | 提取 `SessionManager`（NFC 碰觸決策 + Session 生命週期 + Peer 身份） | HIGH | Service 從 607 行降至 ~200 行 |
| 3.2 | 在 `SessionManager` 中統一 `handleLatch` 決策邏輯 | HIGH | 碰觸決策集中化 |
| 3.3 | 將 `sessionGen` 移入 `SessionManager`，Service 透過 Binder 轉發 | HIGH | 世代防護歸屬明確 |

**測試要求**：
- 至少 2 台實機
- 覆蓋所有路徑：正常配對→傳輸→斷線、髒狀態重建、第三人切換、閒置拆除、App kill 後重開

### Phase 4：長期改善（可選，視需求）

| # | 動作 | 風險 | 備註 |
|---|------|------|------|
| 4.1 | 收斂 WifiDirectManager 狀態轉換 | HIGH | 最後再動，保留 `postValue` |
| 4.2 | 統一三套狀態機 | VERY HIGH | 多 sprint 工作，需大量實機測試 |
| 4.3 | Magic delay 事件驅動化 | MEDIUM | 評估 P2P 框架是否提供乾淨事件 |

---

## 五、風險矩陣

```
        低風險                中風險                高風險              極高風險
高收益  [1.4 transferring]   [2.2 解耦 Repo]     [3.1 SessionMgr]   [4.2 統一狀態機]
        [1.1-1.3 提取工具類]  [2.1 PeerConnector]
        
中收益  [1.5 更新 Javadoc]   [2.3 重構 lambda]   [3.2-3.3 Session]  [4.1 WDM 收斂]
        
低收益                                        [4.3 Magic delay]
```

---

## 六、不該動的檔案

| 檔案 | 原因 |
|------|------|
| `TransferProtocol.java` | 無狀態、乾淨、文件完整 |
| `PairingToken.java` | 職責單一、不可變、GO 選舉邏輯正確 |
| `LocalPairing.java` | 簡單單例、Token 生命週期正確 |
| `ConnectionState.java` | 簡單 enum、正確 |
| `WifiDirectReceiver.java` | 薄委派層、正確 |
| `WifiDirectEventCallback.java` | 乾淨介面、有利測試 |
| `BeamStageView.kt` | Compose 動畫模組、與核心傳輸無關 |

---

## 七、結論

### 架構債務評估

KissLink 的架構債務是**真實但有限的**。程式碼能正常運作，edge case 已被仔細處理（generation counter、dirty rebuild、postValue vs setValue、starting guard）。大部分複雜度是處理 Android Wi-Fi Direct 框架的**本質複雜度**，重寫只會重踩一遍已修的 bug。

### 推薦策略

**增量重構，不改寫**。按 Phase 1→2→3 順序執行，每個 Phase 完成後進行實機回歸測試。

### 預期成果

| 指標 | 重構前 | Phase 2 後 | Phase 3 後 |
|------|--------|-----------|-----------|
| FileTransferService 行數 | 607 | ~490 | ~200 |
| FileTransferService 欄位數 | 24 | ~18 | ~8 |
| 職責數量 | 7 | 4 | 2（薄殼 + 通知） |
| 狀態映射點 | 3 | 3 | 2（SessionManager 內統一） |
| 實機測試需求 | — | 每 Phase 後全路徑 | 每 Phase 後全路徑 |

### 最終建議

> **先做 Phase 1**（低風險快速修復），建立重構信心和回歸測試流程。
> 再評估是否值得投入 Phase 2-3。
> **絕對不要跳過 Phase 1 直接做 Phase 3**——沒有回歸網的高風險重構是賭博。
