package com.cocos.gamestudio

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private var nativeEngineHandle: Long = 0
    private var startTime: Long = 0
    private var gamePath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)

        gamePath = intent.getStringExtra("GAME_PATH") ?: ""
        if (gamePath.isBlank()) {
            finish()
            return
        }

        saveRecentlyPlayed(gamePath)
        startTime = System.currentTimeMillis()
    }

    private fun saveRecentlyPlayed(path: String) {
        val prefs = getSharedPreferences("user_prefs", 0)
        GameCatalog.addToRecent(prefs, path)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        nativeEngineHandle = NativeEngine.nativeInit(holder.surface, gamePath)
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
        NativeEngine.nativePause(nativeEngineHandle)
    }

    override fun onResume() {
        super.onResume()
        NativeEngine.nativeResume(nativeEngineHandle)
    }
}
