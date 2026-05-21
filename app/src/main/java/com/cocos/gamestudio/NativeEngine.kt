package com.cocos.gamestudio

import android.view.Surface

object NativeEngine {
    init {
        System.loadLibrary("cocos_studio")
    }

    external fun nativeInit(surface: Surface, gamePath: String): Long
    external fun nativeResize(handle: Long, width: Int, height: Int)
    external fun nativeDestroy(handle: Long)
    external fun nativePause(handle: Long)
    external fun nativeResume(handle: Long)
}
