package com.example.alipayaccdemo

import okio.ByteString.Companion.encodeUtf8
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.Button
import com.example.alipayaccdemo.capture.ScreenCaptureService
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.Button
import android.os.Handler
import android.os.Looper
import java.util.Collections

class AlipayAccService : AccessibilityService() {

    companion object {
        private const val TAG = "AlipayAccService"

        const val ACTION_QR_READY = "com.example.alipayaccdemo.QR_READY"

        const val ACTION_CAPTURE = "com.example.alipayaccdemo.capture.CaptureRequest.DEFAULT_BROADCAST_ACTION"
        const val EXTRA_QR_TEXT = "qr_text"

        private const val WS_URL = "192.168.1.65"

        private const val WS_URL_PORT = 10864

        private const val WS_URL_PATH = "/AliPay"
        private const val MAX_BACKOFF = 20_000L
    }


    // ① service 的 coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 目前正在執行的「連線協程」
    private var connectJob: Job? = null

    // 目前已排程的「重連協程」（裡面主要是在 delay）
    private var reconnectJob: Job? = null

    // 專門用於管理 WS 請求觸發的業務邏輯 (如 WaitViewId 的等待)
    @Volatile private var requestJob = Job()
    private val requestScope = CoroutineScope(requestJob + Dispatchers.IO) // 基於這個 Job 創建 Scope

    // ② 給 DetectionContext 用的狀態
    @Volatile private var latestRoot: AccessibilityNodeInfo? = null

    // ③ 任務管理器
    private lateinit var taskManager: UiTaskManager

    // ④ 控制 scheduler 有沒有在跑
    @Volatile private var schedulerRunning = false

    private var client: RawWebSocketClient? = null
    private var reconnectAttempts = 0

    private var wm: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val wsEventHandler = object {
        @Volatile  var action: String? = null
            private set
        @Volatile  var method: String? = null
            private set
        @Volatile  var eventID: String? = null
            private set

        // 簡單旗標供 UI 顯示用
        private val subscriptions = Collections.synchronizedSet(mutableSetOf<String>())

        fun setEventData(action: String, method: String, eventID: String) {
            this.action = action
            this.method = method
            this.eventID = eventID

        }

        fun clear() {
            action = null
            method = null
            eventID = null

        }

        // 🔥 新增：訂閱類型（例如 "PushNotification"）
        fun subscribe(type: String) {
            subscriptions.add(type)
            Log.d("WS_EVENT", "Subscribed: $type")
        }

        // 🔥 新增：取消訂閱
        fun unsubscribe(type: String) {
            subscriptions.remove(type)
            Log.d("WS_EVENT", "Unsubscribed: $type")
        }

        // 🔥 新增：查詢是否訂閱某類事件
        fun isSubscribed(type: String): Boolean {
            return subscriptions.contains(type)
        }

        // 🔥 新增：由 service 觸發主動推送邏輯
        fun triggerEvent(type: String, payload: String, sendToServer: (String) -> Unit) {
            if (isSubscribed(type)) {
                Log.d("WS_EVENT", "Trigger event for subscription: $type")

                // 這裡可自訂格式
                val json = buildJsonObject {
                    put("Action", "Notify")
                    put("Type", type)
                    put("Payload", payload)
                }.toString()

                sendToServer(json)
            }
        }
    }


    private val qrReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_QR_READY) {
                val text = intent.getStringExtra(EXTRA_QR_TEXT) ?: "未辨識到 QRCode"
                showOverlay(text)
            }
        }
    }

    //截圖的接收
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CAPTURE) return
            val result = intent.getParcelableExtra<com.example.alipayaccdemo.capture.CaptureResult>(
                ScreenCaptureService.EXTRA_CAPTURE_RESULT
            ) ?: return

            Log.i("AlipayAccService", "Capture OK id=${result.requestId} ${result.width}x${result.height} ${result.mime} bytes=${result.bytes}, b64Len=${result.base64.length}")

            // 丟給 WS（你原本的 sendToServer）
            val ret = buildJsonObject {
                put("Result", "OK")
                put("GUID", result.requestId)
                put("Message", null)
                put("Data", result.base64)
            }


            Log.i("test", result.base64)

            sendToServer(ret, result.requestId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AccessibilityService 創建 (onCreate)")

        // I. 系統資源與管理器 (只需要獲取一次)
        wm = getSystemService(WindowManager::class.java)

        // II. 廣播接收器註冊 (只需要註冊一次)
        registerReceiverCompat(qrReadyReceiver, ACTION_QR_READY)
        registerReceiverCompat(captureReceiver, ACTION_CAPTURE)

        // [新] 創建 WebSocket 客戶端實例 (只需要創建一次)
        // 假設 createWebSocket() 是初始化 client 實例的函數
        //createWebSocket()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "無障礙服務已啟用 (onServiceConnected)")

        // I. 服務配置 (必須在 onServiceConnected 或之後設置)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        this.serviceInfo = serviceInfo // 應用配置

        // III. WebSocket 連線與業務啟動 (每次服務啟動時嘗試連線)
        attemptConnect()
        showOverlay("服務啟動")
        // [保留註釋] 這些是業務啟動邏輯
        //openUrlSchema("alipays://platformapi/startapp?appId=20000123")
        //openUrlSchema("alipays://platformapi/startapp?appId=20000003")
        //openUrlSchema("alipays://platformapi/startapp?appId=20000004")
        //showOverlay("alipaytest")
        //startCaptureIfReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        //dumpAllTexts()
        if (event == null) return

        val eventType = event.eventType
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // 畫面可能有變化 → 更新 root 快照
                latestRoot = rootInActiveWindow
                //handlePendingViewDetection(latestRoot)
                // 內部任務管理器會在 scheduler loop 裡使用這個 root

//                val serializer = AccNodeSerializer()
//                val snapshot = serializer.serialize(latestRoot)
//                var snapshotJson = ""
//
//                if (snapshot != null) {
//                    snapshotJson = Json {
//                        prettyPrint = false
//                        ignoreUnknownKeys = true
//                    }.encodeToString(
//                        AccNodeSerializer.NodeSnapshot.serializer(),
//                        snapshot
//                    )
//
//                    Log.d("FULL_TREE", snapshotJson)
//
//                    val obj = buildJsonObject {
//                        put("Result", "OK")
//                        put("Message", null)
//                        put("Data", snapshotJson)
//                    }
//                    val jsonStr = json.encodeToString(JsonObject.serializer(), obj)
//                   // val bs = jsonStr.encodeUtf8()
//
//                    Log.d("TEST", jsonStr)
//                    //Log.i(TAG, "size=${jsonStr.toByteArray(Charsets.UTF_8).size} bytes}")
//                }

            }
            else -> {
                // 其他事件你可以忽略或者做特殊處理
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "無障礙服務被打斷 (onInterrupt)")
        try {
            // 取消所有協程：停止所有網絡 I/O 和後台任務
            serviceScope.cancel()

            // 關閉 WebSocket 連線
            client?.close("Service Interrupted")

        } catch (e: Exception) {
            // 捕獲並記錄異常，但仍繼續清理
            Log.e(TAG, "onInterrupt 清理異常", e)
        }

        // 移除浮層 (通常是必須的清理，因為服務被打斷可能影響顯示)
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "無障礙服務已銷毀 (onDestroy)")

        // 1. 執行核心清理邏輯
        try {
            // 取消所有協程 (必須在關閉客戶端之前，讓 send/recv loop 停止)
            serviceScope.cancel()

            // 關閉 WebSocket 連線 (會關閉底層 Socket)
            client?.close("Service Destroyed")

            // 移除浮層
            removeOverlay()

        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 核心清理異常", e)
        }

        // 2. 解除系統資源註冊 (建議放在 try 塊外，確保即使核心清理失敗，也能解除註冊)
        try {
            unregisterReceiversCompat(qrReadyReceiver)
            unregisterReceiversCompat(captureReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 解除廣播註冊異常", e)
        }
    }

    private fun showOverlay(qrText: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                showOverlay(qrText)
            }
            return
        }

        // --- 以下保持你原本的 UI 內容 ---
        removeOverlay()

        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC222222"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
            elevation = dp(6).toFloat()
        }

        val tv = TextView(ctx).apply {
            text = "內容：$qrText"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        container.addView(tv)

//        val button = Button(ctx).apply {
//            text = "開啟支付寶"
//            setTextColor(Color.BLACK)
//            setBackgroundColor(Color.WHITE)
//            setOnClickListener {
//                client?.close()
//            }
//        }
//        container.addView(button)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        wm?.addView(container, lp)
        overlayView = container
    }


    private fun removeOverlay() {
        overlayView?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v + 0.5f).toInt()

    private fun dumpAllTexts() {
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        fun dfs(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = (node.text ?: node.contentDescription)?.toString()
            if (!text.isNullOrBlank()) sb.appendLine(text)
            for (i in 0 until node.childCount) dfs(node.getChild(i))
        }
        dfs(root)
        Log.i(TAG, "畫面文字：\n$sb")
    }

    //region [互動功能發起]
    private fun openUrlSchema(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未安裝或無法打開對應頁面", Toast.LENGTH_LONG).show()
        }
    }

    private fun isOpenAlipayTargetLoaded(){
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        fun dfs(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = (node.text ?: node.contentDescription)?.toString()
            if (!text.isNullOrBlank()) sb.appendLine(text)
            for (i in 0 until node.childCount) dfs(node.getChild(i))
        }
        dfs(root)
        Log.i(TAG, "畫面文字：\n$sb")


    }

    //發起截圖請求
    private fun startCaptureIfReady(eventID: String) {
        val data = ProjectionStore.data
        val code = ProjectionStore.resultCode
        if (data == null || code != android.app.Activity.RESULT_OK) {
            Log.w(TAG, "尚未取得 MediaProjection 授權")
            return
        }

        val req = com.example.alipayaccdemo.capture.CaptureRequest(
            requestId = eventID,
            encoding = com.example.alipayaccdemo.capture.CaptureRequest.Encoding.JPEG(quality = 85),
            returnChannel = com.example.alipayaccdemo.capture.CaptureRequest.ReturnChannel.Broadcast(
               action = ACTION_CAPTURE
            )
        )

        val svc = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_HANDLE_REQUEST
            putExtra(ScreenCaptureService.EXTRA_CAPTURE_REQUEST, req)
            // ✅ 不再需要：
            //putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, code)
            // putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    //endregion

    // region [Receiver註冊]

    // --- Receiver 註冊/解除：整合 API 33+ 與舊版 ---
    private fun registerReceiverCompat(
        receiver: BroadcastReceiver,
        vararg actions: String
    ) {
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceiversCompat(vararg receivers: BroadcastReceiver?) {
        receivers.forEach { r ->
            try { if (r != null) unregisterReceiver(r) } catch (_: Exception) {}
        }
    }



    //endregion

    //region [WebSocket相關]

    private fun createWebSocket() : RawWebSocketClient{
        val newClient = RawWebSocketClient(ScanAuth.wsUrl?.host!!, ScanAuth.wsUrl?.port!!, ScanAuth.wsUrl?.path!!)
        newClient.onMessageReceived = { message ->
            // 注意：這個回調在 IO 線程中執行
            Log.d(TAG, "WS Msg: $message")
            // 如果需要更新 UI 或其他主線程操作，必須切換 Dispatcher:
            // serviceScope.launch(Dispatchers.Main) { /* ... UI update ... */ }

            runCatching {
                json.parseToJsonElement(message)
            }.onSuccess { element ->
                if (element is JsonObject) {
                    doServerMethod(element)
                } else {
                   showOverlay(message)
                }
            }.onFailure {
                Log.e(TAG, "WS parse error: ${it.message}", it)
            }
        }

        newClient.onBinaryReceived = { data ->
            Log.d(TAG, "WS Binary Received: ${data.size} bytes")
        }

        newClient.onClosed = { reason ->
            Log.w(TAG, "WS Closed: $reason")
            showOverlay("連線關閉")
            if(reason != "New attempt cleanup")
                scheduleReconnect()
            // 可以在這裡處理重連邏輯
        }

        newClient.onError = { error ->
            showOverlay("連線錯誤")
            Log.e(TAG, "WS Error", error)
        }

        return newClient
    }

    private fun attemptConnect() {
        // 檢查是否已在連線中或正在嘗試連線，避免重複啟動
        if (client?.isConnected() == true) {
            Log.w(TAG, "已連線，跳過連線嘗試。")
            return
        }

        // 如果已經有一個連線協程在跑，就不要再啟動新的
        if (connectJob?.isActive == true) {
            Log.w(TAG, "已有連線協程執行中，跳過 attemptConnect。")
            return
        }

        // 有人手動叫 attemptConnect 時，可以順便把排程中的重連取消掉
        reconnectJob?.cancel()
        reconnectJob = null

        // 在服務的 Scope 中啟動連線任務
        connectJob = serviceScope.launch {
            Log.i(TAG, "嘗試連線... (嘗試次數: $reconnectAttempts)")

            // *** 核心修改部分：清理舊的，創建新的 ***
            // 1. 確保舊 client 資源被釋放
            client?.close("New attempt cleanup")

            // 2. 創建新的客戶端實例並更新全局引用
            val newClient = createWebSocket()
            client = newClient
            showOverlay("建立新連線")
            try {
                // 3. 阻塞等待連線與握手完成 (在 Dispatchers.IO 內執行)
                val success = newClient.connect()

                if (success) {
                    // 🟢 連線成功 (OnOpen邏輯)
                    Log.i(TAG, "WS open，重置重連計數。")
                    connectJob = null
                    reconnectAttempts = 0 // 重置重連計數

                    showOverlay("連線成功")
                    // 4. 發送 Auth 訊息
                    val hello = buildJsonObject {
                        put("Result", "Auth")
                        put("Token", ScanAuth.auth)
                    }
                    // 發送數據，因為 client.sendText 也是 suspend 函數，我們直接在這裡調用
                    newClient.sendText(json.encodeToString(JsonObject.serializer(), hello))

                } else {
                    // 🔴 連線失敗 (TCP/握手層面)
                    Log.e(TAG, "WebSocket 連線失敗 (原因: TCP或握手)。")
                    // 啟動重連，因為失敗是連線嘗試本身的結果
                    connectJob = null
                    scheduleReconnect()
                }
            } catch (e: CancellationException) {
                // 協程被取消，通常是服務銷毀，不需要重連
                Log.d(TAG, "連線嘗試被取消。")
            } catch (e: Exception) {
                // 🔴 其他 I/O 異常 (例如 DNS 錯誤)
                Log.e(TAG, "連線協程異常", e)
                connectJob = null
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        // 檢查是否還有活動的 Scope
        if (!serviceScope.isActive) return

        // 已連線就不排重連
        if (client?.isConnected() == true) {
            Log.d(TAG, "已連線，略過重連排程。")
            return
        }

        // 如果已經有一個重連排程在跑，就不要再排一次
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "已有重連排程，略過。")
            return
        }

        // 計算回退延遲時間 (Exponential Backoff)
        // 1 shl N 等於 2 的 N 次方
        val delayMs = minOf(1000L * (1 shl reconnectAttempts), MAX_BACKOFF)

        // 更新重連計數器，最高為 6 次 (2^6 = 64秒)
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(6)

        reconnectJob  = serviceScope.launch {
            Log.i(TAG, "等待 $delayMs 毫秒後重連 (嘗試次數: $reconnectAttempts)。")

            // 延遲是非阻塞的 suspend 呼叫
            delay(delayMs)

            reconnectJob = null

            // 繼續嘗試連線
            attemptConnect()
        }
    }

    private fun sendToServer(obj: JsonObject, guid:String) {
        if (wsEventHandler.eventID == guid){
            val jsonStr = json.encodeToString(JsonObject.serializer(), obj)
            Log.i(TAG, "sendMsg=${jsonStr}")
            //webSocket?.send(jsonStr)
            serviceScope.launch {
                try {
                    // 協程在這裡會暫停，並在 I/O 線程中執行寫入操作
                    client?.sendText(jsonStr)
                    Log.d(TAG, "消息發送成功並完成寫入。")
                } catch (e: Exception) {
                    Log.e(TAG, "消息發送失敗: ${e.message}")
                    // 處理發送失敗可能導致的連線關閉
                }
            }

            wsEventHandler.clear()
        }
    }


    //endregion

    //region [ws的接收處理]

    private fun doServerMethod(jobj:JsonObject){
        val action = jobj["Action"]?.jsonPrimitive?.content
        val method = jobj["Method"]?.jsonPrimitive?.content
        val guid = jobj["GUID"]?.jsonPrimitive?.content
        wsEventHandler.setEventData(action!!, method!!, guid!!)

        when(action){
            "Open" -> {
                when(method){
                    "UrlSchema" -> {
                        val url = jobj["Url"]?.jsonPrimitive?.content
                        url?.let {
                            openUrlSchema(it)
                        }
//                        val task = UiTask(
//                            requestId = reqID,
//                            type = "open_alipay",
//                            deadline = System.currentTimeMillis() + 8_000,
//                            detector = { ctx -> isOpenAlipayTargetLoaded(ctx.root) }
//                        )
//                        taskManager.add(task)
//                        startSchedulerIfNeeded()   // 👈 這裡呼叫
                        val ret = buildJsonObject {
                            put("Result", "OK")
                            put("GUID", guid)
                            put("Message", null)
                        }

                        sendToServer(ret, guid)
                    }
                }
            }

            "GetCurrentText" -> {
                val serializer = AccNodeSerializer()
                val snapshot = serializer.serialize(latestRoot)
                var snapshotJson = ""

                if (snapshot != null) {
                    snapshotJson = Json {
                        prettyPrint = false
                        ignoreUnknownKeys = true
                    }.encodeToString(
                        AccNodeSerializer.NodeSnapshot.serializer(),
                        snapshot
                    )

                    Log.d("FULL_TREE", snapshotJson)
                }

                val ret = buildJsonObject {
                    put("Result", "OK")
                    put("GUID", guid)
                    put("Message", null)
                    put("Data", snapshotJson)
                }

                sendToServer(ret, guid)
            }

            "Capture" -> {
                startCaptureIfReady(guid)
            }

            "WaitViewId" -> {
                val viewID = jobj["ViewId"]?.jsonPrimitive?.content

                if (viewID != null) {
                    //取消初次搜尋的處理，全部交給定時檢查
                    val waitSeconds: Int = jobj["WaitSeconds"]?.jsonPrimitive?.intOrNull ?: 0


                    Log.d("WAIT_FOR_VIEW", "Node with ID '$viewID' not found. Starting ${waitSeconds}s wait.")

                    // 啟動一個新的 Coroutine 進行非阻塞等待
                    requestScope.launch {
                        try {

                            withTimeout(waitSeconds * 1000L) {
                                // ----------------------------------------------------
                                // 1. 定時檢查循環邏輯 (在單一協程內)
                                // ----------------------------------------------------
                                while (isActive) { // 只要協程沒有被取消就繼續循環
                                    val snapshotJson = handlePendingViewDetection(latestRoot, viewID)


                                    if (snapshotJson != ""){
                                        val ret = buildJsonObject {
                                            put("Result", "OK")
                                            put("GUID", guid)
                                            put("Message", "")
                                            put("Data",snapshotJson)
                                        }
                                        sendToServer(ret, guid)

                                        coroutineContext.cancel()
                                    }

                                    // 延遲指定間隔 (2 秒)
                                    delay(2 * 1000L)
                                }
                            }
                        }catch (e: TimeoutCancellationException) {
                            // ----------------------------------------------------
                            // 2. 超時處理 (Timeout)
                            // ----------------------------------------------------
                            // 循環執行了 20 秒仍未成功，withTimeout 拋出此異常
                            Log.e("TIMEOUT_ERROR", "Wait timed out for View ID: $viewID And eventID: $guid. Reporting failure.")

                            // 報告超時錯誤給伺服器
                            val timeoutRet = buildJsonObject {
                                put("Result", "ERR")
                                put("GUID", guid)
                                put("Message", "")
                            }

                            sendToServer(timeoutRet, guid)


                        } catch (e: CancellationException) {
                            // ----------------------------------------------------
                            // 4. 服務取消處理
                            // ----------------------------------------------------
                            // 外部服務 Scope 被取消 (例如 serviceScope.cancel() 被呼叫)
                            Log.d("WAIT_CANCELED", "Wait for guid $guid was cancelled: ${e.message}")

                            // 報告超時錯誤給伺服器
                            val errRet = buildJsonObject {
                                put("Result", "ERR")
                                put("GUID", guid)
                                put("Message", e.message)
                            }

                            sendToServer(errRet, guid)
                        }
                    }
                }
                else{
                    val ret = buildJsonObject {
                        put("Result", "ERR")
                        put("GUID", guid)
                        put("Message", "ViewID Is Empty")
                    }

                    sendToServer(ret, guid)
                }
            }
        }

    }
    //endregion

    //region [ws的異步回傳處理]

    private fun handlePendingViewDetection(root: AccessibilityNodeInfo?, viewID:String) : String {
        if (root == null )
            return ""

        // 在 IO Coroutine 中執行尋找和發送，避免阻塞 onAccessibilityEvent 的線程
        val targetNode = findNodeByViewId(root, viewID)

        if (targetNode != null) {
            // 找到目標 View ID！

            // 立即清除標記，通知等待超時的 Coroutine 流程它不需要報錯了


            Log.i(TAG, "View ID '$viewID' detected by event listener.")

            // 序列化和發送邏輯 (與 doServerMethod 中成功找到的邏輯相同)
            val serializer = AccNodeSerializer()
            val snapshot = serializer.serialize(targetNode)
            var snapshotJson = ""

            if (snapshot != null) {
                snapshotJson = Json {
                    prettyPrint = false
                    ignoreUnknownKeys = true
                }.encodeToString(
                    AccNodeSerializer.NodeSnapshot.serializer(),
                    snapshot
                )
            }

            return snapshotJson
        }else{
            return  ""
        }
    }
    //endregion


    fun findNodeByViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (root == null) return null

        // 檢查當前節點
        if (root.viewIdResourceName == viewId) {
            return root
        }

        // 遞迴檢查子節點 (深度優先搜尋)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val foundNode = findNodeByViewId(child, viewId)
            if (foundNode != null) {
                return foundNode
            }
        }

        // 如果沒有找到，並且沒有子節點，則返回 null
        return null
    }
}
