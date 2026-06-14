# KissLink 貢獻指南

> 本文件說明如何參與 KissLink 開發，包含環境設定、分支策略、程式碼規範和提交流程。

---

## 環境設定

### 必要工具

| 工具 | 版本 | 說明 |
|------|------|------|
| JDK | 21 | Android Studio 內建 JBR |
| Android Studio | 2024.1+ | 最新穩定版 |
| Gradle | 8.13 | 透過 wrapper 自動安裝 |
| Git | 2.40+ | 版本控制 |

### 建置專案

```bash
# 設定 JAVA_HOME（如果系統 JDK 版本不符）
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

# 建置 debug APK
./gradlew :app:assembleDebug

# 執行所有 linter
./gradlew ktlintCheck spotlessCheck detekt :app:lintDebug

# 自動修正 linter 問題
./gradlew ktlintFormat spotlessApply
```

---

## 分支策略

```
main ─────────────────────────────────────────────
  │
  ├── develop ────────────────────────────────────
  │     │
  │     ├── feature/nfc-refactor ────────────────
  │     │
  │     ├── feature/wifi-direct-fix ─────────────
  │     │
  │     └── bugfix/timeout-issue ────────────────
  │
  ├── release/v1.1.0 ────────────────────────────
  │
  └── hotfix/critical-fix ────────────────────────
```

### 分支命名

| 類型 | 格式 | 範例 |
|------|------|------|
| Feature | `feature/short-description` | `feature/nfc-custom-aid` |
| Bug Fix | `bugfix/short-description` | `bugfix/wifi-timeout` |
| Hotfix | `hotfix/short-description` | `hotfix/crash-on-start` |
| Release | `release/v1.1.0` | `release/v1.1.0` |

---

## 開發流程

### 1. 開始新功能

```bash
# 從 develop 分出 feature 分支
git checkout develop
git pull origin develop
git checkout -b feature/my-new-feature

# 開發...
git add .
git commit -m "feat(module): add new feature"

# 推送並建立 PR
git push origin feature/my-new-feature
```

### 2. Commit Message 格式

使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Type**：
| Type | 說明 | 範例 |
|------|------|------|
| `feat` | 新功能 | `feat(nfc): add custom AID` |
| `fix` | Bug 修復 | `fix(wifi): resolve timeout issue` |
| `refactor` | 重構 | `refactor(session): extract SessionManager` |
| `docs` | 文件 | `docs: update architecture guide` |
| `style` | 程式碼風格 | `style: apply ktlint formatting` |
| `test` | 測試 | `test: add unit tests for SessionState` |
| `chore` | 建構/工具 | `chore: update gradle to 8.13` |
| `perf` | 效能 | `perf: optimize TCP retry logic` |

**Scope**（可選）：
| Scope | 說明 |
|-------|------|
| `nfc` | NFC 相關 |
| `ble` | BLE 相關 |
| `wifi` | Wi-Fi Direct 相關 |
| `session` | Session 生命週期 |
| `transfer` | 檔案傳輸 |
| `ui` | 使用者介面 |
| `profile` | 聯絡卡 |

### 3. 程式碼規範

#### Linter

| 工具 | 用途 | 執行指令 |
|------|------|---------|
| ktlint | Kotlin 程式碼風格 | `./gradlew ktlintCheck` |
| spotless | Java 程式碼風格 | `./gradlew spotlessCheck` |
| detekt | Kotlin 靜態分析 | `./gradlew detekt` |
| Android Lint | Android 最佳實踐 | `./gradlew :app:lintDebug` |

**規則**：
- 所有 linter 必須通過才能合併
- 使用 `./gradlew ktlintFormat spotlessApply` 自動修正
- 禁止 `@Suppress` 除非有明確理由

#### 程式碼風格

| 規範 | 說明 |
|------|------|
| 檔案長度 | 單一檔案不超過 400 行 |
| 方法長度 | 單一方法不超過 50 行 |
| 類別職責 | 單一職責原則（SRP） |
| 命名 | 類別 PascalCase、方法 camelCase、常數 SCREAMING_SNAKE_CASE |
| 縮排 | 4 空格 |
| 行長 | 120 字元上限 |
| Import | 不使用 wildcard import，按字母排序 |

### 4. 架構原則

#### Clean Architecture 分層

```
Presentation → Domain → Data
```

- **Presentation**：Activity / Fragment / Compose UI / ViewModel
- **Domain**：UseCase / Domain Model / Repository Interface
- **Data**：Repository Implementation / Data Source / External Service

#### 單向資料流

```
使用者互動 → ViewModel → UseCase → Repository → DataSource
                ↑                                        │
                └──── StateFlow ←────────────────────────┘
```

#### 依賴注入

使用 Hilt，構造函式注入，不用 Service Locator。

#### 狀態管理

- 使用 `StateFlow`（取代 `LiveData`）
- 單一 State 對象，不可變
- 狀態轉換透過 `copy()` + reducer

### 5. 測試

#### 測試金字塔

```
        ┌─────────┐
        │ E2E Test│  ← 少量，實機測試
       ┌┴─────────┴┐
       │ Integration│  ← 中量，API 測試
      ┌┴────────────┴┐
      │  Unit Test    │  ← 大量，快速
      └───────────────┘
```

#### 執行測試

```bash
# 單元測試
./gradlew :app:testDebugUnitTest

# 整合測試（需要模擬器/實機）
./gradlew :app:connectedDebugAndroidTest

# 覆蓋率報告
./gradlew :app:jacocoTestReport
```

#### 測試覆蓋率目標

| 模組 | 目標覆蓋率 |
|------|-----------|
| Domain Layer | 80% |
| Data Layer | 60% |
| Presentation Layer | 40% |
| 整體 | 60% |

### 6. Pull Request 流程

1. **建立 PR**：從 feature 分支合併到 develop
2. **填寫 PR 描述**：
   - 變更摘要
   - 相關 Issue 編號
   - 測試方式
3. **CI 檢查**：Quality → Unit Test → Build 全部通過
4. **Code Review**：至少一位審查者批准
5. **合併**：使用 squash merge 保持 develop 分支乾淨

#### PR 檢查清單

- [ ] 程式碼符合 linter 規範
- [ ] 新增/修改的公開 API 有文件
- [ ] 有對應的測試
- [ ] 不引入新的架構債務
- [ ] 狀態管理遵循單一資料源原則
- [ ] 沒有硬編碼的 magic number
- [ ] 錯誤處理完善
- [ ] Commit message 符合規範

---

## 已知問題

參考 `docs/LESSONS_LEARNED.md` 取得完整的踩坑紀錄。

### 關鍵教訓

1. **單一決策點**：所有 session reset 邏輯必須集中在一個方法中
2. **State Owner**：每份跨層狀態必須有明確的唯一 owner
3. **世代防護**：跨 session 的回調必須有世代計數器
4. **同步守衛**：異步框架（`postValue`、`NetworkCallback`）需要同步守衛
5. **Service 生命週期**：Activity 的 `onDestroy` 不應 stop Service
6. **Force-stop 恢復**：Service 啟動時必須清理殘留狀態
7. **網路路由**：Wi-Fi Direct TCP 連接必須明確綁定到 P2P 介面
8. **NFC 物理特性**：NFC 天線物理上是雙向的，角色必須在第一次 latch 時鎖定
9. **BLE 時序**：BLE notify 可能在 subscribe 前觸發，必須設計 fallback read
10. **狀態語意**：斷線和錯誤是不同的語意，不應混淆

---

## 文件

| 文件 | 說明 |
|------|------|
| `README.md` | 專案概述、建置方式 |
| `docs/ARCHITECTURE_GUIDE.md` | 架構標準、CI/CD 流程 |
| `docs/LESSONS_LEARNED.md` | 踩坑紀錄 |
| `CONTRIBUTING.md` | 本文件 |
| `CHANGELOG.md` | 版本變更紀錄 |

---

## 取得幫助

- **Issue Tracker**：GitHub Issues
- **討論**：GitHub Discussions
- **文件**：`docs/` 目錄
