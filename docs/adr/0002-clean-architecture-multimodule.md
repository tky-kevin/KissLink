# ADR 0002: Clean Architecture 多模組分層

**狀態**：已採用 (Accepted)  
**決策日**：2025-06

## 背景

隨著功能增加，單一 `:app` 模組的耦合度過高，BLE 配對、Wi-Fi Direct 管理、傳輸協議、UI 層混雜，難以單獨測試與替換。

## 決策

採用 Clean Architecture 多模組結構：

```
:app              ← Application class、Hilt root、最終組裝
:core:domain      ← 純 Java 實體（Profile、TransferProgress、SessionState…）+ 倉庫介面
:core:data        ← Room 實作、TransferRepository
:feature:pairing  ← NFC、BLE、PairingCoordinator、WifiDirectManager
:feature:transfer ← TransferProtocol、SessionManager、PeerConnection
:feature:home     ← HomeViewModel、UI 元件（ThemePrefs、PermissionHelper…）
```

依賴規則：:app → :feature → :core:domain；:core:data → :core:domain；:feature 模組間允許橫向依賴（:feature:home 可用 :feature:pairing 的 Session 類別）。

## 理由

- `:core:domain` 純 Java，無 Android 依賴，可在 JVM 單元測試中直接執行。
- `:feature:pairing` 和 `:feature:transfer` 可獨立編譯、測試。
- R.* 邊界：`TransferListPresenter`、`SendListAdapter` 等使用 `com.kisslink.R` 的類別必須留在 `:app`，不能移入函式庫模組（否則資源 ID 不符）。

## 後果

- 新增功能時需判斷該類別的 R.* 依賴，決定放 `:app` 還是 `:feature:home`。
- Hilt `@AndroidEntryPoint` 只能用在 `:app` 的 Activity/Service（函式庫模組的 Activity 尚需額外 Hilt 設定）。
- 模組增加了 Gradle 配置量，但 build cache 讓並行編譯更快。
