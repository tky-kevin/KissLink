# 已知待實作項目與問題（KissLink / 安卓抓普）

最後更新：2026-06-10

## 架構債務

> 架構債務的詳細分析和重構規劃請參考 `docs/ARCHITECTURE_GUIDE.md` 第五節。

簡要清單：
1. **三套重疊的狀態機** — `SessionState.Phase`、`PairingCoordinator.Phase`、`ConnectionState` 需收斂
2. **狀態/生命週期歸屬模糊** — 每份跨層狀態需指定唯一 owner
3. **FileTransferService God Object** — 需抽出 `SessionManager`
4. **WifiDirectManager 狀態轉換分散** — 16+ 個 `setState()` 調用需收斂
5. **時序耦合（magic delays）** — 固定延遲常數需評估事件驅動化

---

## 待實作 / 待強化

- **第三人觸碰切換（tag 側）**：連線中第三人觸碰時的「閒置→立即切換、傳輸中→傳完再切換」
  邏輯已實作，但只有當被觸碰的舊裝置在這次貼合中擔任 **NFC reader**（讀到對方 token）才會切換；
  若它擔任 **tag**（HCE 被讀），目前無法辨識新對象、會被當成「同對象 resume」而不切換。
  解法：tag 側在隨後的 BLE 換 token 取得對方 nonce 後再判定是否切換。需動到配對核心，且需三台手機驗證。

- **「傳完再切換新對象」的時間窗**：要求新對象在整段傳輸期間維持配對狀態（BLE 廣播未逾時）。
  傳大檔時新對象可能已逾時，導致切換失敗。可考慮延長新對象 BLE 廣播時間或加重試。

- **傳送端進度的應用層 ACK（語意精確）**：傳送端進度可能在實際送達前略早到 100%（TCP 送出緩衝造成，
  差距約 1MB）。可在 `TransferProtocol` 加一個反向 `TRANSFER_COMPLETE_ACK`：接收端寫盤 + CRC32 校驗
  完成後回送，傳送端收到才把進度跳 100%。**屬語意 / 觀感層的正確性，傳輸本身已正確完成**；要動到
  運作中的協定，需配 loss-timeout，風險中等，列為可選打磨。

- **動態 GO IP（低優先）**：`WifiDirectManager.GO_IP_ADDRESS` 硬編碼 `192.168.49.1`（Android P2P 標準）。
  可改為 client 端優先取 `info.groupOwnerAddress.getHostAddress()`、null 才 fallback 常數。
  實測所有 log 皆為 `192.168.49.1`，**真實收益極低**，且會動到剛穩定下來的連線路徑，故暫不做。

- **終態時集中清除 Handler 任務（小）**：poll 目前靠 `state == X` 自我終止（最多殘留一個 tick），
  並非真正洩漏。可在 `setState(ERROR/IDLE/DISCONNECTED)` 內集中 `stopGoPoll()/stopClientPoll()/
  cancelTimeout()` 當防禦性 tidy。低優先。

- **kill 後重連的穩定度（OEM 限制）**：已用 `connectedDevice` 前景服務型別、`onDestroy`/開機
  `removeGroup`、傳輸期間 wake lock 緩解；但 MIUI / Samsung 仍可能在 App 退到背景時秒殺前景服務。
  force-stop / OEM kill 不會跑 `onDestroy` → Wi-Fi 群組殘留，**下次啟動 `onCreate` 的 `removeGroup`
  會清掉**（已實測：kill 後群組 `groupFormed:true` 殘留，重開即 `mGroup null`）。需使用者端關閉電池
  最佳化 / 開啟自啟動才能完全穩定（非程式可完全解決）。

---

## 已知小問題（可接受 / 待打磨）

- Launcher 圖示為全幅 adaptive icon，圓形遮罩會裁掉四角（僅漸層背景，主體角色與 Wi-Fi 保留）。
- 接收端多檔：每檔完成會各自短暫顯示「傳輸完成 + 打勾」動畫（語意正確，略閃）。
- 開系統選檔器會讓 Activity `onStop` → 進入背景閒置計時；若在選檔器停留超過 `IDLE_TEARDOWN_MS`（45s）
  且已連線，連線會被自動拆除（返回後待傳清單保留，需再碰一下重連）。若實際造成困擾，可在自家選檔器
  開啟期間抑制計時。

---

## 環境 / 建置備忘

- 專案**已含 Gradle wrapper**（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`，
  指向 Gradle 8.13）。系統 `java` 若為過新版本（如 JDK 25）會與 AGP 8.x 不相容，請以 JDK 21 建置
  （`JAVA_HOME` 指向 Android Studio 的 JBR）：
  ```bash
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # JBR 21
  ./gradlew :app:assembleDebug
  ```
- CI：GitHub Actions 用 `gradle/actions/setup-gradle`；push 到 `main` 或手動 `workflow_dispatch`
  時建置 release APK 並發佈到 GitHub Releases。
- 技術棧：Java（核心：NFC HCE / BLE / Wi-Fi Direct / 傳輸）+ Kotlin/Jetpack Compose（中央動畫模組）。
