package com.example.alipayaccdemo

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        // 🔒 只抓特定 App 推播（自行換成你的目標 App）
        if (sbn.packageName != "com.eg.android.AlipayGphone") return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title")
        val text = extras.getCharSequence("android.text")?.toString()

        val intent = Intent("MY_NOTIFICATION_EVENT").apply {
            putExtra("title", title)
            putExtra("text", text)
        }

        // ⬅️ 廣播給 AccessibilityService
        sendBroadcast(intent)
    }
}
