package com.example.alipayaccdemo

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.experimental.xor

/**
 * 純 Socket 的 WebSocket Client（RFC 6455）
 * - 支援 126/127 長度
 * - 預設主動分片傳送（避免超大單幀）
 * - 正確處理控制幀（Ping/Pong/Close）
 * - 嚴格檢查：server frame 不得帶 mask
 */
class RawWebSocketClient(
    private val host: String,
    private val port: Int,
    private val path: String = "/",
    private val connectTimeoutMs: Int = 5000,
    private val readBufferSize: Int = 64 * 1024,
    private val sendChunkSize: Int = 16 * 1024 // 建議 8~32KB
) {
    private val TAG = "RawWSClient"

    // I/O
    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var inputStream: InputStream? = null

    // 接收協程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recvJob: Job? = null

    // 送出互斥（避免多協程同時送出造成幀交錯）
    private val sendMutex = Mutex()

    // 碎片累積
    private val fragmentBuffer = ByteArrayOutputStream()
    private var initialOpCode: Int = 0 // 1: text, 2: binary

    // Callbacks
    var onMessageReceived: ((String) -> Unit)? = null
    var onBinaryReceived: ((ByteArray) -> Unit)? = null
    var onClosed: ((String) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    /** 建立 TCP 並完成 WebSocket 握手 */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            outputStream = BufferedOutputStream(s.getOutputStream(), 32 * 1024)
            inputStream = BufferedInputStream(s.getInputStream(), 32 * 1024)

            Log.d(TAG, "TCP 連線成功，開始握手")
            if (!performHandshake()) {
                Log.e(TAG, "WebSocket 握手失敗")
                closeInternal("handshake failed")
                return@withContext false
            }

            Log.i(TAG, "WebSocket 握手成功")
            recvJob = scope.launch { startReceiving() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "連線或握手失敗: ${e.message}", e)
            onError?.invoke(e)
            closeInternal("connect error")
            false
        }
    }

    /** 關閉連線（可重入） */
    @Synchronized
    fun close(reason: String = "client close") {
        closeInternal(reason)
    }

    @Synchronized
    private fun closeInternal(reason: String) {
        try { recvJob?.cancel() } catch (_: Exception) {}
        recvJob = null
        try { outputStream?.flush() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        fragmentBuffer.reset()
        initialOpCode = 0
        Log.d(TAG, "WebSocket 關閉：$reason")
        onClosed?.invoke(reason)
    }

    // -------------------------
    // 發送
    // -------------------------

    /** 發送文字（UTF-8）。自動分片。 */
    suspend fun sendText(message: String) {
        val payload = message.toByteArray(Charsets.UTF_8)
        sendWithFragmentation(payload, isText = true)
    }

    /** 發送二進位。自動分片。 */
    suspend fun sendBinary(data: ByteArray) {
        sendWithFragmentation(data, isText = false)
    }

    // -------------------------
    // 狀態檢查 (新增)
    // -------------------------

    /**
     * 檢查 WebSocket 連線是否處於活動狀態 (TCP 連線已建立且未關閉)
     * @return 如果 Socket 存在、已連線且未關閉，則為 true。
     */
    fun isConnected(): Boolean {
        // 檢查 socket 實例是否存在
        val currentSocket = socket ?: return false

        // 檢查 TCP 連線狀態 (已連線且未被主動關閉/斷開)
        return currentSocket.isConnected && !currentSocket.isClosed
    }


    private suspend fun sendWithFragmentation(payload: ByteArray, isText: Boolean) {
        // 控制幀不可分片；資料幀可以
        val total = payload.size
        if (total == 0) {
            val frame = buildMaskedDataFrame(ByteArray(0), if (isText) 0x1 else 0x2, fin = true)
            sendRawBytes(frame)
            return
        }

        var offset = 0
        var first = true
        while (offset < total) {
            val len = minOf(sendChunkSize, total - offset)
            val slice = payload.copyOfRange(offset, offset + len)
            val op = when {
                first && isText -> 0x1
                first && !isText -> 0x2
                else -> 0x0 // continuation
            }
            val fin = (offset + len) >= total
            val frame = buildMaskedDataFrame(slice, op, fin)
            sendRawBytes(frame)

            first = false
            offset += len
        }
    }

    private suspend fun sendRawBytes(data: ByteArray) = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            try {
                outputStream?.write(data)
                outputStream?.flush()
                Log.d(TAG, "已送出 frame：${data.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "送出錯誤：${e.message}", e)
                onError?.invoke(e)
                closeInternal("send error")
            }
        }
    }

    // 建構已遮罩的資料幀（client→server 必須 mask）
    private fun buildMaskedDataFrame(payload: ByteArray, opCode: Int, fin: Boolean): ByteArray {
        val payloadLength = payload.size
        val finBit = if (fin) 0x80 else 0x00
        val header1 = (finBit or opCode).toByte()

        val maskingKey = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val maskFlag: Byte = 0x80.toByte()

        val (lenByte, extLen) = when {
            payloadLength <= 125 -> Pair(payloadLength, ByteArray(0))
            payloadLength <= 0xFFFF -> {
                val b = ByteBuffer.allocate(2).putShort(payloadLength.toShort()).array()
                Pair(126, b)
            }
            else -> {
                val b = ByteBuffer.allocate(8).putLong(payloadLength.toLong()).array()
                Pair(127, b)
            }
        }

        // mask
        val masked = ByteArray(payloadLength)
        for (i in 0 until payloadLength) {
            masked[i] = (payload[i] xor maskingKey[i % 4])
        }

        val out = ByteArrayOutputStream(1 + 1 + extLen.size + 4 + payloadLength)
        out.write(byteArrayOf(header1))
        out.write(byteArrayOf((maskFlag.toInt() or lenByte).toByte()))
        if (extLen.isNotEmpty()) out.write(extLen)
        out.write(maskingKey)
        out.write(masked)
        return out.toByteArray()

    }

    private fun buildUnmaskedDataFrame(payload: ByteArray, opCode: Int, fin: Boolean): ByteArray {
        val payloadLength = payload.size
        val finBit = if (fin) 0x80 else 0x00
        val header1 = (finBit or opCode).toByte()

        val maskFlag: Byte = 0x00.toByte()  // 伺服器端必須為 0

        val (lenByte, extLen) = when {
            payloadLength <= 125 -> Pair(payloadLength, ByteArray(0))
            payloadLength <= 0xFFFF -> {
                val b = ByteBuffer.allocate(2).putShort(payloadLength.toShort()).array()
                Pair(126, b)
            }
            else -> {
                val b = ByteBuffer.allocate(8).putLong(payloadLength.toLong()).array()
                Pair(127, b)
            }
        }

        val out = ByteArrayOutputStream(1 + 1 + extLen.size + payloadLength)
        out.write(byteArrayOf(header1))
        out.write(byteArrayOf((maskFlag.toInt() or lenByte).toByte()))
        if (extLen.isNotEmpty()) out.write(extLen)
        out.write(payload) // 直接原始 payload，無 masking key
        return out.toByteArray()
    }

    // -------------------------
    // 握手
    // -------------------------

    private fun performHandshake(): Boolean {
        return try {
            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val secWebSocketKey = Base64.getEncoder().encodeToString(keyBytes)
            val hostHeader = if (port == 80 || port == 443) host else "$host:$port"

            val request = """GET $path HTTP/1.1
Host: $hostHeader
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: $secWebSocketKey
Sec-WebSocket-Version: 13

""".replace("\n", "\r\n")

            outputStream?.write(request.toByteArray(Charsets.US_ASCII))
            outputStream?.flush()

            val buf = ByteArray(4096)
            val n = inputStream?.read(buf) ?: -1
            if (n <= 0) return false
            val resp = String(buf, 0, n, Charsets.US_ASCII)

            if (!resp.startsWith("HTTP/1.1 101")) return false

            val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            val acceptExpected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                    (secWebSocketKey + guid).toByteArray(Charsets.US_ASCII)
                )
            )
            resp.contains("Sec-WebSocket-Accept: $acceptExpected")
        } catch (e: Exception) {
            Log.e(TAG, "握手異常: ${e.message}", e)
            onError?.invoke(e)
            false
        }
    }

    // -------------------------
    // 接收
    // -------------------------

    private suspend fun startReceiving() {
        val header = ByteArray(10) // 夠用來讀前兩個位元組 + ext length 區段
        try {
            val ins = inputStream ?: return
            while (true) {
                // 1) 讀第一個位元組
                if (readFully(header, 1, 0, ins) == -1) break
                val b1 = header[0].toInt() and 0xFF
                val fin = (b1 ushr 7) and 0x1
                var opCode = b1 and 0x0F

                // 2) 讀第二個位元組
                if (readFully(header, 1, 1, ins) == -1) break
                val b2 = header[1].toInt() and 0xFF
                val hasMask = (b2 ushr 7) and 0x1
                var payloadLen = b2 and 0x7F

                // Server 不應該 mask
                if (hasMask == 1) {
                    Log.e(TAG, "協議錯誤：server frame 帶 mask")
                    closeInternal("masked frame from server")
                    return
                }

                // 3) extended length
                val actualLen = when (payloadLen) {
                    126 -> {
                        if (readFully(header, 2, 2, ins) == -1) return
                        (ByteBuffer.wrap(header, 2, 2).short.toInt() and 0xFFFF)
                    }
                    127 -> {
                        if (readFully(header, 8, 2, ins) == -1) return
                        val l = ByteBuffer.wrap(header, 2, 8).long
                        if (l < 0L || l > Int.MAX_VALUE) {
                            Log.e(TAG, "超大 frame 長度不支援: $l")
                            closeInternal("frame too large")
                            return
                        }
                        l.toInt()
                    }
                    else -> payloadLen
                }

                // 4) 讀 payload
                if (actualLen < 0) {
                    closeInternal("invalid length")
                    return
                }
                val payload = if (actualLen == 0) ByteArray(0) else ByteArray(actualLen)
                if (actualLen > 0 && readFully(payload, actualLen, 0, ins) == -1) return

                // 5) 控制幀（8,9,10）不可分片
                if (opCode >= 0x8) {
                    processControlFrame(opCode, payload)
                    continue
                }

                // 6) 資料幀與延續幀
                if (opCode != 0x0) {
                    // 新的初始資料幀（text/binary）
                    if (fragmentBuffer.size() > 0) {
                        Log.e(TAG, "新的初始幀到來但前一碎片未結束")
                        closeInternal("fragmentation error")
                        return
                    }
                    initialOpCode = opCode
                    fragmentBuffer.write(payload)

                    if (fin == 1) {
                        processCompleteMessage(initialOpCode, fragmentBuffer.toByteArray())
                        fragmentBuffer.reset()
                        initialOpCode = 0
                    }
                } else {
                    // continuation
                    if (initialOpCode == 0) {
                        Log.e(TAG, "收到延續幀但沒有初始幀")
                        closeInternal("continuation without start")
                        return
                    }
                    fragmentBuffer.write(payload)
                    if (fin == 1) {
                        processCompleteMessage(initialOpCode, fragmentBuffer.toByteArray())
                        fragmentBuffer.reset()
                        initialOpCode = 0
                    }
                }
            }
        } catch (e: CancellationException) {
            // 正常結束
        } catch (e: Exception) {
            Log.e(TAG, "接收錯誤: ${e.message}", e)
            onError?.invoke(e)
        } finally {
            closeInternal("receive loop end")
        }
    }

    private fun processCompleteMessage(opCode: Int, data: ByteArray) {
        when (opCode) {
            0x1 -> { // text
                try {
                    onMessageReceived?.invoke(String(data, Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.e(TAG, "文字解碼失敗: ${e.message}", e)
                    onError?.invoke(e)
                }
            }
            0x2 -> onBinaryReceived?.invoke(data)
            else -> Log.w(TAG, "未知資料幀 opcode=$opCode")
        }
    }

    private suspend fun processControlFrame(opCode: Int, payload: ByteArray) {
        when (opCode) {
            0x8 -> { // Close
                var code = 1000
                var reason = ""
                if (payload.size >= 2) {
                    code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    if (payload.size > 2) {
                        reason = try {
                            String(payload, 2, payload.size - 2, Charsets.UTF_8)
                        } catch (_: Exception) { "" }
                    }
                }
                Log.w(TAG, "收到 Close 幀 code=$code reason=$reason，回送 Close 後關閉")
                // 回送同樣 payload 的 Close
                val reply = buildMaskedDataFrame(payload, 0x8, fin = true)
                sendRawBytes(reply)
                closeInternal("closed by server")
            }
            0x9 -> { // Ping → 必須回相同 payload 的 Pong
                val pongPayload = if (payload.size <= 125) payload else payload.copyOf(125)
                val pong = buildMaskedDataFrame(pongPayload, 0xA, fin = true)
                sendRawBytes(pong)
            }
            0xA -> {
                Log.d(TAG, "收到 Pong（len=${payload.size}）")
            }
            else -> Log.w(TAG, "未知控制幀 opcode=$opCode")
        }
    }

    // 保證讀滿 count bytes，回傳總讀取數；若 EOF 則回 -1
    private fun readFully(buf: ByteArray, count: Int, offset: Int, ins: InputStream): Int {
        var readTotal = 0
        while (readTotal < count) {
            val n = ins.read(buf, offset + readTotal, count - readTotal)
            if (n <= 0) return -1
            readTotal += n
        }
        return readTotal
    }
}
