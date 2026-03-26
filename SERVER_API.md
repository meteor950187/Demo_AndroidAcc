# Server API 文件（WebSocket）

## 目標
提供**Server 人員**使用，說明 App 與 Server 之間的 WebSocket API 格式與行為。

---

## 1. 連線與認證

### 1.1 連線
- 傳輸協定：WebSocket（可能為 `ws://` 明文）。
- 連線位址：由 QR Code 內的 `wsUrl` 提供。

### 1.2 認證
App 連線成功後會主動送出：
```json
{ "Result": "Auth", "Token": "<token>" }
```

---

## 2. 指令格式（Server -> App）
所有指令皆為 JSON，基本欄位：
- `Action`：主動作
- `Method`：子方法，可為空字串
- `GUID`：請求識別碼（App 回傳必帶，Server 需比對是否同一個 GUID）

### 2.1 開啟 Url Schema
指令：
```json
{ "Action": "Open", "Method": "", "Url": "alipays://platformapi/startapp?appId=20000123", "GUID": "12345" }
```
行為：App 透過 Intent 開啟指定 schema。

回覆：
```json
{ "Result": "OK", "GUID": "12345", "Message": null }
```

---

### 2.2 取得當前節點樹（無障礙）
指令：
```json
{ "Action": "GetCurrentText", "Method": "", "GUID": "12346" }
```
行為：App 讀取 `rootInActiveWindow`，序列化節點樹後回傳。

回覆：
```json
{ "Result": "OK", "GUID": "12346", "Message": null, "Data": "<node-tree-json>" }
```

---

### 2.3 擷取螢幕
指令：
```json
{ "Action": "Capture", "Method": "", "GUID": "12347" }
```
行為：App 透過 MediaProjection 擷取螢幕，Base64 回傳。

回覆：
```json
{ "Result": "OK", "GUID": "12347", "Message": null, "Data": "<base64-image>" }
```

---

### 2.4 等待特定 ViewId 出現
指令：
```json
{ "Action": "WaitViewId", "Method": "", "ViewId": "com.alipay.mobile.id", "WaitSeconds": 20, "GUID": "12348" }
```
行為：
- App 持續監控無障礙節點樹，直到找到指定 `viewIdResourceName`。
- 找到即回傳節點序列化資料。
- 超時或失敗回傳 ERR。

成功回覆：
```json
{ "Result": "OK", "GUID": "12348", "Message": "", "Data": "<node-json>" }
```

失敗回覆：
```json
{ "Result": "ERR", "GUID": "12348", "Message": "timeout" }
```

---

## 3. App 回覆格式（App -> Server）
所有回覆格式一致：
```json
{ "Result": "OK|ERR", "GUID": "<same-guid>", "Message": "<string-or-null>", "Data": "<string-or-null>" }
```

---

## 4. 重要注意事項
- `GUID` 為 Server 請求對應的識別碼，App 回覆必帶，Server 需比對是否為同一個 GUID。
- `Method` 可能為空字串，依指令需要而定。
- `Data` 可能為大字串（節點樹 JSON 或 Base64 圖片）。
- 若連線中斷，App 會嘗試重連。

