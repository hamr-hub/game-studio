package com.cocos.gamestudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.text.Charsets.UTF_8

class WebGameActivity : AppCompatActivity() {

    private val TAG = "WebGameActivity"
    private lateinit var webView: WebView
    private lateinit var gamePath: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        gamePath = normalizeAssetPath(intent.getStringExtra("GAME_PATH") ?: "")
        if (gamePath.isBlank()) {
            finish()
            return
        }

        setupWebView()
        runWebGame(gamePath)
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
    }

    private fun runWebGame(path: String) {
        val normalizedPath = normalizeAssetPath(path)
        val baseDir = prepareGameSandbox(normalizedPath)
        if (baseDir == null) {
            Log.w(TAG, "Cannot prepare web sandbox for $normalizedPath")
            Toast.makeText(this, "Unable to load game package in Web runtime.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val entryHtml = resolveEntryHtml(baseDir)
        webView.loadUrl("file://${entryHtml.absolutePath}")
    }

    private fun prepareGameSandbox(path: String): File? {
        if (path.startsWith(ASSET_GAME_PREFIX)) {
            return prepareAssetGameSandbox(path)
        }

        val source = File(path)
        if (!source.exists()) {
            return null
        }

        if (source.isDirectory) {
            return source
        }

        if (!isSupportedZip(path)) {
            return null
        }

        val sandboxRoot = File(cacheDir, "web-fallback/${sha1Hex(path)}")
        if (sandboxRoot.exists() && sandboxRoot.isDirectory) {
            return sandboxRoot
        }

        return if (unpackZip(source, sandboxRoot)) sandboxRoot else null
    }

    private fun prepareAssetGameSandbox(path: String): File? {
        val relative = path.removePrefix(ASSET_GAME_PREFIX).trimStart('/')
        if (relative.isEmpty()) {
            return null
        }

        val sandboxRoot = File(cacheDir, "web-fallback/assets-${sha1Hex(relative)}")
        if (sandboxRoot.exists() && sandboxRoot.isDirectory) {
            return sandboxRoot
        }

        return try {
            assets.open(relative).use { input ->
                val ok = unpackZipFromInput(input, sandboxRoot, true)
                if (ok) sandboxRoot else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed unpacking asset game package $relative", e)
            null
        }
    }

    private fun resolveEntryHtml(root: File): File {
        val hasIndex = listOf("index.html", "index.htm", "game.html").firstNotNullOfOrNull { name ->
            val candidate = File(root, name)
            if (candidate.exists()) candidate else null
        }
        if (hasIndex != null) {
            return hasIndex
        }

        val bootstrapCandidates = listOf(
            "game.js",
            "main.js",
            "application.js",
            "assets/main/index.js",
            "assets/index.js",
            "index.js",
            "src/main/index.js",
            "src/index.js",
        )
        val bootstrapScript = bootstrapCandidates.firstOrNull { candidate ->
            File(root, candidate).exists()
        } ?: "game.js"

        val html = File(root, "index.bootstrap.html")
        val fallbackHtml = """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <style>
                  html, body, canvas { margin: 0; width: 100%; height: 100%; background: #000; }
                  #GameCanvas { width: 100%; height: 100%; display: block; }
                </style>
              </head>
              <body>
                <canvas id="GameCanvas"></canvas>
                <script src="$bootstrapScript"></script>
              </body>
            </html>
        """.trimIndent()

        html.writeText(fallbackHtml, UTF_8)
        return html
    }

    private fun unpackZip(zipFile: File, targetDir: File): Boolean {
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        if (!targetDir.mkdirs()) {
            return false
        }

        return try {
            BufferedInputStream(FileInputStream(zipFile)).use { input ->
                unpackZipFromInput(input, targetDir, false)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun unpackZipFromInput(
        input: InputStream,
        targetDir: File,
        overwriteDirectory: Boolean,
    ): Boolean {
        if (targetDir.exists() && overwriteDirectory) {
            targetDir.deleteRecursively()
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return false
        }

        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        return try {
            ZipInputStream(buffered).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed unpacking zip game package", e)
            false
        }
    }

    private fun isSupportedZip(path: String): Boolean {
        return path.endsWith(".zip", ignoreCase = true)
    }

    private fun normalizeAssetPath(path: String): String {
        val trimmed = path.trim()
        return when {
            trimmed.startsWith(ASSET_GAME_PREFIX) -> trimmed
            trimmed.startsWith("/assets://") -> trimmed.removePrefix("/")
            trimmed.startsWith("assets:/") && !trimmed.startsWith(ASSET_GAME_PREFIX) ->
                "assets://${trimmed.removePrefix("assets:/").trimStart('/')}"
            trimmed.startsWith("/assets:/") ->
                "assets://${trimmed.removePrefix("/assets:/").trimStart('/')}"
            else -> trimmed
        }
    }

    companion object {
        private const val ASSET_GAME_PREFIX = "assets://"

        fun start(context: Context, gamePath: String) {
            context.startActivity(Intent(context, WebGameActivity::class.java).apply {
                putExtra("GAME_PATH", gamePath)
            })
        }
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(UTF_8))
            .joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        return digest.ifEmpty { input.hashCode().toString() }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
