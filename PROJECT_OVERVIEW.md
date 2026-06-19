# KissLink 專案詳細說明

## 專案簡介

**KissLink（安卓抓普）** 是一款 Android 近距離檔案傳輸應用，支援兩台手機背對背「碰觸」即可傳輸檔案、照片、聯絡卡，**無需帳號、無需網路、無需手動配對 Wi-Fi**。

名稱由來：兩台手機「親吻」（背對背碰觸）即可啟動傳輸。

---

## 核心技術架構：三層握手協議

| 層級 | 技術 | 用途 |
|------|------|------|
| **第 1 層** | NFC HCE | 初始配對，交換 PairingToken（nonce + goIntent + deviceName） |
| **第 2 層** | BLE GATT | 交換憑證、確定 Wi-Fi Group Owner、傳遞 Wi-Fi Direct 憑證（SSID + passphrase） |
| **第 3 層** | Wi-Fi Direct TCP | 傳輸實際檔案（port 47890），自訂二進制協議 + CRC32 校驗 + 心跳偵測 |

### 握手流程

1. **NFC 碰觸** → 一台手機作為 Reader（ISO-DEP），另一台作為 Tag（HCE Service），交換 PairingToken
2. **BLE 連接** → NFC 鎖定後建立 BLE GATT 連接，雙向交換 token，確定 Group Owner
3. **Wi-Fi Direct** → GO 建立 SoftAP 群組，Client 使用 BLE 收到的憑證靜默加入，TCP 開始傳輸

---

## 技術棧

| 類別 | 技術 | 版本/細節 |
|------|------|----------|
| **主要語言** | Java | 核心模組 |
| **次要語言** | Kotlin | 動畫模組（BeamStageView.kt） |
| **UI 框架** | Jetpack Compose | v1.7.5（核心動畫） |
| **UI 框架** | Android Views + Material | v1.12.0（Sheet、列表、主佈局） |
| **建構系統** | Gradle | 8.13 |
| **AGP** | Android Gradle Plugin | 8.13.2 |
| **Kotlin** | | 2.2.10 |
| **Min SDK** | | 29（Android 10） |
| **Target SDK** | | 34（Android 14） |
| **資料庫** | Room | 2.6.1 |
| **架構** | ViewModel + LiveData | 2.8.0 |
| **CI/CD** | GitHub Actions | 自動構建 Release APK |
| **測試** | JUnit 4.13.2 + Espresso 3.6.1 | |
| **JDK** | | 21 |

---

## 主要功能

### 1. NFC 碰觸連接
- 背對背輕觸即配對
- 自訂 AID（`F04B495353`）避免與系統 NDEF 衝突
- 全靜默配對（無系統 Wi-Fi 彈窗）

### 2. 雙向檔案傳輸
- 連接後任一方可隨時發送
- 全雙工 TCP 連接
- 支援多輪傳輸

### 3. 聯絡卡分享（vCard）
- vCard 3.0 格式，含頭像縮圖
- 自訂欄位：電話、Email、公司、職稱、網址、地址、備註
- 收到的卡片可儲存至系統通訊錄

### 4. 系統分享整合
- 註冊為 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 分享目標
- 從任何 App 分享檔案至 KissLink 排隊

### 5. 即時傳輸動畫
- 中心頭像環形進度動畫
- 即時傳輸速度顯示
- NFC 波紋待機動畫
- 完成勾勾動畫
- 聯絡卡飛入精靈動畫

### 6. 傳輸歷史
- Room 資料庫記錄所有傳輸
- 元資料：方向、對端名稱、檔案大小、速度、時間戳、批次 ID、MIME 類型

### 7. 第三方設備切換
- 傳輸中可排隊切換連接設備
- 閒置時立即切換

### 8. HELLO 資料交換
- TCP 連接後雙方發送 HELLO 封包
- 包含顯示名稱 + 頭像縮圖（JSON + Base64）

---

## 目錄結構

```
KissLink/
├── .github/workflows/
│   └── android.yml              # CI：構建 + 發佈 Release APK
├── build.gradle                  # 根建構檔（AGP + Kotlin）
├── settings.gradle               # 單模組：:app
├── gradle.properties             # AndroidX、JVM 記憶體
├── README.md                     # 專案文件
├── DIAGRAMS.md                   # Mermaid 架構圖
├── KNOWN_ISSUES.md               # 已知問題、技術債務、TODO
├── design/                       # UI 設計稿
│   ├── ConceptTap.jsx
│   ├── ConceptPulse.jsx
│   ├── ConceptBeam.jsx
│   └── ...
└── app/
    ├── build.gradle              # App 建構設定
    ├── proguard-rules.pro        # R8 規則
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/kisslink/
        │   ├── data/
        │   │   ├── db/           # Room 資料庫
        │   │   └── repository/   # Repository 模式
        │   ├── model/            # 資料模型
        │   ├── nfc/              # NFC HCE 服務
        │   ├── pairing/          # 三層握手協調器
        │   │   └── ble/          # BLE GATT 客戶端/伺服器
        │   ├── profile/          # 聯絡卡 vCard
        │   ├── transfer/         # TCP 檔案傳輸協議
        │   ├── ui/
        │   │   ├── home/         # 主 Activity + 動畫
        │   │   ├── history/      # 傳輸歷史 Sheet
        │   │   └── profile/      # 聯絡卡 Sheet
        │   ├── util/             # 權限處理
        │   ├── utils/            # 檔案工具
        │   └── wifidirect/       # Wi-Fi Direct 管理
        └── res/                  # 資源檔
```

---

## 設計模式

### 1. SSOT（Single Source of Truth）
`SessionState` 是不可變資料類，合併三個獨立狀態流（配對、連接、傳輸進度）為單一 `LiveData<SessionState>`，消除多 LiveData 觀察的競態條件。

### 2. Repository Pattern
`TransferRepository` 封裝 Room DAO 操作，透過 `ExecutorService` 在背景執行緒執行。

### 3. Observer Pattern（LiveData）
- `sessionLd` — 服務端狀態
- `stateLd` — Wi-Fi Direct 連接狀態
- `credentialLd` — Wi-Fi Direct 憑證
- `progressLd` — 傳輸進度
- `incomingCardLd` — 收到的聯絡卡

### 4. State Machine Pattern
三層重疊的狀態機：
- `PairingCoordinator.Phase`：IDLE → LATCHED → LINKING → ELECTING → CONNECTING → CONNECTED
- `ConnectionState`：IDLE → CREATING_GROUP/HOSTING/CONNECTING → CONNECTED → DISCONNECTED/ERROR
- `SessionState.Phase`：完整生命週期（RESETTING → IDLE → PAIRING_* → CREATING_GROUP → ... → ALL_DONE）

### 5. Builder Pattern
`TransferProgress.Builder` 和 `PairingToken` 建構使用流式 API。

### 6. Singleton Pattern
`AppDatabase.getInstance()`、`ProfileStore.get()`、`TransferRepository.getInstance()` 均使用雙重檢查鎖。

### 7. Service + Binder Pattern
`FileTransferService.TransferBinder` 暴露型別化 API，實現安全的跨元件通訊。

### 8. Session Generation Guard
`FileTransferService.sessionGen` 遞增計數器，防止過期回調影響當前會話。

### 9. Dirty/Clean State Teardown
`handleLatch()` 實現統一的 NFC 觸碰處理：進行中忽略、待重置忽略、乾淨狀態立即連接、髒狀態先拆除再重建。

---

## 關鍵架構決策

| 決策 | 原因 |
|------|------|
| 自訂二進制傳輸協議 | 最大化控制 framing 和錯誤偵測 |
| BLE 作為側通道 | NFC 載荷小、速度慢，不適合交換 Wi-Fi Direct 憑證 |
| 確定性 GO 選舉 | 使用 goIntent（主要）+ nonce（平手），無需網路通訊 |
| 前台 Service（connectedDevice） | 碰觸互動中短暫背景切換時保持存活 |
| Wake Lock | 防止 CPU 降速中斷 Socket 連接 |
| 閒置拆除計時器（45 秒） | 節省電力，同時容忍短暫 App 切換 |

---

## 已知技術債務

1. 三層狀態機重疊，需要映射膠水代碼（`mapPhase`、`fromConnection`、`fromTransfer`）
2. `FileTransferService` 是 God Object，職責過重
3. `WifiDirectManager` 狀態轉換分散在多個非同步回調中
4. 魔法延遲常數（如 `RESET_SETTLE_MS = 1800ms`、`IDLE_TEARDOWN_MS = 45s`）
5. 狀態/生命週期所有權模糊（部分已修正 HCE token）

---

## 總結

KissLink 是一個技術密度極高的近距離傳輸專案，核心亮點在於：
- **三層無縫握手**（NFC → BLE → Wi-Fi Direct）實現真正零操作配對
- **全雙工 TCP 傳輸**支援多輪雙向檔案交換
- **自訂二進制協議**確保傳輸效率與完整性
- **單一畫面架構**配合前景 Service 實現流暢的使用者體驗
