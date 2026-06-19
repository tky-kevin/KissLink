# KissLink 貢獻指南

> 本文件說明如何參與 KissLink 開發，包含環境設定、開發流程和提交規範。
> 完整的架構標準、CI/CD 流程和 Linter 規範請參考 `docs/ARCHITECTURE_GUIDE.md`。

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

---

## Pull Request 流程

1. **建立 PR**：從 feature 分支合併到 develop
2. **填寫 PR 描述**：
   - 變更摘要
   - 相關 Issue 編號
   - 測試方式
3. **CI 檢查**：Quality → Unit Test → Build 全部通過
4. **Code Review**：至少一位審查者批准
5. **合併**：使用 squash merge 保持 develop 分支乾淨

### PR 檢查清單

- [ ] 程式碼符合 linter 規範
- [ ] 新增/修改的公開 API 有文件
- [ ] 有對應的測試
- [ ] 不引入新的架構債務
- [ ] 狀態管理遵循單一資料源原則
- [ ] 沒有硬編碼的 magic number
- [ ] 錯誤處理完善
- [ ] Commit message 符合規範

---

## 關鍵教訓

參考 `docs/LESSONS_LEARNED.md` 取得完整的踩坑紀錄。

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
| `docs/ARCHITECTURE_GUIDE.md` | 架構標準、CI/CD 流程、Linter 規範 |
| `docs/LESSONS_LEARNED.md` | 踩坑紀錄 |
| `CONTRIBUTING.md` | 本文件 |
| `CHANGELOG.md` | 版本變更紀錄 |

---

## 取得幫助

- **Issue Tracker**：GitHub Issues
- **討論**：GitHub Discussions
- **文件**：`docs/` 目錄
