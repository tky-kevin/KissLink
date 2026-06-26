# ADR 0001: NFC → BLE → Wi-Fi Direct 三層配對架構

**狀態**：已採用 (Accepted)  
**決策日**：2025-06

## 背景

兩台裝置需要在沒有網路的情況下直接點對點傳輸大型檔案。Android 提供多種 P2P 技術：NFC（低速、近場）、BLE（低功耗、近場）、Wi-Fi Direct（高頻寬、中距離）。

## 決策

採用三層式配對架構：

1. **NFC latch**（觸發層）：兩機背靠背感應，瞬間取得對方 token（含 nonce、goIntent、canHost5G）。NFC 僅用於「認識對方是誰」，不傳憑證。
2. **BLE 側通道**（憑證交換層）：以 nonce 作 scanFilter 精準鎖定對方 peripheral，交換雙方 token、進行 GO 選舉，再把 Wi-Fi Direct 群組憑證傳給非 GO 方。
3. **Wi-Fi Direct TCP**（資料傳輸層）：建立 P2P 群組後在固定埠建立 TCP 連線，所有實際傳輸均走此通道。

## 理由

- NFC 觸發零摩擦（不需要使用者選擇裝置），但頻寬太低、不適合傳憑證。
- BLE 可靠地在 NFC 範圍內精準識別對方（nonce scanFilter），且不需要系統配對流程。
- Wi-Fi Direct 提供 100-500 Mbps 的實際傳輸速率，遠優於 BLE (< 1 Mbps)。
- 三層分離讓各層可獨立演進（例如未來可用 UWB 替換 NFC 觸發層）。

## 後果

- 必須同時持有 NFC、BLE、Wi-Fi 權限。
- SCC（Wi-Fi Direct 頻段鎖定）問題：若 GO 的 STA 介面在 2.4 GHz，P2P 群組被鎖 2.4 GHz，速度從 ~500 Mbps 降至 ~13 Mbps。透過 canHost5G 旗標輔助 GO 選舉緩解，但無法完全消除。
- BLE 密集重碰（連觸）有 GATT status 133 問題，已實作 3 次重試 + 交握逾時自癒。
