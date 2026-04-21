package com.example.alipayaccdemo

import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI

object ScanAuth {
    @Volatile var wsUrl: URI? = null
        private set
    @Volatile var auth: String? = null
        private set

    // 簡單旗標供 UI 顯示用
    private val ready = AtomicBoolean(false)

    fun setAuthData(auth: String, wsUrlString: String) {
        this.auth = auth
        this.wsUrl = URI(wsUrlString)
        ready.set(true)
    }

    fun clear() {
        this.auth = null
        this.wsUrl = null
        ready.set(false)
    }

    fun isReady(): Boolean = ready.get()
}