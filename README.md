# 📱 WebView Diagnostic Probe (WebView 診斷探針)

## 📌 專案簡介
本專案為內部開發的輕量級 Android 測試工具，專門用於排查「混合式架構 App (Hybrid App)」中 Web 端資料載入卡頓、白畫面 (OOM) 或無法顯示的問題。

透過抽離主 App 中複雜的業務邏輯與第三方 SDK 依賴，本探針提供了一個**「純淨的 WebView 沙盒環境」**，讓 QA 與開發團隊能透過控制變數 (如 User-Agent、DOM Storage、Cache) 進行量化壓力測試，快速釐清問題是出在基礎網路設備 (WAF/防火牆)、前端網頁相容性，還是主 App 本身的資源佔用。

---

## 🚀 核心功能
* **自動化壓力測試：** 可自訂測試網址、連續重新整理次數與每次重載的間隔時間。
* **環境變數控制：**
  * **偽裝 UA：** 可切換使用 App 預設 User-Agent 或偽裝成純手機版 Chrome (用於排查 WAF 阻擋)。
  * **DOM Storage 開關：** 驗證現代前端框架 (Vue/React) 載入相容性。
  * **無痕測試：** 支援每次重載前自動清除 Cache 與 Cookie。
* **深度效能監控：**
  * 紀錄每次載入的延遲時間，並計算最大、最小與平均延遲。
  * 攔截 HTTP 錯誤碼 (非 200 回應)。
  * **OOM 白屏捕捉：** 精準捕捉 WebView Render Process 崩潰事件。
  * **Timeout 看門狗：** 內建 15 秒超時強制中斷機制，避免測試死鎖。
* **自動分析報告：** 測試完成後自動根據錯誤特徵給出排查建議。

---

## 🛠️ 如何編譯與安裝 (CI/CD)
本專案已完全整合 GitHub Actions，**無需在本機安裝 Android Studio** 即可打包。

1. 點擊本專案 GitHub 頁面上方的 **[Actions]** 頁籤。
2. 點擊左側的 **Build Android APK** 工作流程。
3. 點擊右側的 **Run workflow** 按鈕啟動雲端編譯。
4. 靜待約 2~3 分鐘，編譯完成後，在該次執行紀錄最下方的 **Artifacts** 區塊，下載 `WebViewProbe-APK`。
5. 解壓縮後將 `app-debug.apk` 傳送至 Android 手機進行安裝。

---

## 📋 QA 測試 SOP (使用指南)

測試人員取得 APK 後，請依照以下劇本進行排查：

### 劇本一：基準壓力測試 (Baseline)
* **目的：** 驗證網頁在最標準的 App 預設環境下是否會出錯。
* **設定：**
  * 輸入目標測試網址。
  * 次數設為 `50`，間隔設為 `2000` (毫秒)。
  * **勾選**「開啟 DOM Storage」(其餘不勾選)。
* **觀察：** 若出現大量 Timeout 或 HTTP 錯誤，進入劇本二。若發生 OOM 白屏，請直接請前端優化記憶體 (減少 DOM 或實作虛擬分頁)。

### 劇本二：WAF 防護排除測試 (UA Spoofing)
* **目的：** 驗證是否為公司防火牆/WAF 將 App 流量誤判為爬蟲。
* **設定：**
  * 參數同上，但**額外勾選**「偽裝原生 Chrome Browser UA」。
* **觀察：** 若劇本一狂報錯，但劇本二「完全順暢正常」，**100% 確定是網路設備 (WAF/Load Balancer) 擋下了 App 的預設 UA**，請立刻交由系統工程師 (Infrastructure Team) 開白名單或調整防禦規則。

### 劇本三：前端快取失效測試 (Cache Flush)
* **目的：** 驗證前端是否因為讀到錯誤的舊快取導致畫面卡死。
* **設定：**
  * 參數同劇本一，但**額外勾選**「每次重載前清除 Cache 與 Cookie」。
* **觀察：** 協助釐清「第一次載入正常，但後續重新整理出不來」的問題。

---

## ⚠️ 團隊維護注意事項

1. **專案架構：** 本專案刻意維持極簡架構 (不使用 MVVM/Jetpack Compose 等複雜框架)，所有邏輯皆集中於 `MainActivity.kt`，UI 集中於 `activity_main.xml`。**請勿在此專案中引入主 App 的業務邏輯或無關的第三方 Library**，以保持探針的絕對純淨。
2. **網址重定向 (Redirect) 處理：** * `MainActivity.kt` 內部已實作 `isWaitingForPage` 布林鎖機制，已解決了目標網址發生 301/302 重定向時導致的 100% Timeout 誤判問題。若未來需擴充攔截邏輯，請務必保留此鎖定機制。
3. **版本升級：** * 若未來需提升 Target SDK 版本，請於 `app/build.gradle` 中修改 `compileSdk` 與 `targetSdk`。
   * 請維持使用原生 `Handler` 取代 `Coroutines` 進行計時，以減少外部依賴帶來的編譯風險。
