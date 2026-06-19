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

## 五、KissLink 架構分析與重構規劃

### 5.1 架構問題總覽

#### God Object：FileTransferService（607 行 / 24 欄位 / 17 私有方法）

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

#### 三套重疊的狀態機

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

#### 狀態/生命週期歸屬模糊

| 狀態 | 建立者 | 銷毀者 | 問題 |
|------|--------|--------|------|
| Wi-Fi Group | `WifiDirectManager.createGroupAsGO()` | `FileTransferService.teardownSession()` + `onDestroy()` + `onCreate()` | 建立在 Manager、銷毀在 Service |
| HCE Token | `FileTransferService.createCoordinator()` | `FileTransferService.onDestroy()` | ✅ 已修正，歸屬明確 |
| Peer Connection | `FileTransferService.startPeer()` | `teardownPeer()` 但可從 6 條路徑觸發 | 銷毀路徑過多 |
| `transferring` flag | `peerProgressObs` observer | `teardownSession()`、`onDisconnected()`、`interruptPairing()` | 可從 `sessionLd` 派生，獨立布林值可能不同步 |

#### WifiDirectManager 狀態轉換分散

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

#### 時序耦合（Magic Delays）

| 常數 | 值 | 用途 | 風險 |
|------|-----|------|------|
| `RESET_SETTLE_MS` | 1800ms | 髒狀態拆除後的沉澱時間 | 偶爾不足，已知問題 |
| `STAGE_MIN_DWELL_MS` | ? | 各階段最小顯示時間 | UI 覺感用 |
| `IDLE_TEARDOWN_MS` | 45s | 閒置自動拆除 | 選檔器場景可能誤觸 |
| `SO_TIMEOUT` | 7000ms | Socket 活性偵測 | 合理但硬編碼 |
| `HEARTBEAT_INTERVAL` | 2500ms | 心跳間隔 | 合理但硬編碼 |

### 5.2 檔案耦合度分析

#### 耦合矩陣

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

#### 可切斷的耦合

| 耦合 | 來源 | 目標 | 解法 | 價值 |
|------|------|------|------|------|
| FTS → TransferRepository | `startPeer()` L500 | `getInstance()` | 注入 `TransferLogger` 函式介面 | 高 |
| FTS → ProfileStore | `startPeer()` L489 | `get(this)` | 傳入構造參數（已有 selfName/selfAvatarThumb） | 中 |
| FTS → KissLinkHCEService | `createCoordinator()`/`onDestroy()` | 靜態方法 | Token Holder 介面（但靜態單例是務實選擇） | 低 |
| PeerConnection → TransferRepository | `onItemCompleted` callback | `getInstance()` | 透過 Listener 回呼由 Service 處理 | 高 |

### 5.3 重構可行性與 CP 值評估

#### 評估維度說明

- **可行性**：技術風險、是否需實機測試、是否涉及核心傳輸路徑
- **CP 值**：改善幅度 / (工作量 × 風險)
- **風險等級**：LOW / MEDIUM / HIGH / VERY HIGH

#### 各重構項目評估

##### 項目 A：提取 SessionManager（從 FileTransferService）

| 維度 | 評估 |
|------|------|
| **目標** | 將 NFC 碰觸決策 + Session 生命週期 + Peer 身份管理 + 進度橋接抽出為 `SessionManager`，Service 退回薄殼 |
| **可行性** | ⚠️ 高風險 |
| **工作量** | 大（~300 行搬移 + 測試） |
| **風險** | HIGH — Session 管理深度交織，threading contract（`@MainThread`）必須保留，`sessionGen` 必須保持原子性 |
| **實機測試** | 必須，所有 NFC→配對→傳輸→斷線路徑 |
| **預期收益** | 高 — God Object 從 607 行降至 ~200 行，職責清晰 |
| **CP 值** | ★★★☆☆ 風險偏高但收益最大 |

##### 項目 B：提取閒置拆除 + 通知 + Wake Lock

| 維度 | 評估 |
|------|------|
| **目標** | 將 `IdleTeardownManager`、`ServiceNotificationHelper`、`WakeLockManager` 從 Service 抽出 |
| **可行性** | ✅ 高可行 |
| **工作量** | 小（~80 行搬移） |
| **風險** | LOW — 自包含邏輯，不涉及核心傳輸路徑 |
| **實機測試** | 最少 — 驗證通知顯示、Wake Lock 防 CPU 休眠、閒置計時器觸發 |
| **預期收益** | 中 — Service 減 ~80 行，清晰度提升 |
| **CP 值** | ★★★★★ 低風險高回報的入門重構 |

##### 項目 C：收斂三套狀態機

| 維度 | 評估 |
|------|------|
| **目標** | 統一為單一 Phase 推進，`ConnectionState` 和 `PairingCoordinator.Phase` 退為邊界 adapter |
| **可行性** | ⚠️ 極高風險 |
| **工作量** | 極大（改三個 enum + 所有映射 + 所有觀察者） |
| **風險** | VERY HIGH — 觸及最敏感的狀態傳播路徑，`postValue` 異步性、`observeForever` 模式都需重新驗證 |
| **實機測試** | 必須，每一條狀態轉換路徑 |
| **預期收益** | 高 — 消除映射漂移風險，狀態追蹤單一化 |
| **CP 值** | ★★☆☆☆ 風險遠大於收益，不建議一次做 |

##### 項目 D：收斂 WifiDirectManager 狀態轉換

| 維度 | 評估 |
|------|------|
| **目標** | 將 16+ 個 `setState()` 調用收斂為統一 `transition()` 方法 |
| **可行性** | ⚠️ 高風險 |
| **工作量** | 中（~200 行重構） |
| **風險** | HIGH — `starting` 守衛、`postValue` 異步性、P2P 回呼順序都有微妙依賴 |
| **實機測試** | 必須，GO/Client 兩側的群組建立 + 斷線重連 |
| **預期收益** | 中 — 狀態轉換可預測性提升 |
| **CP 值** | ★★★☆☆ 中等收益但高風險 |

##### 項目 E：分離 TCP Socket 建立邏輯

| 維度 | 評估 |
|------|------|
| **目標** | 將 `establishPeer`、`acceptAsServer`、`connectAsClient` 抽出為 `PeerConnector` |
| **可行性** | ✅ 中高可行 |
| **工作量** | 中（~90 行搬移 + 30 次重試邏輯） |
| **風險** | MEDIUM — TCP 連接時序與 Wi-Fi Direct 群組形成時序敏感相關 |
| **實機測試** | 必須，GO 和 Client 兩側的 TCP 連接 |
| **預期收益** | 中 — Service 再減 ~90 行 |
| **CP 值** | ★★★★☆ 可行且有實質收益 |

##### 項目 F：修復 `transferring` flag 同步問題

| 維度 | 評估 |
|------|------|
| **目標** | 將 `transferring` 改為從 `sessionLd` 派生，消除獨立布林值 |
| **可行性** | ✅ 高可行 |
| **工作量** | 小（~15 行改動） |
| **風險** | LOW — 改為 computed value 不涉及狀態傳播 |
| **實機測試** | 最少 — 驗證第三人觸碰切換場景 |
| **預期收益** | 小 — 消除一個潛在的不同步窗口 |
| **CP 值** | ★★★★★ 小投入大回報 |

### 5.4 風險矩陣

```
        低風險                中風險                高風險              極高風險
高收益  [1.4 transferring]   [2.2 解耦 Repo]     [3.1 SessionMgr]   [4.2 統一狀態機]
        [1.1-1.3 提取工具類]  [2.1 PeerConnector]
        
中收益  [1.5 更新 Javadoc]   [2.3 重構 lambda]   [3.2-3.3 Session]  [4.1 WDM 收斂]
        
低收益                                        [4.3 Magic delay]
```

### 5.5 不該動的檔案

| 檔案 | 原因 |
|------|------|
| `TransferProtocol.java` | 無狀態、乾淨、文件完整 |
| `PairingToken.java` | 職責單一、不可變、GO 選舉邏輯正確 |
| `LocalPairing.java` | 簡單單例、Token 生命週期正確 |
| `ConnectionState.java` | 簡單 enum、正確 |
| `WifiDirectReceiver.java` | 薄委派層、正確 |
| `WifiDirectEventCallback.java` | 乾淨介面、有利測試 |
| `BeamStageView.kt` | Compose 動畫模組、與核心傳輸無關 |

### 5.6 目錄結構

```
KissLink/
├── .github/workflows/
│   └── android.yml              # CI：構建 + 發佈 Release APK
├── build.gradle                  # 根建構檔（AGP + Kotlin）
├── settings.gradle               # 單模組：:app
├── gradle.properties             # AndroidX、JVM 記憶體
├── README.md                     # 專案文件
├── DIAGRAMS.md                   # Mermaid 架構圖
├── KNOWN_ISSUES.md               # 已知問題、TODO
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
        │   ├── util/             # 工具類
        │   └── wifidirect/       # Wi-Fi Direct 管理
        └── res/                  # 資源檔
```

### 5.7 設計模式

| 模式 | 實作 | 說明 |
|------|------|------|
| **SSOT** | `SessionState` | 不可變資料類，合併三個獨立狀態流為單一 `LiveData<SessionState>` |
| **Repository** | `TransferRepository` | 封裝 Room DAO 操作，透過 `ExecutorService` 在背景執行緒執行 |
| **Observer** | LiveData | `sessionLd`、`stateLd`、`credentialLd`、`progressLd`、`incomingCardLd` |
| **State Machine** | 三套重疊狀態機 | `PairingCoordinator.Phase`、`ConnectionState`、`SessionState.Phase` |
| **Builder** | `TransferProgress.Builder`、`PairingToken` | 流式 API 建構 |
| **Singleton** | `AppDatabase.getInstance()`、`ProfileStore.get()`、`TransferRepository.getInstance()` | 雙重檢查鎖 |
| **Service + Binder** | `FileTransferService.TransferBinder` | 暴露型別化 API，實現安全的跨元件通訊 |
| **Session Generation Guard** | `FileTransferService.sessionGen` | 遞增計數器，防止過期回調影響當前會話 |
| **Dirty/Clean Teardown** | `handleLatch()` | 統一的 NFC 觸碰處理：進行中忽略、待重置忽略、乾淨狀態立即連接、髒狀態先拆除再重建 |

### 5.8 關鍵架構決策

| 決策 | 原因 |
|------|------|
| 自訂二進制傳輸協議 | 最大化控制 framing 和錯誤偵測 |
| BLE 作為側通道 | NFC 載荷小、速度慢，不適合交換 Wi-Fi Direct 憑證 |
| 確定性 GO 選舉 | 使用 goIntent（主要）+ nonce（平手），無需網路通訊 |
| 前台 Service（connectedDevice） | 碰觸互動中短暫背景切換時保持存活 |
| Wake Lock | 防止 CPU 降速中斷 Socket 連接 |
| 閒置拆除計時器（45 秒） | 節省電力，同時容忍短暫 App 切換 |

### 5.9 目標架構

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

### 5.10 重構路線圖

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

### 5.11 預期成果

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
