package com.example.alipayaccdemo

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity

class QrResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 簡單內建 UI（不用 Compose 以免多檔）
        val tv = TextView(this).apply {
            textSize = 18f
            val txt = intent.getStringExtra(EXTRA_QR_TEXT) ?: "未辨識到 QRCode"
            text = "QR 內容：\n$txt"
            setPadding(60, 60, 60, 40)
        }

        val btn = Button(this).apply {
            text = "確認，開始讀取畫面文字"
            setOnClickListener {
                // 發出廣播，通知無障礙服務開始讀取畫面文字
                sendBroadcast(Intent(ACTION_QR_CONFIRMED))
                finish()
            }
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(tv)
            addView(btn)
            setPadding(40, 40, 40, 40)
        }
        setContentView(root)
    }

    companion object {
        const val EXTRA_QR_TEXT = "qr_text"
        const val ACTION_QR_CONFIRMED = "com.example.alipayaccdemo.QR_CONFIRMED"
    }
}
