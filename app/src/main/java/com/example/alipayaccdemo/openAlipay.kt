package com.example.alipayaccdemo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

// 🔹 打開「個人收款碼」
fun openAlipayCollectionCode(context: Context) {
    val schemeUrl = "alipays://platformapi/startapp?appId=20000123"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(schemeUrl))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "未檢測到支付寶或跳轉失敗", Toast.LENGTH_LONG).show()
    }
}

// 🔹 打開「掃一掃」頁面
fun openAlipayScanCode(context: Context) {
    val schemeUrl = "alipays://platformapi/startapp?saId=10000007"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(schemeUrl))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "未檢測到支付寶或跳轉失敗", Toast.LENGTH_LONG).show()
    }
}
