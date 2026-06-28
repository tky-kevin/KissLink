# ADR 0003: Wi-Fi Direct GO 選舉演算法

**狀態**：已採用 (Accepted)  
**決策日**：2025-06

## 背景

Wi-Fi Direct 需要其中一台扮演 Group Owner（GO）。Android 提供 `goIntent`（0–15）讓應用程式影響選舉，但系統最終決定。選錯 GO 會導致 SCC 頻段鎖定（GO 的 STA 介面決定群組頻段）。

## 決策

`PairingToken.shouldBeGroupOwner(peer)` 決定本機是否當 GO，規則為：

1. **canHost5G 優先**：能在 5GHz 開群組（未連 STA 或 STA 在 5GHz）的一方當 GO，避免被 2.4 GHz STA 拉低速率。
2. **goIntent 次要**：兩台 canHost5G 相同時，goIntent 較高者當 GO（goIntent = Base + CSPRNG 隨機值，避免每次同一台當 GO 造成電池不均）。
3. **nonce 決勝**：goIntent 也相同時，nonce 字典序較小者當 GO（確保雙邊決策一致，無需額外通訊）。

## 理由

- 純本地計算，不需要額外通訊回合，延遲最小。
- 決定性（Deterministic）：同一對 token 輸入必然兩邊結果互補（一個 true、一個 false），排除選舉平票。
- canHost5G 旗標在 `LocalPairing.setCanHost5G()` 由 `WifiDirectManager.canHostFastGroup()` 即時取得，只在閒置邊界更新（避免選舉不對稱）。

## 後果

- 若兩台都被 2.4 GHz STA 佔用，無法由 GO 選舉改善速率，只能提示使用者（`onSlowLinkWarning`）。
- canHost5G 是近似值（`WifiInfo.getFrequency()` 在部分裝置上不可靠），有時低估 5GHz 能力而讓較慢的那台當 GO。
