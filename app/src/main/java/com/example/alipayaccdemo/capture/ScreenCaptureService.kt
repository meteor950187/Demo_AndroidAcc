// app/src/main/java/com/example/alipayaccdemo/capture/ScreenCaptureService.kt
package com.example.alipayaccdemo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.alipayaccdemo.MediaProjectionHolder
import com.example.alipayaccdemo.ProjectionStore
import com.example.alipayaccdemo.R
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * VD 常駐版：
 * - MediaProjection / VirtualDisplay / ImageReader 只建立一次並常駐
 * - 每次收到請求只標記 pendingOneShot，回調取下一張影像回傳
 * - 不在同一個 MediaProjection 上重複 createVirtualDisplay()，避免 Android 14+ SecurityException
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"

        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTI_ID = 1001

        // 對外介面
        const val ACTION_HANDLE_REQUEST = "com.example.alipayaccdemo.capture.HANDLE_REQUEST"
        const val EXTRA_CAPTURE_REQUEST = "extra_capture_request"
        const val EXTRA_CAPTURE_RESULT = "extra_capture_result"
    }

    // ---- 常駐資源 ----
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    // 一次性擷取控制
    @Volatile private var pendingOneShot: Boolean = false

    // 保存最近一次請求的資訊，供回調使用
    private var lastRequestId: String = ""
    private var lastEncoding: CaptureRequest.Encoding = CaptureRequest.Encoding.PNG
    private var lastBroadcastAction: String = CaptureRequest.DEFAULT_BROADCAST_ACTION

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTI_ID, buildNoti("螢幕擷取服務運行中"))

        worker = HandlerThread("ScreenCaptureWorker").apply { start() }
        handler = Handler(worker!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANDLE_REQUEST) {
            handleRequest(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 只在服務結束時釋放全部資源
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.let { MediaProjectionHolder.release() }; projection = null

        worker?.quitSafely(); worker = null
        handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 核心流程 ----

    private fun handleRequest(intent: Intent) {
        if (!ProjectionStore.isReady()) {
            Log.w(TAG, "尚未取得 MediaProjection 授權")
            return
        }

        val req = intent.getParcelableExtra<CaptureRequest>(EXTRA_CAPTURE_REQUEST)
        if (req == null) {
            Log.w(TAG, "沒有 CaptureRequest")
            return
        }

        // 紀錄此次請求參數
        lastRequestId = req.requestId
        lastEncoding = req.encoding
        lastBroadcastAction = (req.returnChannel as? CaptureRequest.ReturnChannel.Broadcast)?.action
            ?: CaptureRequest.DEFAULT_BROADCAST_ACTION

        // 確保 Projection 與 VD/Reader 已建立（若未建立才建，一次而已）
        val dm = resources.displayMetrics
        ensureProjectionAndDisplay(dm.widthPixels, dm.heightPixels, dm.densityDpi)

        // 標記下一張要取回
        pendingOneShot = true
        updateNoti("擷取待命（常駐 VD）")
    }

    private fun ensureProjectionAndDisplay(width: Int, height: Int, density: Int) {
        if (projection == null) {
            projection = MediaProjectionHolder.acquire(applicationContext)
        }
        if (virtualDisplay != null && imageReader != null) {
            return // 已經就緒，直接使用
        }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, /*maxImages*/3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!pendingOneShot) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                pendingOneShot = false

                val bmp = imageToBitmap(image)
                val (mime, data) = when (val enc = lastEncoding) {
                    is CaptureRequest.Encoding.JPEG -> "image/jpeg" to bitmapToBytes(bmp, Bitmap.CompressFormat.JPEG, enc.quality)
                    is CaptureRequest.Encoding.PNG  -> "image/png"  to bitmapToBytes(bmp, Bitmap.CompressFormat.PNG, 100)
                    else                            -> "image/png"  to bitmapToBytes(bmp, Bitmap.CompressFormat.PNG, 100)
                }
                val b64 = Base64.encodeToString(data, Base64.NO_WRAP)

                val result = CaptureResult(
                    requestId = lastRequestId,
                    width = bmp.width,
                    height = bmp.height,
                    mime = mime,
                    bytes = data.size,
                    base64 = b64
                )

                // 廣播回去，鍵使用 EXTRA_CAPTURE_RESULT（你的接收端已採用）
                val intent = Intent(lastBroadcastAction)
                     .setPackage(packageName) // 👈 限定本 app，提升命中率
                     .putExtra(ScreenCaptureService.EXTRA_CAPTURE_RESULT, result)
                sendBroadcast(intent)
                updateNoti("擷取完成（常駐 VD）")
            } catch (e: Exception) {
                Log.e(TAG, "擷取處理錯誤", e)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "PointScanCaptureVD",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            imageReader?.surface,
            null, null
        )

        updateNoti("擷取啟動（常駐 VD）${width}x${height}")
    }

    // 可選：若你要調整解析度/旋轉，請使用這個方法而非重建 VD
    private fun resizeDisplay(newW: Int, newH: Int, newDpi: Int) {
        if (virtualDisplay == null || projection == null) return

        // 重建 ImageReader（尺寸變了），但不重建 MediaProjection / VD
        imageReader?.close()
        imageReader = ImageReader.newInstance(newW, newH, PixelFormat.RGBA_8888, 3)
        virtualDisplay?.resize(newW, newH, newDpi)
        virtualDisplay?.setSurface(imageReader?.surface)
        updateNoti("擷取調整尺寸 ${newW}x${newH}")
    }

    // ---- 影像工具 ----

    private fun imageToBitmap(image: Image): Bitmap {
        if (image.format != ImageFormat.UNKNOWN && image.planes.isNotEmpty()) {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val tmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            val buffer: ByteBuffer = plane.buffer
            tmp.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(tmp, 0, 0, width, height)
        } else {
            throw IllegalStateException("Unsupported Image format or empty planes")
        }
    }

    private fun bitmapToBytes(bmp: Bitmap, fmt: Bitmap.CompressFormat, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(fmt, quality.coerceIn(0, 100), bos)
        return bos.toByteArray()
    }

    // ---- 通知 ----

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNoti(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PointScan")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNoti(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, buildNoti(text))
    }
}
