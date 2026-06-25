# Changelog

本文件記錄 KissLink 專案的所有重要變更。

格式基於 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.0.0/)，
版本號遵循 [Semantic Versioning](https://semver.org/lang/zh-TW/)。

---

## [Unreleased]

### Changed — 架構整理（`audit/architecture-cleanup`）
- **狀態收斂為單一來源**：`SessionState` 退為純值物件，跨 enum 映射政策全集中於 `SessionManager`
  （單一寫入者）。
- **拆解 God Object**：抽出 `SessionManager`（狀態/身份/進度橋接）；`HomeActivity` 1018 → 587 行，
  渲染層拆成三個 presenter（`TransferListPresenter` / `SendStackPresenter` / `SessionRenderer`）。
- **飛行記錄器一般化**：`PairingFlightRecorder` → `com.kisslink.diag.FlightRecorder`，跨子系統單一
  時間軸，失敗時落地 dump。
- **配對身分/酬載分離**：`PairingToken` 把 identity（nonce/goIntent，曝光後凍結）與 payload 拆開，
  rendezvous/選舉不變量改由結構保證（修坑 14）。
- 死碼清理（`NfcForegroundHelper`、`countReceived`/`onIncomingBatch` 等被取代的舊路徑）。

### Fixed
- BLE 連線/掃描階段加看門狗，自癒 `connectGatt` 靜默失敗。
- 接收清單完成時偶爾漏打勾（改由「下一檔開始 ⇒ 前面皆完成」回填，不依賴會被合併的 `FILE_DONE`）。
- 切換深淺色/背景返回後接收清單只剩 1 項（`renderReady` 改為純視覺、不清 VM 持久狀態）。
- 「收到 N 個」橫幅與接收清單漂移（橫幅計數改為 `received` 的純投影）。
- 待傳清單文字/連結點擊無反應；本次接收 sheet 垃圾桶誤清全部歷史。

### Removed
- **detekt**：1.23 不支援 Kotlin 2.2 且 CI 早已停用，移除 plugin 與 `config/detekt/`（死重量）。
- `KNOWN_ISSUES.md`（併入 `docs/LESSONS_LEARNED.md` 第七章）、`DIAGRAMS.md`（併入
  `docs/ARCHITECTURE_GUIDE.md` 第七節）。
- 將誤追蹤的 `.mimocode/` 移出版控。

### Docs / CI
- `LESSONS_LEARNED.md` 新增第〇章 POSTMORTEM（共同病根 + 開發心得）。
- 修正 README/CONTRIBUTING 的 Gradle/AGP 版本（8.13 → 9.5.1 / AGP 9.2.1）。
- 修掉 CI lint 紅燈（停用會隨上游變紅的 `GradleDependency`；修正搬移造成的 `InlinedApi`）。

---

## [1.0.0] - 2026-06-14

### Added
- NFC 碰觸配對功能
- BLE 側通道憑證交換
- Wi-Fi Direct 檔案傳輸
- vCard 聯絡卡分享
- 系統分享整合（ACTION_SEND）
- 即時傳動畫（Jetpack Compose）
- 傳輸歷史（Room DB）
- 前台服務生命週期管理
- 閒置自動拆除（45 秒）
- 第三方設備切換
- HELLO 資料交換

### Fixed
- NFC AID 衝突（Samsung 選擇器彈窗）
- Session 重疊 Coordinator（自我連接）
- NFC 雙向讀取角色翻轉
- HCE Token 生死區
- Force-stop 後 Wi-Fi Group 殘留
- 進入配對畫面就斷線
- BLE Notify-Before-Subscribe Race
- Socket Address Already in Use
- 斷線閃爍錯誤訊息

---

## [0.9.0] - 2026-06-10

### Added
- Pre-pair 檢查（BT/Wi-Fi/NFC + 權限）
- 中斷式連接階段（可取消重試）
- Watchdog 逾時機制
- 分享目標整合

### Fixed
- Service Churn + Wi-Fi 重入
- 重連失敗（分散 reset）

---

## [0.8.0] - 2026-06-08

### Added
- Session generation guard
- 心跳活線偵測
- 同設備恢復 / 新設備切換
- 閒置拆除 + Wake Lock

### Fixed
- 重連失敗（重疊 Coordinator）
- 重連失敗（角色翻轉）

---

## [0.7.0] - 2026-06-05

### Added
- 私有 NFC AID（F04B495353）
- BLE credential fallback 讀取
- Session lifecycle 序列化

### Fixed
- Samsung 選擇器彈窗
- BLE notify-before-subscribe race

---

## [0.6.0] - 2026-06-03

### Added
- PairingCoordinator 三層中樞
- 跨設備配對（NFC → BLE → Wi-Fi Direct）
- TCP framed transfer + CRC32

### Fixed
- 基礎連線穩定性

---

## [0.5.0] - 2026-06-01

### Added
- 雙向 PeerConnection 傳輸
- TransferProtocol 二進制協議
- 傳輸歷史（Room DB）

---

## [0.4.0] - 2026-05-28

### Added
- A+B2 配對基礎
- NDEF tag emulation
- BLE channel

---

## [0.3.0] - 2026-05-25

### Added
- UI 重設計（Jetpack Compose 動畫）
- 閒置拆除
- 效能調優

---

## [0.2.0] - 2026-05-20

### Added
- QR Code 掃描配對
- 跨裝置相容性

### Fixed
- 連線穩定性問題

---

## [0.1.0] - 2026-05-15

### Added
- 初始版本
- NFC 傳輸基礎功能
- UI 框架
