# Android 原生 App 爬蟲 Demo｜Accessibility Service

在不需 Root、不修改目標 App 的原生 Android 環境下，透過無障礙服務（Accessibility Service）讀取目標 App 提供的節點文字、內容描述與畫面階層，讓外部程式能進一步擷取資料與判斷頁面狀態。

本專案以支付寶為目標案例，展示 Android App 爬蟲的裝置端核心：**從原生 App 畫面取得可供程式處理的結構化資料**。

## 專案背景

這個作品源自我過去工作中的 Android App 爬蟲需求，當時的資料擷取對象是支付寶。任務需要在未 Root 的 Android 裝置上進行，因此採用系統提供的無障礙服務，讀取目標 App 暴露的畫面節點與文字資訊。

此儲存庫以 Demo 形式呈現這項實作方式，重點在於無障礙節點擷取、頁面狀態判斷，以及與外部 Server 的指令整合。

## 核心實作

### 1. 透過無障礙服務擷取原生 App 資料

`AlipayAccService` 監聽視窗與內容變化事件，透過 `rootInActiveWindow` 取得並快取目前作用中視窗的 `AccessibilityNodeInfo` 根節點；收到擷取指令後，由 `AccNodeSerializer` 遞迴走訪節點樹，整理為本專案定義的 `NodeSnapshot` 資料物件，保留：

- 文字與內容描述：`text`、`contentDescription`、`hintText`
- 元件識別資訊：`packageName`、`className`、`viewId`
- UI 結構與位置：子節點階層、節點數量、畫面座標範圍
- 元件狀態：是否可見、可點擊、可捲動、已選取等

文字欄位直接取自 `node.text?.toString()`，內容描述取自 `node.contentDescription?.toString()`，並透過 `children` 保留節點階層。外部程式可結合這些文字、ViewId 與位置資訊分析頁面內容。

**無障礙節點是本 Demo 的主要資料來源**。實際能取得的文字取決於執行時的目標頁面及其提供的節點資訊。截圖則提供另一種畫面回傳方式。

### 2. 等待目標節點，銜接資料擷取流程

App 頁面切換與內容載入需要時間。`WaitViewId` 接收指定的 `viewIdResourceName` 與等待秒數，每 2 秒檢查一次最新節點樹，找到目標後回傳該節點及其子樹，逾時則回傳錯誤。

這讓外部程式可以用「目標元件是否出現」判斷何時繼續擷取資料。

### 3. 以 WebSocket 串接外部流程

使用者掃描 QR Code 取得 `Token` 與 `wsUrl`，完成系統授權後，由無障礙服務建立 WebSocket 連線並送出認證訊息。

Server 可下發開啟頁面、讀取節點樹、等待節點及截圖指令；App 以 `GUID` 對應請求與回覆。裝置端負責取得畫面資料，外部 Server 可接續處理資料解析與流程安排。

## 資料擷取流程

```text
使用者掃描 QR Code，完成螢幕擷取與無障礙授權
    ↓
Android App 連線至外部 Server，送出 Token 認證
    ↓
Server 下發指令，透過 URL Scheme 開啟目標頁面
    ↓
Accessibility Service 監聽畫面變化，更新節點樹
    ↓
等待指定 ViewId，或取得目前畫面節點樹
    ↓
將擷取到的節點文字、屬性與階層透過 WebSocket 回傳
```

## Demo 功能與範圍

| 功能 | 實作內容 |
| --- | --- |
| QR Code 配對 | 讀取 Server 提供的 Token 與 WebSocket 位址 |
| 開啟目標頁面 | 透過 `Intent.ACTION_VIEW` 開啟 `alipays://` 等 URL Scheme |
| 畫面資料擷取 | 讀取目前作用中視窗的節點文字、內容描述、屬性與階層 |
| 等待指定節點 | 輪詢 ViewId，找到後回傳節點資料，逾時回覆錯誤 |
| 螢幕擷取 | 透過 MediaProjection 與前景服務取得畫面，以 Base64 回傳 |
| 連線管理 | WebSocket 連線、認證與斷線重連 |

本儲存庫包含 Android 裝置端，Server 需另外準備。展示範圍集中在 App 爬蟲的畫面資料擷取與指令通道，未包含完整的支付寶業務爬取流程、資料儲存後端或 OCR 辨識。

擷取結果取決於目標 App 提供的無障礙資訊；自繪畫面或未提供節點文字的元件，可能無法直接取得內容。目標 App 更新後，也可能需要重新確認 ViewId 與頁面結構。

## 技術與程式結構

- **語言與介面**：Kotlin、Jetpack Compose
- **畫面資料擷取**：Accessibility Service、AccessibilityNodeInfo、Kotlin Serialization
- **流程與通訊**：Kotlin Coroutines、自行實作的 WebSocket Client
- **螢幕擷取與掃碼**：MediaProjection、前景服務、ZXing

主要程式位於 `app/src/main/java/com/example/alipayaccdemo/`：

| 檔案 | 職責 |
| --- | --- |
| [AlipayAccService.kt](app/src/main/java/com/example/alipayaccdemo/AlipayAccService.kt) | 無障礙事件處理、節點狀態更新、Server 指令與連線管理 |
| [AccNodeSerializer.kt](app/src/main/java/com/example/alipayaccdemo/AccNodeSerializer.kt) | 遞迴走訪無障礙節點，整理為 `NodeSnapshot` 資料物件 |
| [MainActivity.kt](app/src/main/java/com/example/alipayaccdemo/MainActivity.kt) | 掃碼介面與系統授權流程 |
| [RawWebSocketClient.kt](app/src/main/java/com/example/alipayaccdemo/RawWebSocketClient.kt) | WebSocket 握手、資料幀傳送與接收、Ping/Pong |
| [ScreenCaptureService.kt](app/src/main/java/com/example/alipayaccdemo/capture/ScreenCaptureService.kt) | 前景服務與 MediaProjection 螢幕擷取 |

## 環境與執行方式

### 開發環境

- Android Studio
- JDK 17
- Android SDK：`minSdk 28`、`compileSdk 36`、`targetSdk 36`
- 可配合此 App 通訊的外部 WebSocket Server

### 建置

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

### 操作流程

1. 透過 Android Studio 執行，或安裝建置產生的 APK。
2. 開啟 App，點選「QRCode掃碼」，掃描 Server 提供的 QR Code。
3. 點選「確認授權」，依系統提示允許螢幕擷取。
4. 到系統設定手動啟用本 App 的無障礙服務。
5. 服務啟動後連線至 Server，等待指令。

QR Code 內容為 JSON，至少包含：

```json
{
  "Token": "<token>",
  "wsUrl": "ws://host:port/path"
}
```

App 需使用相機、網路、通知與前景服務相關權限；無障礙服務與螢幕擷取需由使用者在系統介面授權。

## WebSocket 指令

連線成功後，App 主動送出認證訊息：

```json
{ "Result": "Auth", "Token": "<token>" }
```

| Action | 用途 | 必要參數 |
| --- | --- | --- |
| `Open` | 開啟指定 URL Scheme | `Method: "UrlSchema"`、`Url` |
| `GetCurrentText` | 取得目前畫面的節點文字、屬性與階層 | `Method: ""` |
| `WaitViewId` | 等待並取得指定節點資料 | `Method: ""`、`ViewId`、正整數 `WaitSeconds` |
| `Capture` | 取得螢幕截圖 Base64 | `Method: ""` |

通訊格式使用 JSON。`GetCurrentText` 與成功的 `WaitViewId` 回覆，會將節點資料編碼為 JSON 字串並放入外層回覆的 `Data` 欄位；這是傳輸封裝方式，節點文字的內容仍以目標 App 提供的值為準。

每個指令皆需帶入 `GUID`，供請求與回覆配對。例如：

```json
{
  "Action": "Open",
  "Method": "UrlSchema",
  "Url": "alipays://platformapi/startapp?appId=20000123",
  "GUID": "demo-001"
}
```

目前 WebSocket Client 使用一般 TCP Socket，未實作 TLS，連線位址需使用 `ws://`。掃碼資料保存在記憶體中，程序重啟後需重新掃碼；螢幕擷取也需先完成系統授權。

## 延伸文件

- [App 授權與通訊流程](APP_OVERVIEW.md)
- [裝置操作與測試流程](FLOW.md)
- [Server API 格式與回傳說明](SERVER_API.md)（其中 `Open` 範例的 `Method` 請以本 README 的 `UrlSchema` 為準）
