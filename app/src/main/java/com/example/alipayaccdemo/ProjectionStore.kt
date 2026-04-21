package com.example.alipayaccdemo

import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean

object ProjectionStore {
    @Volatile var resultCode: Int? = null
        private set
    @Volatile var data: Intent? = null
        private set

    // 簡單旗標供 UI 顯示用
    private val ready = AtomicBoolean(false)

    fun setConsent(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.data = data
        ready.set(true)
    }

    fun clear() {
        resultCode = null
        data = null
        ready.set(false)
    }

    fun isReady(): Boolean = ready.get()
}