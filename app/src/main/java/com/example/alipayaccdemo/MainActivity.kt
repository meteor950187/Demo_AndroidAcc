package com.example.alipayaccdemo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.alipayaccdemo.ui.theme.AlipayAccDemoTheme
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MainActivity : ComponentActivity() {

    private  var isAllCheck = false
    private var captureStatus = mutableStateOf(if (ProjectionStore.data != null) "掃碼狀態：✅ 已取得" else "掃碼狀態：❌ 未取得")

    private var authStatus = mutableStateOf(if (ProjectionStore.data != null) "授權狀態：✅ 已成功" else "授權狀態：❌ 未成功")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    // 申請 MediaProjection 授權（供 Accessibility 觸發前景服務截圖用）
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                ProjectionStore.setConsent(r.resultCode, r.data!!)
                authStatus.value = "授權狀態：✅ 已取得"
                Toast.makeText(this, "已取得螢幕擷取授權", Toast.LENGTH_SHORT).show()
                openAccessibilitySettings()
            } else {
                Toast.makeText(this, "使用者未授權螢幕擷取", Toast.LENGTH_SHORT).show()
            }
        }

    val qrScanLauncher =
        registerForActivityResult(ScanContract()) { result: ScanIntentResult? ->
            if (result == null) {
                Toast.makeText(this, "沒有結果", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            if (result.contents != null) {
                Toast.makeText(this, "掃到：${result.contents}", Toast.LENGTH_LONG).show()
                val jobj = json.parseToJsonElement(result.contents).jsonObject
                val auth = jobj["Token"]?.jsonPrimitive?.content
                val url = jobj["wsUrl"]?.jsonPrimitive?.content
                ScanAuth.setAuthData(auth!!, url!!)
                captureStatus.value = "掃碼狀態：✅ 已成功"

            } else {
                Toast.makeText(this, "使用者取消掃描", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()


        setContent {
            AlipayAccDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        captureStatusText = captureStatus.value,
                        authStatusText = authStatus.value,
                        onCheckAllAuthClick = { checkAllAuth() },
                        onScanQRCode = {startQrScan()}
                    )
                }
            }
        }
    }

    private fun requestProjectionPermission() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            Toast.makeText(this, "裝置不支援 MediaProjection", Toast.LENGTH_LONG).show()
            return
        }
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun openAccessibilitySettings() {
        if (!isMyAccServiceEnabled(this)) {
            // 服務已關 → 明確廣播（限定本包）
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isMyAccServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val me = ComponentName(context, AlipayAccService::class.java)
        return list.any { it.resolveInfo?.serviceInfo?.packageName == me.packageName &&
                it.resolveInfo?.serviceInfo?.name == me.className }
    }


    private fun checkAllAuth() {
        if(isAllCheck){
            openAccessibilitySettings()
            return
        }

        if (ScanAuth.isReady()){
            if(ProjectionStore.data == null) {
                requestProjectionPermission()
            }else if (!isMyAccServiceEnabled(this)){
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }else{
                //Toast.makeText(this, "已取得所有授權", Toast.LENGTH_SHORT).show()

                isAllCheck = true
                openAccessibilitySettings()
            }
        }else{
            Toast.makeText(this, "請先掃碼", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startQrScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("請對準 QR Code")
            setBeepEnabled(true)
            setCameraId(0)
            captureActivity = PortraitCaptureActivity::class.java   // ← 關鍵
        }
        qrScanLauncher.launch(options)
    }
}

@Composable
private fun MainScreen(
    modifier: Modifier = Modifier,
    authStatusText: String,
    captureStatusText: String,
    onCheckAllAuthClick: () -> Unit,
    onScanQRCode: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(text = "跑分偵測", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onScanQRCode) {
            Text("QRCode掃碼")
        }
        Button(onClick = onCheckAllAuthClick) {
            Text("確認授權")
        }



        Text(
            text = captureStatusText,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = authStatusText,
            style = MaterialTheme.typography.bodyMedium
        )


        Text(
            text = "1. 請先掃碼專用QRCode \n" +
                "2. 點擊\"確認授權\"確認狀態 \n" +
                "3. 允許螢幕截圖權限 \n" +
                "4. 無障礙 => 下載的應用程式 => 允許 PointScan \n" +
                "5. 再點擊一次\"確認授權\"確認",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview
@Composable
private fun PreviewMain() {
    AlipayAccDemoTheme {
        MainScreen(
            modifier = Modifier,
            authStatusText = "",
            captureStatusText = "",
            onCheckAllAuthClick = {},
            onScanQRCode =  {}
        )
    }
}
