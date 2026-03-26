// app/src/main/java/com/example/alipayaccdemo/capture/MediaProjectionHolder.kt
package com.example.alipayaccdemo

import android.content.Context
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 集中管理 MediaProjection 單例。
 * - getOrCreate(): 需要時建立；之後皆回傳同一實例
 * - acquire()/release(): 以參考計數保證最後一個使用者釋放時才 stop()
 */
object MediaProjectionHolder {
    @Volatile private var projection: MediaProjection? = null
    private val refCount = AtomicInteger(0)

    @Synchronized
    fun getOrCreate(context: Context): MediaProjection {
        val existing = projection
        if (existing != null) {
            return existing
        }

        val resultCode = ProjectionStore.resultCode
            ?: throw IllegalStateException("MediaProjection consent missing: call Activity to request permission first.")
        val data = ProjectionStore.data
            ?: throw IllegalStateException("MediaProjection consent missing Intent data.")

        val mgr = context.getSystemService(MediaProjectionManager::class.java)
            ?: throw IllegalStateException("MediaProjectionManager not available.")

        val created = mgr.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("getMediaProjection() returned null.")

        projection = created

        // 當系統撤銷權限或使用者中止時，會觸發 stop callback，順便清理單例
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(this@MediaProjectionHolder) {
                    projection = null
                    refCount.set(0)
                }
            }
        }, null)

        return created
    }

    /** 取得實例並+1，習慣用法：val p = acquire(context); try { ... } finally { release() } */
    fun acquire(context: Context): MediaProjection {
        val p = getOrCreate(context)
        refCount.incrementAndGet()
        return p
    }

    /** 使用完畢 -1；當降到 0 時主動 stop() */
    @Synchronized
    fun release() {
        if (refCount.decrementAndGet() <= 0) {
            projection?.stop()
            projection = null
            refCount.set(0)
        }
    }

    /** 清掉目前單例但不動 consent（例如你要重建 VirtualDisplay） */
    @Synchronized
    fun resetKeepConsent() {
        projection?.stop()
        projection = null
        refCount.set(0)
    }
}
