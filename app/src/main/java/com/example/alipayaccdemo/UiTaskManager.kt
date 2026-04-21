package com.example.alipayaccdemo

class UiTaskManager(
    private val sendResult: (String, Boolean) -> Unit
) {
    private val tasks = mutableListOf<UiTask>()

    fun add(task: UiTask) {
        tasks += task
    }

    // ✅ 給“我已經知道它完成了”的任務用
    fun complete(requestId: String, success: Boolean) {
        val it = tasks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.requestId == requestId) {
                sendResult(requestId, success)
                it.remove()
                return
            }
        }
    }

    fun hasPendingTasks(): Boolean = tasks.isNotEmpty()

    fun tick(ctx: DetectionContext) {
        if (tasks.isEmpty()) return
        val now = System.currentTimeMillis()
        val it = tasks.iterator()
        while (it.hasNext()) {
            val t = it.next()

            if (now > t.deadline) {
                sendResult(t.requestId, false)
                it.remove()
                continue
            }

            // 只能被動偵測的才需要看 interval
            if (t.detector != null) {
                if (now - t.lastCheck < t.intervalMs) continue
                t.lastCheck = now
                val done = t.detector.invoke(ctx)
                if (done) {
                    sendResult(t.requestId, true)
                    it.remove()
                }
            }
        }
    }
}
