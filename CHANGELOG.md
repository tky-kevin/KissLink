# Changelog

本文件記錄 KissLink 專案的所有重要變更。

格式基於 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.0.0/)，
版本號遵循 [Semantic Versioning](https://semver.org/lang/zh-TW/)。

---

## [Unreleased]

### Added
- 完整的架構規劃文件 (`docs/ARCHITECTURE_GUIDE.md`)
- 踩坑紀錄文件 (`docs/LESSONS_LEARNED.md`)
- 貢獻指南 (`CONTRIBUTING.md`)
- Linter 配置（ktlint + spotless + detekt）
- CI/CD Pipeline（`.github/workflows/ci.yml`）
- detekt 規則配置 (`config/detekt/detekt.yml`)

### Changed
- 更新 `build.gradle` 加入 linter plugins
- 更新 `app/build.gradle` 加入 linter 配置
- 更新 lint 設定為 CI 中必須通過

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
