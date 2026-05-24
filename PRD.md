# PRD — NFC Transfer
## Android AirDrop-like 無線檔案傳輸應用程式

**版本：** 1.0  
**撰寫日期：** 2026-05-24  
**Demo Day：** 2026-06-10  
**團隊規模：** 3 人，開發週期 2 週  

---

## 1. 專案概述

### 1.1 背景與動機

現有 Android 裝置之間的檔案傳輸方式，多依賴藍牙（速度慢）、Google Drive / LINE（需要網路與帳號）或第三方 App（隱私疑慮）。本專案目標為實作「零設定、免帳號、高速度」的 Android P2P 檔案傳輸 App，利用 NFC HCE 自動交換 WiFi Direct 憑證，複現 AirDrop 的核心體驗。

### 1.2 專案定義

| 項目 | 內容 |
|------|------|
| 專案名稱 | NFC Transfer |
| 平台 | Android（Java） |
| Min SDK | 31（Android 12） |
| 目標裝置 | 具備 NFC 的 Android 手機（兩支）|
| Demo Day | 2026/06/10 |

### 1.3 核心價值主張

- **零設定：** 不需手動輸入 IP、密碼或掃描 WiFi 清單
- **高速傳輸：** WiFi Direct 理論速率 250 Mbps，遠優於藍牙
- **隱私優先：** 純本地 P2P，不經雲端，不需帳號
- **實體直覺：** 「碰一碰」即傳檔

---

## 2. 功能範圍（Scope）

### In Scope

| 功能 | 說明 | 優先級 |
|------|------|--------|
| NFC HCE 憑證交換 | Receiver 透過 HCE 廣播 WiFi Direct SSID + Passphrase | P0 |
| WiFi Direct 自動連線 | Sender 讀取 NFC 後自動連線 | P0 |
| 單檔傳輸 | Java Socket 傳送任意格式單一檔案 | P0 |
| 批次傳輸 | 一次選取多個檔案同時傳輸 | P1 |
| 傳輸進度條 | 即時顯示傳輸百分比 | P1 |
| 傳輸歷史紀錄 | Room DB 儲存每次傳輸紀錄 | P1 |
| 推播通知 | 傳輸完成時發送本地通知 | P1 |
| QR Code 備援 | NFC 失敗時以 ZXing QR Code 替代 | P2 |
| 接收檔案預覽 | Glide 顯示圖片縮圖 | P2 |

### Out of Scope

- 跨平台傳輸（iOS / PC）
- 雲端備份或同步
- 加密傳輸（TLS）
- 多對多群組傳輸

---

## 3. 使用者流程

### Mode A — NFC（主要流程）

```
[Receiver]
1. 開 App → 點「接收」
2. 建立 WiFi Direct Group（成為 Group Owner）
3. 取得 SSID + Passphrase
4. NfcHceService 啟動，透過 HCE 廣播憑證

[Sender]
1. 開 App → 點「傳送」→ 選取檔案
2. 將手機靠近 Receiver（NFC 感應距離 < 4cm）
3. 讀取 APDU Response，解析 SSID + Passphrase
4. WifiP2pManager 自動連線
5. Socket Client 連線至 192.168.49.1:8888
6. 傳輸檔案 + 顯示進度
7. 完成後推播通知 + 寫入 Room DB

[Receiver（持續等待）]
5. Socket Server 監聽 Port 8888
6. 接收檔案，存入 Downloads
7. 推播通知 + Glide 預覽 + 寫入 Room DB
```

### Mode B — QR Code（備援流程）

```
[Receiver] 點「顯示 QR Code」→ ZXing 產生包含憑證的 QR Code
[Sender]   點「掃描 QR Code」→ 解析憑證 → 後續同 Mode A 步驟 4-7
```

---

## 4. 系統架構

```
┌──────────────────────────────────────────┐
│              UI Layer                     │
│  MainActivity  SendActivity  ReceiveActivity  HistoryActivity  │
└────────────────┬─────────────────────────┘
                 │
┌────────────────▼─────────────────────────┐
│         Business Logic Layer              │
│  ┌─────────────────┐  ┌───────────────┐  │
│  │ Connection Layer │  │ Transfer Layer│  │
│  │ NfcHceService   │  │ SocketServer  │  │
│  │ WifiDirectMgr   │  │ SocketClient  │  │
│  └─────────────────┘  └───────────────┘  │
│  ┌─────────────────┐  ┌───────────────┐  │
│  │  QR (ZXing)     │  │ Notification  │  │
│  └─────────────────┘  └───────────────┘  │
└────────────────┬─────────────────────────┘
                 │
┌────────────────▼─────────────────────────┐
│              Data Layer                   │
│  Room DB (TransferHistory)  Local Storage │
└──────────────────────────────────────────┘
```

### 關鍵常數

```
NFC AID:        F04E464354520101
Socket Port:    8888
GO IP:          192.168.49.1  (WiFi Direct Group Owner 固定 IP)
Buffer Size:    65536 bytes (64KB)
Header Format:  [4B name_len][name_bytes][8B file_size][file_data]
```

---

## 5. 技術選型

| 技術 | 版本 | 用途 |
|------|------|------|
| Java | 11 | 全專案語言 |
| NFC HCE | HostApduService | Receiver 廣播 WiFi Direct 憑證 |
| NFC Reader | NfcAdapter.ForegroundDispatch | Sender 讀取憑證 |
| WiFi Direct | WifiP2pManager | 高速 P2P 連線 |
| Socket | Java ServerSocket/Socket | 檔案傳輸 |
| Room | 2.6.1 | 傳輸歷史 SQLite |
| ZXing | 4.3.0 | QR Code 產生與掃描 |
| Glide | 4.16.0 | 圖片預覽 |
| NotificationCompat | AndroidX | 推播通知 |

---

## 6. 工作分工

### Person A — Connection Layer

| Task | 說明 |
|------|------|
| A-1 | 建立 `NfcHceService`，實作 `processCommandApdu()` |
| A-2 | 定義 AID，在 Manifest 綁定服務 |
| A-3 | `WifiP2pManager.createGroup()` + `requestGroupInfo()` |
| A-4 | `WifiDirectManager` 單例：`createGroup()`, `connect()`, `disconnect()` |
| A-5 | `WifiP2pBroadcastReceiver` 監聽連線狀態 |
| A-6 | Sender 端 NFC ForegroundDispatch + APDU 解析 |

**對外介面：**
```java
interface ConnectionCallback {
    void onConnected(String peerIpAddress);
    void onConnectionFailed(String reason);
    void onDisconnected();
}
```

### Person B — Transfer Layer

| Task | 說明 |
|------|------|
| B-1 | `FileTransferServer`（ServerSocket Port 8888，背景 Thread）|
| B-2 | `FileTransferClient`，支援單檔與批次 |
| B-3 | 自訂 Header 傳輸協議 |
| B-4 | `ProgressCallback` 每 64KB 回報一次 |
| B-5 | `TransferNotificationHelper`（傳輸完成通知）|
| B-6 | `TransferService`（前景 Service 防止被殺）|

**對外介面：**
```java
interface TransferCallback {
    void onProgressUpdate(String fileName, int percent);
    void onFileReceived(String fileName, String filePath);
    void onTransferComplete(int successCount, int failCount);
    void onTransferError(String fileName, Exception e);
}
```

### Person C — UI & Data Layer

| Task | 說明 |
|------|------|
| C-1 | `MainActivity`：三個入口按鈕 |
| C-2 | `SendActivity`：檔案選取 + NFC 等待提示 + 進度 UI |
| C-3 | `ReceiveActivity`：等待提示 + 進度 + QR Code 顯示 |
| C-4 | `HistoryActivity`：RecyclerView 顯示傳輸歷史 |
| C-5 | Room DB：Entity, DAO, Database, Repository |
| C-6 | ZXing QR Code 產生與掃描 |
| C-7 | Glide 圖片預覽整合 |
| C-8 | `PermissionHelper` 統一執行期權限請求 |

---

## 7. 里程碑

| 日程 | 目標 | 驗收條件 |
|------|------|----------|
| Day 1-2 | Scaffold + Interface 定義 | Gradle Build 通過；三組 Interface 已定義 |
| Day 3-6 | 三人平行開發 | 各模組單元測試通過 |
| Day 7-8 | ZXing + Glide 整合 | QR Code 可產生並解析；圖片預覽正常 |
| Day 9-10 | 三模組整合 | 端對端：兩支實機完成完整傳檔 |
| Day 11-12 | 實機測試 | 10 次傳輸成功率 ≥ 90% |
| Day 13 | Demo 影片 + 簡報 | 影片 ≤ 3 分鐘 |
| Day 14 | Buffer + 提交 | APK + Source + 簡報全數提交 |

---

## 8. 風險評估

| 風險 | 可能性 | 因應策略 |
|------|--------|----------|
| NFC 硬體不穩定 | 高 | QR Code 備援作為 Demo 保險 |
| WiFi Direct 廠商差異 | 中 | Day 9-10 優先在目標 Demo 裝置測試 |
| Android 12+ 權限問題 | 中 | Day 1 完成全部權限宣告與請求流程 |
| Large file OOM | 低 | 64KB Buffer 逐塊讀寫，不一次載入記憶體 |
| 模組介面不一致 | 中 | Day 2 前 Code Review 所有 Interface 定義 |

---

## 9. 評分對應策略

| 評分項目 | 比重 | 對應策略 |
|----------|------|----------|
| 功能完整性 | 30% | P0 全部實作，P1 全部實作 |
| 技術難度 | 20% | NFC HCE APDU 手動實作 + WiFi Direct Group 手動管理 |
| 創意性 | 15% | AirDrop 概念 + QR Code 備援雙路徑 |
| 易用性與穩定性 | 15% | 友善權限請求 + 錯誤提示 + 10 次測試達標 |
| 文件與口頭報告 | 20% | 本 PRD + 架構圖 + Demo 影片 + 簡報 |

---

## 10. 附錄 — 測試矩陣

| 測試項目 | 目標 |
|----------|------|
| NFC HCE 憑證交換成功率 | ≥ 95% |
| WiFi Direct 連線建立時間 | ≤ 5 秒 |
| 10MB 圖片傳輸時間 | ≤ 3 秒 |
| 批次 5 檔傳輸成功率 | ≥ 90% |
| QR Code 掃描成功率 | ≥ 99% |
| App Crash Rate（10 次）| 0% |
