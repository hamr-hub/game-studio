package com.cocos.gamestudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object GameIconLoader {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryCache = ConcurrentHashMap<String, Bitmap>()

    fun bind(imageView: ImageView, fallbackView: TextView, game: GameEntry) {
        val iconUri = game.iconUri?.trim().orEmpty()
        imageView.tag = iconUri
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        fallbackView.visibility = View.VISIBLE

        if (iconUri.isBlank()) {
            return
        }

        memoryCache[iconUri]?.let { cached ->
            showBitmap(imageView, fallbackView, iconUri, cached)
            return
        }

        val appContext = imageView.context.applicationContext
        executor.execute {
            val bitmap = loadBitmap(appContext, iconUri)
            if (bitmap != null) {
                memoryCache[iconUri] = bitmap
                mainHandler.post {
                    showBitmap(imageView, fallbackView, iconUri, bitmap)
                }
            }
        }
    }

    private fun showBitmap(
        imageView: ImageView,
        fallbackView: TextView,
        iconUri: String,
        bitmap: Bitmap,
    ) {
        if (imageView.tag != iconUri) return
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        fallbackView.visibility = View.GONE
    }

    private fun loadBitmap(context: android.content.Context, iconUri: String): Bitmap? {
        return try {
            when {
                iconUri.startsWith("http://") || iconUri.startsWith("https://") -> {
                    val connection = (URL(iconUri).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                        setRequestProperty("Accept", "image/*")
                    }
                    try {
                        if (connection.responseCode in 200..299) {
                            connection.inputStream.use { BitmapFactory.decodeStream(it) }
                        } else {
                            null
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                iconUri.startsWith("assets://") -> {
                    val assetPath = iconUri.removePrefix("assets://").trimStart('/')
                    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
                }
                iconUri.startsWith("/") || iconUri.startsWith("file://") -> {
                    val path = iconUri.removePrefix("file://")
                    BitmapFactory.decodeFile(File(path).absolutePath)
                }
                else -> {
                    context.assets.open(iconUri.trimStart('/')).use { BitmapFactory.decodeStream(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
