package com.cocos.gamestudio

import android.app.Activity
import android.content.pm.ActivityInfo

object GameOrientationLock {
    fun apply(activity: Activity, rawOrientation: String?): String {
        val orientation = GameOrientation.normalize(rawOrientation.orEmpty()) ?: GameOrientation.LANDSCAPE
        activity.requestedOrientation = when (orientation) {
            GameOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        return orientation
    }

    fun clear(activity: Activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
}
