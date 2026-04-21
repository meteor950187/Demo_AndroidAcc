# AlipayAccDemo3 App 前置動作與 Server 溝通說明

## 1. 前置動作與需要授予的權限

### 1.1 使用者操作流程（App 內）
1. 開啟 App（MainActivity）。
2. 點選「QRCode 掃描」，掃描 Server 提供的 QR Code。
   - QR Code 內容必須是 JSON，至少包含：
     - `Token`
     - `wsUrl`
   - App 會把 `Token` 與 `wsUrl` 寫入 `ScanAuth`（記憶體），作為之後 WebSocket 連線與認證使用。
3. 點選「授權」：
   - 第一步：取得螢幕擷取授權（MediaProjection）。
   - 第二步：跳轉到「無障礙設定」並手動啟用本 App 的無障礙服務。

### 1.2 Manifest 內宣告的權限
來源：`app/src/main/AndroidManifest.xml`
- `android.permission.INTERNET`
  - WebSocket 與 Server 溝通必須。
- `android.permission.ACCESS_NETWORK_STATE`
  - 用於判斷網路狀態（可選但已宣告）。
- `android.permission.CAMERA`
  - QRCode 掃描使用相機。
- `android.permission.POST_NOTIFICATIONS`
  - Android 13+ 必須允許通知，否則前景服務通知可能無法顯示。
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`
  - 螢幕擷取服務（`ScreenCaptureService`）以前景服務執行，且屬於 MediaProjection 類型。

### 1.3 需要使用者在系統設定中手動授權
- **無障礙服務（Accessibility Service）**
  - 服務：`AlipayAccService`
  - 需要在系統的「無障礙設定」中手動開啟。
- **螢幕擷取（MediaProjection）**
  - 由系統彈窗授權；App 使用 `MediaProjectionManager` 觸發。
- **通知權限（Android 13+）**
  - 若系統要求，需允許通知，否則前景服務通知可能被阻擋。
- **相機權限**
  - QRCode 掃描需要相機權限。若未授權，掃描會失敗。

### 1.4 其他重要設定
- `app/src/main/res/xml/network_security_config.xml` 允許 `cleartextTraffic`：
  - 代表 WebSocket 可能是 `ws://`（明文），不是 `wss://`。

---

## 2. App 與 Server 的溝通方式

### 2.1 連線流程（WebSocket）
1. 使用者掃描 QR Code，解析出 `Token` 與 `wsUrl`。
2. `AlipayAccService` 在 `onServiceConnected()` 內嘗試建立 WebSocket 連線。
3. WebSocket 連線成功後，App 會送出認證訊息：

範例（App -> Server）：
```json
{ "Result": "Auth", "Token": "<token>" }
```

### 2.2 WebSocket 連線實作
- Client 實作：`RawWebSocketClient`
- 走 TCP + WebSocket (RFC6455) handshake，自行處理：
  - frame / ping-pong / close / fragmentation
- 連線參數來源：
  - `ScanAuth.wsUrl`（由 QRCode 提供）

### 2.3 Server 指令與 App 行為
WebSocket 收到 JSON 後由 `doServerMethod()` 處理。

#### (1) Open / UrlSchema
Server 指令：
```json
{ "Action": "Open", "Method": "UrlSchema", "Url": "alipays://...", "GUID": "..." }
```
App 行為：
- 呼叫 `Intent.ACTION_VIEW` 開啟指定 Url Schema。
- 回傳 OK：
```json
{ "Result": "OK", "GUID": "...", "Message": null }
```

#### (2) GetCurrentText
Server 指令：
```json
{ "Action": "GetCurrentText", "Method": "...", "GUID": "..." }
```
App 行為：
- 取得目前 `rootInActiveWindow` 節點樹。
- 以 `AccNodeSerializer` 序列化成 JSON 字串。
- 回傳結果：
```json
{ "Result": "OK", "GUID": "...", "Message": null, "Data": "<node-tree-json>" }
```

#### (3) Capture
Server 指令：
```json
{ "Action": "Capture", "Method": "...", "GUID": "..." }
```
App 行為：
- 透過 `ScreenCaptureService` 進行 MediaProjection 螢幕擷取。
- 取回畫面後轉成 Base64。
- 回傳結果：
```json
{ "Result": "OK", "GUID": "...", "Message": null, "Data": "<base64-image>" }
```

#### (4) WaitViewId
Server 指令：
```json
{ "Action": "WaitViewId", "ViewId": "<view-id>", "WaitSeconds": 20, "GUID": "..." }
```
App 行為：
- 以無障礙 API 持續監控（輪詢）是否出現指定 `viewIdResourceName`。
- 找到後回傳節點序列化資料。
- 超時則回傳 ERR。

### 2.4 App 回傳訊息格式
所有回傳均包含以下結構：
```json
{ "Result": "OK|ERR", "GUID": "...", "Message": "...", "Data": "..." }
```

`GUID` 用於對應 Server 的請求與回覆。

---

## 3. 相關檔案索引
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/accessibility_service_config.xml`
- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/java/com/example/alipayaccdemo/MainActivity.kt`
- `app/src/main/java/com/example/alipayaccdemo/AlipayAccService.kt`
- `app/src/main/java/com/example/alipayaccdemo/RawWebSocketClient.kt`
- `app/src/main/java/com/example/alipayaccdemo/capture/ScreenCaptureService.kt`
- `app/src/main/java/com/example/alipayaccdemo/ScanAuth.kt`
- `app/src/main/java/com/example/alipayaccdemo/ProjectionStore.kt`

