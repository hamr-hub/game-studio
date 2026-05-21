package com.cocos.gamestudio

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statsTv: TextView
    private lateinit var consoleTv: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var captureBtn: ImageButton
    private lateinit var consoleToggleBtn: ImageButton
    
    private val allLogs = mutableListOf<String>()
    private var currentFilter = "ALL"
    
    private var nativeEngineHandle: Long = 0
    private var startTime: Long = 0
    private var gamePath: String = ""
    
    private val handler = Handler(Looper.getMainLooper())
    private val statsUpdater = object : Runnable {
        override fun run() {
            if (nativeEngineHandle != 0L) {
                statsTv.text = NativeEngine.nativeGetPerformanceStats(nativeEngineHandle)
                updateLogs()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateLogs() {
        val logs = NativeEngine.nativeGetLogs(nativeEngineHandle)
        if (logs.isNotEmpty()) {
            allLogs.addAll(logs)
            if (allLogs.size > 500) {
                repeat(allLogs.size - 500) { allLogs.removeAt(0) }
            }
            refreshConsole()
        }
    }

    private fun refreshConsole() {
        consoleTv.text = ""
        allLogs.forEach { log ->
            if (currentFilter == "ALL" || log.contains("[$currentFilter]")) {
                val spannable = SpannableString(log + "\n")
                val color = when {
                    log.contains("[ERROR]") -> Color.RED
                    log.contains("[WARN]") -> Color.YELLOW
                    else -> Color.GREEN
                }
                spannable.setSpan(ForegroundColorSpan(color), 0, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                consoleTv.append(spannable)
            }
        }
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        
        surfaceView = SurfaceView(this)
        root.addView(surfaceView)
        
        // Stats
        statsTv = TextView(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setTextColor(Color.WHITE)
            setPadding(20, 10, 20, 10)
            textSize = 12f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(20, 20, 0, 0)
            }
        }
        root.addView(statsTv)

        // Console Container
        val consoleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                600,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.END
            }
        }

        // Filter Bar
        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(5, 5, 5, 5)
            setBackgroundColor(Color.DKGRAY)
        }
        val filters = arrayOf("ALL", "INFO", "WARN", "ERROR")
        filters.forEach { filter ->
            val btn = Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = filter
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    currentFilter = filter
                    refreshConsole()
                }
            }
            filterBar.addView(btn)
        }
        consoleContainer.addView(filterBar)

        // Console Scroll
        consoleScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        consoleTv = TextView(this).apply {
            textSize = 10f
            setPadding(10, 10, 10, 10)
        }
        consoleScroll.addView(consoleTv)
        consoleContainer.addView(consoleScroll)
        root.addView(consoleContainer)

        // Buttons
        captureBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setBackgroundResource(android.R.drawable.btn_default)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, 40, 40)
            }
            setOnClickListener { takeScreenshot() }
        }
        root.addView(captureBtn)

        consoleToggleBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_info_details)
            setBackgroundResource(android.R.drawable.btn_default)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(40, 0, 0, 40)
            }
            setOnClickListener {
                consoleContainer.visibility = if (consoleContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(consoleToggleBtn)
        
        setContentView(root)
        surfaceView.holder.addCallback(this)

        gamePath = intent.getStringExtra("GAME_PATH") ?: ""
        if (gamePath.isBlank()) {
            finish()
            return
        }

        saveRecentlyPlayed(gamePath)
        startTime = System.currentTimeMillis()
    }

    private fun takeScreenshot() {
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                saveBitmapToGallery(bitmap)
            } else {
                Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
            }
        }, handler)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "CocosCapture_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CocosStudio")
            }
        }

        val contentResolver = contentResolver
        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        imageUri?.let {
            fos = contentResolver.openOutputStream(it)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos!!)
            fos?.close()
            Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRecentlyPlayed(path: String) {
        val prefs = getSharedPreferences("user_prefs", 0)
        GameCatalog.addToRecent(prefs, path)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        nativeEngineHandle = NativeEngine.nativeInit(holder.surface, gamePath)
        applyEngineSettings()
    }

    private fun applyEngineSettings() {
        if (nativeEngineHandle == 0L) return
        val prefs = getSharedPreferences("engine_settings", 0)
        val fpsLimit = prefs.getInt("fps_limit", 60)
        val enableShadows = prefs.getBoolean("enable_shadows", true)
        NativeEngine.nativeUpdateSettings(nativeEngineHandle, fpsLimit, enableShadows)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeEngine.nativeResize(nativeEngineHandle, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeEngine.nativeDestroy(nativeEngineHandle)
        nativeEngineHandle = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        if (startTime > 0) {
            val endTime = System.currentTimeMillis()
            val durationMinutes = (endTime - startTime) / 60000

            val prefs = getSharedPreferences("user_prefs", 0)
            val totalMinutes = prefs.getLong("minutes_played", 0)
            prefs.edit().putLong("minutes_played", totalMinutes + durationMinutes).apply()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statsUpdater)
        NativeEngine.nativePause(nativeEngineHandle)
    }

    override fun onResume() {
        super.onResume()
        handler.post(statsUpdater)
        NativeEngine.nativeResume(nativeEngineHandle)
    }
}
