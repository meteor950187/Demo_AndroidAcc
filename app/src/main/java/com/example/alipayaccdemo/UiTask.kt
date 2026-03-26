package com.example.alipayaccdemo

data class UiTask(
    val requestId: String,
    val type: String,
    val deadline: Long,
    val intervalMs: Long = 300,
    var lastCheck: Long = 0L,
    // ✅ 可以是 null，表示這個任務不是靠輪詢完成的
    val detector: ((DetectionContext) -> Boolean)? = null
)
