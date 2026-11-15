package com.example.takeaway_board

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

class ShopOverlayService : Service() {
    companion object {
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: View? = null
    private var scanning = false
    private var closed = false
    private var recognizerUsed = false
    private val prefs by lazy { getSharedPreferences("reviews", MODE_PRIVATE) }
    private val green = Color.rgb(28, 74, 59)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val token = intent.getParcelableExtra<Intent>("projection")
        if (token == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("shop_ocr", "店铺识别", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "shop_ocr")
            else Notification.Builder(this)
        startForeground(43, notification.setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("悬浮窗已开启").setContentText("点击悬浮图标识别当前店铺")
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "关闭", stop
            ).build()).build())
        runCatching {
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), token)
            checkNotNull(projection).registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            running = true
            bubble()
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun show(view: View, panel: Boolean) {
        if (closed) return
        removeOverlay()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        val width = if (panel) minOf(dp(410), resources.displayMetrics.widthPixels - dp(24)) else dp(56)
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, type,
            if (panel) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = if (panel) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.END or Gravity.CENTER_VERTICAL
            y = if (panel) dp(24) else 0
            x = if (panel) 0 else dp(8)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { windows.addView(view, params); overlay = view }.onFailure { stopSelf() }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windows.removeView(it) } }
        overlay = null
    }

    private fun bubble() {
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.ic_review_board)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "识别当前店铺"
            minimumHeight = dp(56)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(255, 244, 219), 28)
            setOnClickListener { scan() }
            setOnLongClickListener { stopSelf(); true }
        }
        show(button, false)
    }

    private fun scan() {
        if (scanning || closed) return
        scanning = true
        removeOverlay()
        handler.postDelayed({
            if (closed) return@postDelayed
            runCatching {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windows.defaultDisplay.getRealMetrics(metrics)
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi
                val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                reader = source
                source.setOnImageAvailableListener({ available ->
                    val frame = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val image = try {
                        frame.use {
                            val plane = it.planes[0]
                            Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                                .apply { copyPixelsFromBuffer(plane.buffer) }
                        }
                    } catch (_: Exception) {
                        endCapture()
                        panel(emptyList(), "无法读取屏幕，请手动输入")
                        return@setOnImageAvailableListener
                    }
                    endCapture()
                    val cropped = Bitmap.createBitmap(image, 0, 0, width, (height * .55).toInt().coerceAtLeast(1))
                    if (cropped !== image) image.recycle()
                    recognizerUsed = true
                    recognizer.process(InputImage.fromBitmap(cropped, 0))
                        .addOnSuccessListener { text ->
                            val candidates = text.textBlocks.flatMap { it.lines }
                                .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
                                .map { it.text.trim() }.distinct()
                                .filter { it.length in 2..38 && it.any(Char::isLetter) }
                                .filterNot { it in listOf("搜索店内商品", "点餐", "评价", "商家", "购物车", "到店自取") }
                            val known = reviews().map { it.optString("shop") }.filter(String::isNotBlank).distinct()
                            val matches = known.filter { shop -> candidates.any { it == shop || shop.length >= 3 && it.contains(shop) } }
                            val names = (matches + candidates).distinct().take(6)
                            panel(names, if (names.isEmpty()) "没识别到店名，可以手动输入" else "")
                        }.addOnFailureListener { panel(emptyList(), "识别失败，请手动输入") }
                        .addOnCompleteListener { cropped.recycle() }
                }, handler)
                val existing = display
                if (existing == null) display = checkNotNull(projection).createVirtualDisplay(
                    "Shop OCR", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    source.surface, null, handler
                ) else {
                    existing.resize(width, height, density)
                    existing.surface = source.surface
                }
                handler.postDelayed({
                    if (reader === source) {
                        endCapture()
                        panel(emptyList(), "未能读取当前画面，请手动输入")
                    }
                }, 2500)
            }.onFailure {
                endCapture()
                panel(emptyList(), "无法捕获画面，请手动输入")
            }
        }, 220)
    }

    private fun endCapture() {
        runCatching { display?.surface = null }
        reader?.close()
        reader = null
    }

    private fun reviews(): List<JSONObject> = runCatching {
        val items = JSONArray(prefs.getString("items", "[]"))
        (0 until items.length()).map { items.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun panel(candidates: List<String>, message: String = "") {
        scanning = false
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.rgb(255, 248, 232), 24)
        }
        fun label(value: String) = TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(green)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            stateListAnimator = null
            elevation = 0f
            setTextColor(green)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.rgb(205, 225, 209)),
                rounded(Color.rgb(236, 241, 224), 18), null
            )
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            setOnClickListener { action() }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_review_board)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        header.addView(label("悬浮窗"), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("收起") { bubble() })
        layout.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(this).apply {
            addView(body)
            isFillViewport = false
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(scroller, LinearLayout.LayoutParams(-1, minOf(dp(420), resources.displayMetrics.heightPixels / 2)))
        if (message.isNotEmpty()) body.addView(label(message))
        val shop = EditText(this).apply {
            hint = "确认或输入店名"
            setTextColor(green)
            setHintTextColor(Color.DKGRAY)
            setSingleLine()
            setText(candidates.firstOrNull().orEmpty())
        }
        body.addView(shop)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun lookup() {
            results.removeAllViews()
            val name = shop.text.toString().trim()
            val matches = reviews().filter { it.optString("shop").trim().equals(name, true) }
            results.addView(label(if (matches.isEmpty()) "这家店还没记过" else "找到 ${matches.size} 条评价"))
            matches.forEach {
                val verdict = if (it.optBoolean("good")) "红榜" else "黑榜"
                results.addView(label("$verdict · ${it.optString("dish")}\n${it.optString("note")}"))
            }
        }
        body.addView(button("查记录") { lookup() })
        if (candidates.size > 1) body.addView(button("选择识别结果") {
            results.removeAllViews()
            candidates.forEach { name -> results.addView(button(name) { shop.setText(name); lookup() }) }
        })
        body.addView(results)
        layout.addView(button("带入店铺并记一单") {
            results.removeAllViews()
            val dish = EditText(this).apply { hint = "菜品"; setTextColor(green); setHintTextColor(Color.DKGRAY) }
            val note = EditText(this).apply { hint = "评价（可选）"; setTextColor(green); setHintTextColor(Color.DKGRAY) }
            results.addView(dish)
            results.addView(note)
            fun save(good: Boolean) {
                val name = shop.text.toString().trim()
                val food = dish.text.toString().trim()
                if (name.isBlank()) { shop.error = "请输入店铺名称"; return }
                if (food.isBlank()) { dish.error = "请输入菜品"; return }
                val items = JSONArray()
                items.put(JSONObject().put("shop", name).put("dish", food).put("note", note.text.toString().trim())
                    .put("good", good).put("date", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
                        .format(java.util.Date())))
                reviews().forEach { items.put(it) }
                if (prefs.edit().putString("items", items.toString()).commit()) lookup()
                else Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            }
            results.addView(button("保存到红榜") { save(true) })
            results.addView(button("保存到黑榜") { save(false) })
            scroller.post { scroller.smoothScrollTo(0, results.top) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        })
        if (candidates.isNotEmpty()) lookup()
        show(layout, true)
    }

    override fun onDestroy() {
        closed = true
        running = false
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        endCapture()
        display?.release()
        projection?.stop()
        if (recognizerUsed) recognizer.close()
        super.onDestroy()
    }
}
