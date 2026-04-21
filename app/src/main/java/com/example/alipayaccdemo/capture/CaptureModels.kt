package com.example.alipayaccdemo.capture

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// ── 呼叫端傳進來的請求（只有這一個物件） ─────────────────────────
@Parcelize
data class CaptureRequest(
    val requestId: String = System.currentTimeMillis().toString(),
    val encoding: Encoding = Encoding.JPEG(quality = 85),
    val includeMeta: Boolean = true,
    val returnChannel: ReturnChannel = ReturnChannel.Broadcast(action = DEFAULT_BROADCAST_ACTION)
) : Parcelable {
    companion object {
        const val DEFAULT_BROADCAST_ACTION = "com.example.alipayaccdemo.CAPTURE_DONE"
    }

    // 壓縮策略
    sealed class Encoding : Parcelable {
        @Parcelize
        data class JPEG(val quality: Int = 85) : Encoding()
        @Parcelize
        object PNG : Encoding()
    }

    // 回傳通道（之後要換 ResultReceiver/Messenger 也不用改呼叫端）
    sealed class ReturnChannel : Parcelable {
        @Parcelize
        data class Broadcast(val action: String) : ReturnChannel()
        // 需要時可擴充：
        // @Parcelize data class ResultReceiver(val receiver: android.os.ResultReceiver) : ReturnChannel()
        // @Parcelize data class PendingIntent(val pendingIntent: android.app.PendingIntent) : ReturnChannel()
    }
}

// ── 服務端回給呼叫端的結果（也只有這一個物件） ───────────────────────
@Parcelize
data class CaptureResult(
    val requestId: String,
    val base64: String,
    val mime: String,      // image/jpeg or image/png
    val width: Int,
    val height: Int,
    val bytes: Int         // 壓縮後的位元組大小
) : Parcelable
