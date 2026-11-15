package com.example.takeaway_board

import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        val preferences = getSharedPreferences("reviews", MODE_PRIVATE)
        MethodChannel(engine.dartExecutor.binaryMessenger, "takeaway_board").setMethodCallHandler { call, result ->
            when (call.method) {
                "load" -> result.success(preferences.getString("items", "[]"))
                "save" -> {
                    val data = call.arguments as? String
                    if (data == null) result.error("invalid", "Missing data", null)
                    else if (preferences.edit().putString("items", data).commit()) result.success(null)
                    else result.error("storage", "Write failed", null)
                }
                "share" -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, call.arguments as? String)
                    }
                    startActivity(Intent.createChooser(intent, "分享评价"))
                    result.success(null)
                }
                "overlay" -> runCatching { toggleBubble() }.fold(
                    onSuccess = { result.success(it) },
                    onFailure = { result.error("overlay", it.message, null) }
                )
                else -> result.notImplemented()
            }
        }
    }

    private fun toggleBubble(): Boolean {
        if (ShopOverlayService.running) {
            stopService(Intent(this, ShopOverlayService::class.java))
            return true
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val capture = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(capture, 43)
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 43 && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ShopOverlayService::class.java)
                .putExtra("resultCode", resultCode).putExtra("projection", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
    }
}
