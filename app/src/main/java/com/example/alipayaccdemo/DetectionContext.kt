package com.example.alipayaccdemo


import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonObject

data class DetectionContext(
    val root: AccessibilityNodeInfo?,
    val screenCaptureStarted: Boolean = false,
    val lastCapturePath: String? = null,
    val lastWsPayload: JsonObject? = null,
    // 以後你想到別的狀態再往這裡加
)