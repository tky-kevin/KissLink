# KissLink 架構規劃與業界最佳實踐

> 本文件定義 KissLink 的架構標準、開發模式、CI/CD 流程和 Linter 規範。
> 目標：建立業界普遍認可的開發基線，減少架構債務。

最後更新：2026-06-14

---

## 一、現代 Android App 架構標準

### 1.1 Clean Architecture 分層

```
┌─────────────────────────────────────────────────┐
│                 Presentation Layer               │
│   Activities / Fragments / Compose UI / ViewModels│
│   職責：UI 邏輯、使用者互動、狀態觀察               │
├─────────────────────────────────────────────────┤
│                  Domain Layer                    │
│   UseCases / Domain Models / Repository Interfaces│
│   職責：業務邏輯、資料轉換、介面定義               │
├─────────────────────────────────────────────────┤
│                   Data Layer                     │
│   Repository Implementations / Data Sources       │
│   Room DB / Network / BLE / NFC / Wi-Fi Direct   │
│   職責：資料存取、外部服務互動、資料轉換           │
└─────────────────────────────────────────────────┘
```

### 1.2 單向資料流（Unidirectional Data Flow）

```
使用者互動 → ViewModel → UseCase → Repository → DataSource
                ↑                                        │
                └──── StateFlow / LiveData ←─────────────┘
```

**原則**：
- 資料單向流動，不可逆向
- ViewModel 不直接操作 UI，只暴露 State
- UI 只觀察 State，不主動查詢

### 1.3 依賴注入

使用 Hilt（Android 官方推薦的 DI 框架）：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideSessionManager(...): SessionManager = SessionManagerImpl(...)
}

@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {
    @Provides
    fun provideTransferService(@ApplicationContext context: Context): TransferService = ...
}
```

**原則**：
- 構造函式注入，不用 Service Locator
- Scope 對應生命週期（Singleton → App、ActivityScoped → Activity、ServiceScoped → Service）
- 不在 Activity/Service 中直接 `new` 物件

### 1.4 狀態管理

```kotlin
// 推薦：StateFlow（取代 LiveData）
class SessionViewModel @Inject constructor(
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    // 單一狀態對象，不可變
    data class SessionUiState(
        val phase: SessionPhase = SessionPhase.IDLE,
        val peerName: String? = null,
        val transferProgress: Float = 0f,
        val error: String? = null
    )
}
```

**原則**：
- 單一 StateFlow 暴露 UI 狀態
- 狀態不可變（data class + val）
- 狀態轉換透過 `copy()` + reducer
- 錯誤是狀態的一部分，不是事件

---

## 二、Linter 與程式碼品質

### 2.1 Kotlin Linter：ktlint

**安裝**：在 `build.gradle` 中加入：

```groovy
// 根 build.gradle
plugins {
    id 'org.jlleitschuh.gradle.ktlint' version '12.1.2' apply false
}

// app/build.gradle
plugins {
    id 'org.jlleitschuh.gradle.ktlint'
}

ktlint {
    reporter = arrayReporter(ReporterType.CHECKSTYLE, ReporterType.SARIF)
    ignoreFailures = false  // CI 中必須通過
    enableExperimentalRules = true
    filter {
        // 排除生成的檔案
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}
```

**規則**：
- 使用 `ktlint --format` 自動修正
- CI 中執行 `ktlintCheck`，不通過則 fail
- 禁止 `@Suppress("ktlint")` 除非有明確理由

### 2.2 Java Linter：Spotless + Google Java Format

```groovy
// app/build.gradle
plugins {
    id 'com.diffplug.spotless' version '6.25.0'
}

spotless {
    java {
        googleJavaFormat('1.19.2').aosp()  // AOSP style (4-space indent)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    groovyGradle {
        greclipse()
    }
}
```

**規則**：
- 使用 Google Java Format（AOSP 風格）
- CI 中執行 `spotlessCheck`

### 2.3 Static Analysis：detekt（Kotlin）+ lint（Android）

```groovy
// app/build.gradle
plugins {
    id 'com.android.library'  // lint 已內建
    id 'io.gitlab.arturbosch.detekt' version '1.23.7'
}

detekt {
    input = files("src/main/java", "src/main/kotlin")
    config = files("config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

android {
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError true  // CI 中必須通過
        warningsAsErrors true
    }
}
```

### 2.4 程式碼風格規範

| 規範 | 說明 |
|------|------|
| **檔案長度** | 單一檔案不超過 400 行（超過則拆分） |
| **方法長度** | 單一方法不超過 50 行 |
| **類別職責** | 單一職責原則（SRP），每個類別只做一件事 |
| **命名** | 類別 PascalCase、方法 camelCase、常數 SCREAMING_SNAKE_CASE |
| **縮排** | 4 空格（Java）/ 4 空格（Kotlin） |
| **行長** | 120 字元上限 |
| **Import** | 不使用 wildcard import，按字母排序 |

---

## 三、業界標準開發模式

### 3.1 Git 分支策略

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

**規則**：
- `main` 只接受 `release` 和 `hotfix` 的合併
- `develop` 是開發主線，所有 feature 分支從此分出
- Feature 分支命名：`feature/short-description`
- Bug fix 命名：`bugfix/short-description`
- Commit message 使用 Conventional Commits 格式

### 3.2 Commit Message 格式

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Type**：
| Type | 說明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修復 |
| `refactor` | 重構（不改變功能） |
| `docs` | 文件更新 |
| `style` | 程式碼風格（不影響邏輯） |
| `test` | 測試 |
| `chore` | 建構/工具變更 |
| `perf` | 效能改善 |

**範例**：
```
feat(nfc): add private AID to avoid system conflict

Switch from standard NDEF AID (D2760000850101) to custom AID (F04B495353)
to prevent Samsung ConflictResolverActivity from appearing.

Fixes #42
```

### 3.3 Code Review 清單

- [ ] 程式碼符合 linter 規範
- [ ] 新增/修改的公開 API 有文件
- [ ] 有對應的測試（單元測試 + 整合測試）
- [ ] 不引入新的架構債務
- [ ] 狀態管理遵循單一資料源原則
- [ ] 沒有硬編碼的 magic number
- [ ] 錯誤處理完善
- [ ] 效能影響可接受

### 3.4 測試策略

```
┌─────────────────────────────────────────┐
│           測試金字塔                      │
├─────────────────────────────────────────┤
│                                         │
│          ┌─────────┐                    │
│          │ E2E Test│  ← 少量，實機測試   │
│         ┌┴─────────┴┐                   │
│         │ Integration│  ← 中量，API 測試 │
│        ┌┴────────────┴┐                 │
│        │  Unit Test    │  ← 大量，快速    │
│        └───────────────┘                 │
│                                         │
└─────────────────────────────────────────┘
```

| 測試類型 | 工具 | 覆蓋範圍 | 執行環境 |
|---------|------|---------|---------|
| **Unit Test** | JUnit 5 + Mockk | 業務邏輯、資料轉換、狀態轉換 | JVM（快速） |
| **Integration Test** | JUnit 5 + MockWebServer | Repository、API 互動 | JVM |
| **Android Test** | Espresso + UI Automator | UI 互動、元件整合 | 實機/模擬器 |
| **E2E Test** | UI Automator | 完整使用者流程 | 實機（NFC/BLE 必要） |

**關鍵測試場景**（KissLink 特有）：
- Session 狀態轉換（所有路徑）
- NFC latch 角色鎖定
- Session generation guard
- Dirty state teardown + settle
- TCP 路由（WiFi 連接/斷開兩種場景）
- BLE notify-before-subscribe fallback

---

## 四、CI/CD 流程

### 4.1 完整 CI Pipeline

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

jobs:
  # ── Stage 1: 程式碼品質（< 2 分鐘）──
  quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.13"

      - name: Run ktlint
        run: ./gradlew ktlintCheck --no-daemon

      - name: Run spotless (Java)
        run: ./gradlew spotlessCheck --no-daemon

      - name: Run detekt
        run: ./gradlew detekt --no-daemon

      - name: Run Android Lint
        run: ./gradlew :app:lintDebug --no-daemon

      - name: Upload lint results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-results
          path: app/build/reports/lint-results-debug.html

  # ── Stage 2: 單元測試（< 5 分鐘）──
  unit-test:
    runs-on: ubuntu-latest
    needs: quality
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.13"

      - name: Run unit tests
        run: ./gradlew :app:testDebugUnitTest --no-daemon --stacktrace

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-results
          path: app/build/reports/tests/

  # ── Stage 3: 建置（< 10 分鐘）──
  build:
    runs-on: ubuntu-latest
    needs: unit-test
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.13"

      - name: Build debug APK
        run: ./gradlew :app:assembleDebug --no-daemon --stacktrace

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk

  # ── Stage 4: 整合測試（僅 PR，< 15 分鐘）──
  integration-test:
    runs-on: ubuntu-latest
    needs: build
    if: github.event_name == 'pull_request'
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.13"

      - name: Run instrumented tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          script: ./gradlew :app:connectedDebugAndroidTest --no-daemon

      - name: Upload instrumented test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: instrumented-test-results
          path: app/build/reports/androidTests/

  # ── Stage 5: Release（僅 main 分支）──
  release:
    runs-on: ubuntu-latest
    needs: build
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.13"

      - name: Build release APK
        run: ./gradlew :app:assembleRelease --no-daemon --stacktrace

      - name: Publish to GitHub Releases
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1.0.${{ github.run_number }}
          name: 安卓抓普 v1.0.${{ github.run_number }}
          body: |
            自動建置的正式版 APK。
            Commit: ${{ github.sha }}
          files: app/build/outputs/apk/release/app-release.apk
          fail_on_unmatched_files: true
```

### 4.2 Pipeline 階段說明

| 階段 | 時間 | 觸發條件 | 失敗行為 |
|------|------|---------|---------|
| **Quality** | < 2 min | 所有 push/PR | 阻止合併 |
| **Unit Test** | < 5 min | Quality 通過後 | 阻止合併 |
| **Build** | < 10 min | Unit Test 通過後 | 阻止合併 |
| **Integration Test** | < 15 min | 僅 PR | 阻止合併 |
| **Release** | < 10 min | 僅 main push | 自動發佈 |

### 4.3 Branch Protection Rules

```yaml
# GitHub Settings → Branches → main
required_status_checks:
  - quality
  - unit-test
  - build
enforce_admins: false  # 管理者也需遵守
required_pull_request_reviews:
  required_approving_review_count: 1
  dismiss_stale_reviews: true
allow_force_pushes: false
allow_deletions: false
```

---

## 五、KissLink 特定架構規劃

### 5.1 目標架構

```
┌─────────────────────────────────────────────────────┐
│                  Presentation Layer                  │
│                                                     │
│  HomeActivity ←── BeamStageView (Compose)           │
│       │                                             │
│       ├── HistorySheet                              │
│       ├── ProfileCardSheet                          │
│       └── ReceivedCardSheet                         │
│                                                     │
│  Observes: SessionUiState (StateFlow)               │
├─────────────────────────────────────────────────────┤
│                   ViewModel Layer                   │
│                                                     │
│  SessionViewModel                                   │
│       │                                             │
│       ├── uiState: StateFlow<SessionUiState>        │
│       ├── sendItems(items: List<SendItem>)          │
│       ├── disconnect()                              │
│       └── cancel()                                  │
├─────────────────────────────────────────────────────┤
│                    Domain Layer                     │
│                                                     │
│  SessionManager (取代 FileTransferService 的業務邏輯) │
│       │                                             │
│       ├── handleLatch(token, peerToken)             │
│       ├── createSession()                           │
│       ├── teardownSession()                         │
│       └── state: StateFlow<SessionState>            │
│                                                     │
│  TransferManager                                    │
│       │                                             │
│       ├── connect(peer: PeerInfo)                   │
│       ├── send(items: List<SendItem>)               │
│       ├── progress: StateFlow<TransferProgress>     │
│       └── disconnect()                              │
├─────────────────────────────────────────────────────┤
│                     Data Layer                      │
│                                                     │
│  PairingCoordinator (NFC → BLE → Wi-Fi Direct)     │
│  WifiDirectManager (P2P 群組管理)                    │
│  PeerConnection (TCP framed transfer)               │
│  TransferProtocol (binary wire protocol)            │
│  ProfileStore (vCard persistence)                   │
│  TransferRepository (Room DB)                       │
├─────────────────────────────────────────────────────┤
│               Android Service Shell                 │
│                                                     │
│  FileTransferService (薄殼：生命週期 + 前台通知)      │
│       │                                             │
│       ├── onCreate → init SessionManager             │
│       ├── onBind → return Binder                     │
│       └── onDestroy → cleanup                        │
└─────────────────────────────────────────────────────┘
```

### 5.2 重構路線圖

#### Phase 1：低風險快修（1-2 天）

| # | 動作 | 風險 | 預期效果 |
|---|------|------|---------|
| 1.1 | 加入 ktlint + spotless + detekt | LOW | 程式碼品質基線 |
| 1.2 | 加入 CI quality stage | LOW | 自動化品質檢查 |
| 1.3 | 修復 WiFi 路由問題（`Network.bindSocket()`） | LOW | 修復嚴重 bug |
| 1.4 | 提取 `IdleTeardownManager` | LOW | Service 減 ~30 行 |
| 1.5 | 提取 `ServiceNotificationHelper` | LOW | Service 減 ~30 行 |
| 1.6 | 提取 `WakeLockManager` | LOW | Service 減 ~20 行 |
| 1.7 | 修復 `transferring` flag 為 computed value | LOW | 消除同步窗口 |

#### Phase 2：結構改善（3-5 天）

| # | 動作 | 風險 | 預期效果 |
|---|------|------|---------|
| 2.1 | 加入 Hilt 依賴注入 | MEDIUM | 解耦物件建立 |
| 2.2 | 引入 StateFlow 取代 LiveData | MEDIUM | 現代化狀態管理 |
| 2.3 | 提取 `PeerConnector`（TCP 重試） | MEDIUM | Service 減 ~90 行 |
| 2.4 | 將 TransferRepository 依賴從 PeerConnection 移除 | MEDIUM | 解耦傳輸層與持久層 |
| 2.5 | 建立 Domain Layer（SessionManager + TransferManager） | MEDIUM | 清晰的業務邏輯層 |
| 2.6 | 加入整合測試（JUnit 5 + MockWebServer） | MEDIUM | 回歸測試網 |

#### Phase 3：核心重構（1-2 週）

| # | 動作 | 風險 | 預期效果 |
|---|------|------|---------|
| 3.1 | 提取 SessionManager | HIGH | Service 從 607 行降至 ~200 行 |
| 3.2 | 統一狀態機為單一 Phase | HIGH | 消除映射漂移 |
| 3.3 | 將 WifiDirectManager 轉換收斂為 `transition()` | HIGH | 狀態轉換可預測 |
| 3.4 | 加入 E2E 測試（實機） | HIGH | 完整回歸測試網 |

#### Phase 4：長期改善（可選）

| # | 動作 | 風險 | 備註 |
|---|------|------|------|
| 4.1 | Magic delay 事件驅動化 | MEDIUM | 評估 P2P 框架 |
| 4.2 | 遷移至 Compose Navigation | LOW | 單 Activity 架構 |
| 5.3 | 遷移至 Kotlin Multiplatform | HIGH | 長期目標 |

### 5.3 預期成果

| 指標 | 重構前 | Phase 2 後 | Phase 3 後 |
|------|--------|-----------|-----------|
| FileTransferService 行數 | 607 | ~490 | ~200 |
| FileTransferService 欄位數 | 24 | ~18 | ~8 |
| 職責數量 | 7 | 4 | 2 |
| 測試覆蓋率 | ~10% | ~40% | ~70% |
| CI 階段數 | 1（release） | 3 | 5 |
| Lint 規則數 | 0 | 3 | 3 |

---

## 六、文件維護

### 6.1 必須維護的文件

| 文件 | 說明 | 更新時機 |
|------|------|---------|
| `README.md` | 專案概述、建置方式 | 每次 release |
| `ARCHITECTURE.md` | 架構圖、設計決策 | 架構變更時 |
| `LESSONS_LEARNED.md` | 踩坑紀錄 | 每次修 bug |
| `CHANGELOG.md` | 版本變更紀錄 | 每次 release |
| `CONTRIBUTING.md` | 貢獻指南 | 新人加入時 |

### 6.2 文件標準

- 使用 Markdown 格式
- 包含 Mermaid 架構圖
- 中文撰寫（技術術語保留英文）
- 每個文件顶部標註最後更新時間
