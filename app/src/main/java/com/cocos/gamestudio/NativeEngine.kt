package com.cocos.gamestudio

import android.content.res.AssetManager
import android.view.Surface

object NativeEngine {
    init {
        System.loadLibrary("cocos_studio")
    }

    external fun nativeSetAssetManager(assetManager: AssetManager)
    external fun nativeInit(surface: Surface, gamePath: String): Long
    external fun nativeResize(handle: Long, width: Int, height: Int)
    external fun nativeDestroy(handle: Long)
    external fun nativePause(handle: Long)
    external fun nativeResume(handle: Long)
    external fun nativeGetPerformanceStats(handle: Long): String
    external fun nativeGetInitError(): String
    external fun nativeUpdateSettings(handle: Long, fpsLimit: Int, enableShadows: Boolean)
    external fun nativeGetLogs(handle: Long): Array<String>
}
