# AlipayAccDemo3

Android 無障礙擴充應用，用來配合外部 Server 透過 WebSocket 進行控制與回傳。

這個專案的核心流程是：

1. 使用者先掃描 QR Code，取得 `Token` 與 `wsUrl`
2. App 申請螢幕擷取授權，並要求使用者手動開啟無障礙服務
3. `AlipayAccService` 啟動後連線到 Server
4. 連線成功後先送出 `Auth`
5. Server 再下發指令，App 依指令回傳無障礙節點樹、截圖或執行指定動作

---

## 功能

- QR Code 掃描與授權資料載入
- 無障礙服務啟動與頁面節點樹序列化
- WebSocket 連線與重連
- Server 指令處理
  - 開啟 `alipays://` 之類的 URL Schema
  - 回傳目前無障礙節點樹
  - 透過 MediaProjection 擷取螢幕
  - 等待特定 `ViewId` 出現
- 前景服務型螢幕擷取

---

## 專案結構

- `app/src/main/java/com/example/alipayaccdemo/MainActivity.kt`
  - App 入口畫面、QR Code 掃描、授權流程
- `app/src/main/java/com/example/alipayaccdemo/AlipayAccService.kt`
  - 無障礙服務主體，負責 WebSocket 指令處理
- `app/src/main/java/com/example/alipayaccdemo/RawWebSocketClient.kt`
  - 自行實作的 RFC 6455 WebSocket client
- `app/src/main/java/com/example/alipayaccdemo/AccNodeSerializer.kt`
  - 將 `AccessibilityNodeInfo` 序列化成 JSON
- `app/src/main/java/com/example/alipayaccdemo/capture/ScreenCaptureService.kt`
  - MediaProjection 擷取流程
- `SERVER_API.md`
  - WebSocket 指令格式文件
- `APP_OVERVIEW.md`
  - App 前置動作與整體流程說明
- `FLOW.md`
  - 操作/測試流程說明

---

## 環境需求

- Android Studio
- JDK 17
- Android SDK
  - `minSdk 28`
  - `compileSdk 36`
  - `targetSdk 36`

---

## 權限與前置條件

這個 App 需要以下權限與系統授權：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `CAMERA`
- `POST_NOTIFICATIONS`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- 系統手動開啟的無障礙服務
- 系統授權的螢幕擷取權限

### QR Code 內容格式

App 會從 QR Code 讀取 JSON，至少需要：

```json
{
  "Token": "<token>",
  "wsUrl": "ws://host:port/path"
}
```

---

## 如何執行

### 1. 建置專案

```bash
./gradlew assembleDebug
```

Windows 可使用：

```powershell
gradlew.bat assembleDebug
```

### 2. 安裝到裝置或模擬器

透過 Android Studio 直接執行，或安裝產物 APK 到支援的 Android 裝置。

### 3. App 內操作

1. 開啟 App
2. 點選 `QRCode 掃描`
3. 掃描 Server 提供的 QR Code
4. 點選 `授權`
5. 依系統提示授予螢幕擷取權限
6. 到系統設定手動啟用本 App 的無障礙服務

---

## WebSocket 流程

連線成功後，App 會先送出驗證訊息：

```json
{ "Result": "Auth", "Token": "<token>" }
```

之後 Server 可依目前實作發送以下指令：

- `Open` + `UrlSchema`
- `GetCurrentText`
- `Capture`
- `WaitViewId`

完整格式與回傳欄位請看 [`SERVER_API.md`](./SERVER_API.md)。

---

## 目前的行為重點

- `GetCurrentText` 會回傳目前 `rootInActiveWindow` 的節點樹 JSON
- `Capture` 會透過前景服務擷取畫面並以 Base64 回傳
- `WaitViewId` 會輪詢指定 `viewIdResourceName`
- WebSocket 斷線後會嘗試重連
- 目前程式裡有 `Notify` 的預留事件格式，但沒有找到實際觸發點

---

## 相關文件

- [`APP_OVERVIEW.md`](./APP_OVERVIEW.md)
- [`FLOW.md`](./FLOW.md)
- [`SERVER_API.md`](./SERVER_API.md)

---

## 注意事項

- 這個專案使用明文 WebSocket 的可能性較高，請確認 Server 端 `wsUrl` 是否正確
- 若 `ScanAuth` 尚未完成，無障礙服務啟動後不會有可用的連線資訊
- `WaitViewId` 若沒有提供有效等待秒數，可能會立即超時

