package com.cocos.gamestudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.system.Os
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
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
    private var gameOrientation: String = GameOrientation.LANDSCAPE
    private var startTime: Long = 0L
    private var shouldRecordSession = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameOrientation = GameOrientationLock.apply(this, GameOrientation.LANDSCAPE)
        val configuredOrientation = applyConfiguredOrientation(intent.getStringExtra(GameActivity.EXTRA_GAME_ORIENTATION))

        webView = WebView(this)
        setContentView(webView)

        gamePath = normalizeAssetPath(intent.getStringExtra("GAME_PATH") ?: "")
        if (gamePath.isBlank()) {
            shouldRecordSession = false
            finish()
            return
        }

        GameCatalog.addToRecent(getSharedPreferences("user_prefs", 0), gamePath)

        setupWebView()
        runWebGame(gamePath, configuredOrientation)
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (shouldSuppressConsoleMessage(consoleMessage)) {
                    return true
                }
                Log.d(
                    TAG,
                    "console/${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}",
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return shouldBlockExternalNavigation(request?.url?.toString(), request?.isForMainFrame ?: true)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return shouldBlockExternalNavigation(url, true)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()
                return if (shouldBlockExternalWebResource(url)) {
                    emptyWebResourceResponse(url.orEmpty())
                } else {
                    null
                }
            }
        }
    }

    private fun shouldSuppressConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        return when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR,
            ConsoleMessage.MessageLevel.WARNING -> true
            else -> false
        }
    }

    private fun shouldBlockExternalNavigation(url: String?, isForMainFrame: Boolean): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }

        val scheme = url.substringBefore(':', "").lowercase()
        val sandboxScheme = scheme in setOf("file", "about", "data", "blob")
        if (sandboxScheme || (!isForMainFrame && scheme in setOf("http", "https"))) {
            return false
        }

        return true
    }

    private fun shouldBlockExternalWebResource(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }
        val normalized = url.lowercase()
        val scheme = normalized.substringBefore(':', "")
        if (scheme !in setOf("http", "https")) {
            return false
        }
        return BLOCKED_WEB_RESOURCE_MARKERS.any { marker -> normalized.contains(marker) }
    }

    private fun emptyWebResourceResponse(url: String): WebResourceResponse {
        val normalized = url.lowercase()
        val mimeType = when {
            normalized.endsWith(".js") -> "application/javascript"
            normalized.endsWith(".json") -> "application/json"
            normalized.endsWith(".css") -> "text/css"
            else -> "text/plain"
        }
        val body = if (mimeType == "application/json") "{}" else ""
        return WebResourceResponse(mimeType, "UTF-8", ByteArrayInputStream(body.toByteArray(UTF_8)))
    }

    private fun runWebGame(path: String, hasConfiguredOrientation: Boolean) {
        val normalizedPath = normalizeAssetPath(path)
        val baseDir = prepareGameSandbox(normalizedPath)
        if (baseDir == null) {
            Log.i(TAG, "Web sandbox unavailable for $normalizedPath")
            shouldRecordSession = false
            finish()
            return
        }

        startTime = System.currentTimeMillis()
        if (!hasConfiguredOrientation) {
            applyPackageOrientation(baseDir)
        }
        val entryHtml = resolveEntryHtml(baseDir)
        Log.i(TAG, "Loading web sandbox entry ${entryHtml.absolutePath}")
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

        val sandboxRoot = File(
            cacheDir,
            "web-fallback/${sha1Hex("${source.absolutePath}:${source.length()}:${source.lastModified()}")}",
        )
        if (isPreparedSandbox(sandboxRoot)) {
            return finalizePreparedSandbox(sandboxRoot)
        }

        return if (unpackZip(source, sandboxRoot)) finalizePreparedSandbox(sandboxRoot) else null
    }

    private fun prepareAssetGameSandbox(path: String): File? {
        val relative = path.removePrefix(ASSET_GAME_PREFIX).trimStart('/')
        if (relative.isEmpty()) {
            return null
        }

        val assetSize = try {
            assets.open(relative).use { it.available().toLong() }
        } catch (_: Exception) {
            -1L
        }

        val sandboxRoot = File(cacheDir, "web-fallback/assets-${sha1Hex("$relative:$assetSize")}")
        if (isPreparedSandbox(sandboxRoot)) {
            return finalizePreparedSandbox(sandboxRoot)
        }

        return try {
            assets.open(relative).use { input ->
                val ok = unpackZipFromInput(input, sandboxRoot, true)
                if (ok) finalizePreparedSandbox(sandboxRoot) else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed unpacking asset game package $relative", e)
            null
        }
    }

    private fun finalizePreparedSandbox(root: File): File {
        createCocosAssetAliases(root)
        createRemoteBundleAliases(root)
        createSubpackageAliases(root)
        return root
    }

    private fun createCocosAssetAliases(root: File) {
        val assetsRoot = File(root, "assets")
        if (!assetsRoot.isDirectory) {
            return
        }

        COCOS_BUNDLE_ALIASES.forEach { bundleName ->
            val source = File(assetsRoot, bundleName)
            val target = File(root, bundleName)
            if (!source.exists() || target.exists()) {
                return@forEach
            }

            try {
                Os.symlink(source.absolutePath, target.absolutePath)
            } catch (_: Exception) {
                try {
                    source.copyRecursively(target, overwrite = false)
                } catch (copyError: Exception) {
                    Log.w(
                        TAG,
                        "Unable to create Cocos asset alias ${target.name}",
                        copyError,
                    )
                }
            }
        }
    }

    private fun createRemoteBundleAliases(root: File) {
        val remoteRoot = File(root, "remote")
        if (!remoteRoot.isDirectory) {
            return
        }

        remoteRoot.listFiles { file -> file.isDirectory }?.forEach { source ->
            val target = File(root, source.name)
            if (target.exists()) {
                return@forEach
            }

            try {
                Os.symlink(source.absolutePath, target.absolutePath)
            } catch (_: Exception) {
                try {
                    source.copyRecursively(target, overwrite = false)
                } catch (copyError: Exception) {
                    Log.w(
                        TAG,
                        "Unable to create remote bundle alias ${target.name}",
                        copyError,
                    )
                }
            }
        }
    }

    private fun createSubpackageAliases(root: File) {
        val subpackagesRoot = File(root, "subpackages")
        if (!subpackagesRoot.isDirectory) {
            return
        }

        subpackagesRoot.listFiles { file -> file.isDirectory }?.forEach { source ->
            val target = File(root, source.name)
            if (target.exists()) {
                return@forEach
            }

            try {
                Os.symlink(source.absolutePath, target.absolutePath)
            } catch (_: Exception) {
                try {
                    source.copyRecursively(target, overwrite = false)
                } catch (copyError: Exception) {
                    Log.w(
                        TAG,
                        "Unable to create Cocos subpackage alias ${target.name}",
                        copyError,
                    )
                }
            }
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

        val bootstrapScript = findBootstrapScript(root)
            ?: return writeDiagnosticsHtml(root, "No runnable web entry was found in this package.")

        val html = File(root, "index.bootstrap.html")
        val runtimePrelude = webRuntimePrelude()
        val escapedBootstrapScript = escapeJsString("./$bootstrapScript")
        val fallbackHtml = """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <style>
                  html, body { margin: 0; width: 100%; height: 100%; overflow: hidden; background: #000; }
                  #GameCanvas { width: 100vw; height: 100vh; display: block; touch-action: none; }
                </style>
              </head>
              <body>
                <canvas id="GameCanvas"></canvas>
                <script>
                $runtimePrelude
                require('$escapedBootstrapScript');
                </script>
              </body>
            </html>
        """.trimIndent()

        html.writeText(fallbackHtml, UTF_8)
        return html
    }

    private fun findBootstrapScript(root: File): String? {
        val bootstrapCandidates = listOf(
            "game.js",
            "main.js",
            "application.js",
            "assets/main/index.js",
            "assets/index.js",
            "index.js",
            "src/main/index.js",
            "src/index.js",
            "assets/resources/index.js",
            "assets/internal/index.js",
        )
        val direct = bootstrapCandidates.firstOrNull { candidate ->
            File(root, candidate).isFile
        }
        if (direct != null) {
            return direct
        }

        return root.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .sortedWith(compareBy<String> { scoreBootstrapScript(it) }.thenBy { it })
            .firstOrNull()
    }

    private fun scoreBootstrapScript(path: String): Int {
        return when {
            path.endsWith("/game.js") || path == "game.js" -> 0
            path.endsWith("/main.js") || path == "main.js" -> 1
            path.endsWith("/application.js") || path == "application.js" -> 2
            path.endsWith("/index.js") -> 3
            else -> 4
        }
    }

    private fun unpackZip(zipFile: File, targetDir: File): Boolean {
        return try {
            BufferedInputStream(FileInputStream(zipFile)).use { input ->
                unpackZipFromInput(input, targetDir, true)
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
        var extractedFiles = 0
        var partial = false
        return try {
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = try {
                        zip.nextEntry
                    } catch (e: EOFException) {
                        partial = true
                        Log.w(TAG, "Zip ended while reading next entry; keeping recovered files", e)
                        null
                    } ?: break

                    val outFile = safeZipEntryTarget(targetDir, entry)
                    if (outFile == null) {
                        Log.w(TAG, "Skipping unsafe zip entry ${entry.name}")
                        safeCloseEntry(zip)
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        try {
                            FileOutputStream(outFile).use { output ->
                                zip.copyTo(output)
                            }
                            extractedFiles += 1
                        } catch (e: EOFException) {
                            partial = true
                            outFile.delete()
                            Log.w(TAG, "Zip entry ${entry.name} ended early; keeping previous recovered files", e)
                            break
                        }
                    }
                    safeCloseEntry(zip)
                }
            }
            val runnable = hasRunnableEntry(targetDir)
            if (runnable) {
                markSandboxReady(targetDir, extractedFiles, partial)
            } else {
                targetDir.deleteRecursively()
            }
            runnable
        } catch (e: Exception) {
            Log.d(TAG, "Failed unpacking zip game package", e)
            targetDir.deleteRecursively()
            false
        }
    }

    private fun safeZipEntryTarget(targetDir: File, entry: ZipEntry): File? {
        val outFile = File(targetDir, entry.name)
        val targetPath = targetDir.canonicalPath + File.separator
        val outputPath = outFile.canonicalPath
        return if (outputPath.startsWith(targetPath)) outFile else null
    }

    private fun safeCloseEntry(zip: ZipInputStream) {
        try {
            zip.closeEntry()
        } catch (_: Exception) {
        }
    }

    private fun isPreparedSandbox(root: File): Boolean {
        return root.isDirectory && File(root, SANDBOX_READY_FILE).isFile && hasRunnableEntry(root)
    }

    private fun hasRunnableEntry(root: File): Boolean {
        val hasHtml = listOf("index.html", "index.htm", "game.html").any { File(root, it).isFile }
        return hasHtml || findBootstrapScript(root) != null
    }

    private fun markSandboxReady(root: File, extractedFiles: Int, partial: Boolean) {
        File(root, SANDBOX_READY_FILE).writeText(
            "files=$extractedFiles\npartial=$partial\n",
            UTF_8,
        )
    }

    private fun applyPackageOrientation(root: File) {
        val gameJson = File(root, "game.json")
        if (!gameJson.isFile) {
            return
        }

        val content = try {
            gameJson.readText(UTF_8)
        } catch (_: Exception) {
            return
        }

        when {
            content.contains("\"deviceOrientation\"", ignoreCase = true) &&
                content.contains("\"portrait\"", ignoreCase = true) ->
                gameOrientation = GameOrientationLock.apply(this, GameOrientation.PORTRAIT)
            content.contains("\"deviceOrientation\"", ignoreCase = true) &&
                content.contains("\"landscape\"", ignoreCase = true) ->
                gameOrientation = GameOrientationLock.apply(this, GameOrientation.LANDSCAPE)
        }
    }

    private fun applyConfiguredOrientation(rawOrientation: String?): Boolean {
        val orientation = GameOrientation.normalize(rawOrientation.orEmpty()) ?: return false
        gameOrientation = GameOrientationLock.apply(this, orientation)
        return true
    }

    private fun writeDiagnosticsHtml(root: File, message: String): File {
        val html = File(root, "index.bootstrap.html")
        val escaped = escapeHtml(message)
        html.writeText(
            """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                  html, body { margin: 0; width: 100%; height: 100%; background: #101820; color: #fff; font: 16px sans-serif; }
                  body { display: flex; align-items: center; justify-content: center; text-align: center; padding: 24px; box-sizing: border-box; }
                </style>
              </head>
              <body>$escaped</body>
            </html>
            """.trimIndent(),
            UTF_8,
        )
        return html
    }

    private fun webRuntimePrelude(): String {
        return """
            (function () {
              var canvas = document.getElementById('GameCanvas');
              var gameGlobal = window.GameGlobal && window.GameGlobal !== window ? window.GameGlobal : {};
              window.canvas = canvas;
              window.GameGlobal = gameGlobal;
              window.global = gameGlobal;
              window.globalThis = gameGlobal;
              window.screencanvas = window.screencanvas || canvas;
              window.__webSandboxLoaded = {};
              var NativeXMLHttpRequest = window.XMLHttpRequest;
              try {
                localStorage.setItem('AD_REMOVE', 'true');
                localStorage.setItem('ad_remove', 'true');
                localStorage.setItem('removeAds', 'true');
              } catch (e) {}
              if (window.console) {
                window.console.warn = function () {};
                window.console.error = function () {};
              }

              function describeSandboxError(reason) {
                if (!reason) return 'unknown error';
                if (reason.stack) return reason.stack;
                if (reason.message) return reason.message;
                try { return JSON.stringify(reason); } catch (e) { return String(reason); }
              }
              window.addEventListener('error', function (event) {
                event && event.preventDefault && event.preventDefault();
                event && event.stopImmediatePropagation && event.stopImmediatePropagation();
                return true;
              }, true);
              window.addEventListener('unhandledrejection', function (event) {
                event && event.preventDefault && event.preventDefault();
                event && event.stopImmediatePropagation && event.stopImmediatePropagation();
                return true;
              }, true);

              Object.assign(gameGlobal, {
                window: gameGlobal,
                GameGlobal: gameGlobal,
                global: gameGlobal,
                globalThis: gameGlobal,
                self: gameGlobal,
                parent: gameGlobal,
                top: gameGlobal,
                document: document,
                navigator: navigator,
                location: location,
                localStorage: localStorage,
                screen: screen,
                performance: performance,
                canvas: canvas,
                screencanvas: canvas,
                setTimeout: setTimeout.bind(window),
                setInterval: setInterval.bind(window),
                clearTimeout: clearTimeout.bind(window),
                clearInterval: clearInterval.bind(window),
                requestAnimationFrame: requestAnimationFrame.bind(window),
                cancelAnimationFrame: cancelAnimationFrame.bind(window),
                XMLHttpRequest: window.XMLHttpRequest,
                WebSocket: window.WebSocket,
                Image: window.Image,
                ImageBitmap: window.ImageBitmap,
                Audio: window.Audio,
                FileReader: window.FileReader,
                HTMLElement: window.HTMLElement,
                HTMLImageElement: window.HTMLImageElement,
                HTMLCanvasElement: window.HTMLCanvasElement,
                HTMLMediaElement: window.HTMLMediaElement,
                HTMLAudioElement: window.HTMLAudioElement,
                HTMLVideoElement: window.HTMLVideoElement,
                WebGLRenderingContext: window.WebGLRenderingContext,
                WebAssembly: window.WebAssembly,
                KSWebAssembly: window.WebAssembly,
                WXWebAssembly: window.WebAssembly,
                TouchEvent: window.TouchEvent,
                MouseEvent: window.MouseEvent,
                DeviceMotionEvent: window.DeviceMotionEvent
              });
              gameGlobal.__globalAdapter = gameGlobal.__globalAdapter || window.__globalAdapter || {};
              window.__globalAdapter = gameGlobal.__globalAdapter;
              window.KSWebAssembly = window.KSWebAssembly || window.WebAssembly;
              window.WXWebAssembly = window.WXWebAssembly || window.WebAssembly;

              function cocosBundleAlias(path) {
                var match = path.match(/^src\/(?:scripts|bundle-scripts)\/([^\/]+)(?:\/index\.js)?${'$'}/);
                return match ? 'assets/' + match[1] + '/index.js' : null;
              }
              function cocosAssetAlias(path) {
                var clean = path.replace(/^\.\//, '');
                var match = clean.match(/^(internal|resources|main|start-scene|share-images)\/(.+)/);
                return match ? 'assets/' + match[1] + '/' + match[2] : null;
              }
              function cocosModuleCandidates(path) {
                var candidates = [path];
                [cocosBundleAlias(path), cocosAssetAlias(path)].forEach(function (alias) {
                  if (alias && candidates.indexOf(alias) < 0) candidates.push(alias);
                });
                return candidates;
              }
              function wrapCocosRequire(fn) {
                if (!fn || fn.__webSandboxWrappedCocosRequire) return fn;
                var wrapped = function (moduleName) {
                  var candidates = cocosModuleCandidates(moduleName);
                  var lastError = null;
                  for (var i = 0; i < candidates.length; i++) {
                    try {
                      return fn.call(this, candidates[i]);
                    } catch (e) {
                      lastError = e;
                      if (!e || !/cannot find module|Cannot load module/i.test(String(e.message || e))) throw e;
                    }
                  }
                  throw lastError;
                };
                wrapped.__webSandboxWrappedCocosRequire = true;
                return wrapped;
              }
              function syncGameGlobalToWindow() {
                var virtualWindow = gameGlobal.window && gameGlobal.window !== gameGlobal ? gameGlobal.window : null;
                if (virtualWindow && virtualWindow.__cocos_require__) {
                  virtualWindow.__cocos_require__ = wrapCocosRequire(virtualWindow.__cocos_require__);
                  gameGlobal.__cocos_require__ = virtualWindow.__cocos_require__;
                }
                if (gameGlobal.__cocos_require__) {
                  gameGlobal.__cocos_require__ = wrapCocosRequire(gameGlobal.__cocos_require__);
                }
                [
                  '__globalAdapter',
                  '__cocos_require__',
                  'canvas',
                  'screencanvas',
                  'KSWebAssembly',
                  'WXWebAssembly',
                  'cc',
                  'CocosEngine',
                  'fsUtils',
                  'DOMParser',
                  'System',
                  '__wxRequire'
                ].forEach(function (name) {
                  if (gameGlobal[name] !== undefined) window[name] = gameGlobal[name];
                });
                patchCocosAssetManager();
              }

              function cocosBundleNameAlias(name) {
                if (typeof name !== 'string') return name;
                return name.replace(/^Texture\/UIPrefab\//, '');
              }
              function patchCocosAssetManager() {
                var manager = window.cc && window.cc.assetManager;
                if (!manager || manager.__webSandboxBundleAliasPatched) return;
                var nativeLoadBundle = manager.loadBundle;
                var nativeGetBundle = manager.getBundle;
                if (typeof nativeLoadBundle !== 'function') return;
                manager.__webSandboxBundleAliasPatched = true;
                manager.loadBundle = function (name, options, onComplete) {
                  return nativeLoadBundle.call(this, cocosBundleNameAlias(name), options, onComplete);
                };
                if (typeof nativeGetBundle === 'function') {
                  manager.getBundle = function (name) {
                    return nativeGetBundle.call(this, cocosBundleNameAlias(name));
                  };
                }
              }

              function makeWindowPropertyWritable(name, fallback) {
                try {
                  Object.defineProperty(window, name, {
                    value: window[name] || fallback,
                    writable: true,
                    configurable: true
                  });
                } catch (e) {}
              }
              [
                'canvas',
                'document',
                'navigator',
                'screen',
                'performance',
                'parent',
                'top',
                'self',
                'XMLHttpRequest',
                'WebSocket',
                'Image',
                'ImageBitmap',
                'Audio',
                'FileReader',
                'HTMLElement',
                'HTMLImageElement',
                'HTMLCanvasElement',
                'HTMLMediaElement',
                'HTMLAudioElement',
                'HTMLVideoElement',
                'WebGLRenderingContext',
                'TouchEvent',
                'MouseEvent',
                'DeviceMotionEvent',
                'localStorage',
                'location'
              ].forEach(function (name) { makeWindowPropertyWritable(name, window[name]); });

              if (window.EventTarget && window.Event && !EventTarget.prototype.__webSandboxDispatchPatched) {
                var nativeDispatchEvent = EventTarget.prototype.dispatchEvent;
                EventTarget.prototype.__webSandboxDispatchPatched = true;
                EventTarget.prototype.dispatchEvent = function (event) {
                  if (!(event instanceof Event)) {
                    var original = event || {};
                    var type = original.type || original.name || 'message';
                    var domEvent;
                    try {
                      domEvent = new CustomEvent(type, { bubbles: true, cancelable: true, detail: original });
                    } catch (e) {
                      domEvent = document.createEvent('Event');
                      domEvent.initEvent(type, true, true);
                      domEvent.detail = original;
                    }
                    if (typeof original === 'object') {
                      Object.keys(original).forEach(function (key) {
                        if (!(key in domEvent)) {
                          try {
                            Object.defineProperty(domEvent, key, { value: original[key], configurable: true });
                          } catch (e) {}
                        }
                      });
                    }
                    event = domEvent;
                  }
                  return nativeDispatchEvent.call(this, event);
                };
              }

              function noop() {}
              function asyncOk(value) {
                return {
                  then: function (resolve) {
                    if (resolve) resolve(value || {});
                    return { catch: noop };
                  },
                  catch: noop
                };
              }
              function invokeMiniCallback(callback, value) {
                if (callback) {
                  setTimeout(function () { callback(value || {}); }, 0);
                }
              }
              function eventHandle() {
                return {
                  onClose: noop,
                  offClose: noop,
                  onError: noop,
                  offError: noop,
                  onLoad: noop,
                  offLoad: noop,
                  show: function () { return asyncOk(); },
                  load: function () { return asyncOk(); },
                  hide: noop,
                  destroy: noop
                };
              }
              function skippedAdHandle() {
                var closeCallbacks = [];
                var loadCallbacks = [];
                var errorCallbacks = [];
                function addCallback(list) {
                  return function (cb) {
                    if (cb && list.indexOf(cb) < 0) list.push(cb);
                  };
                }
                function removeCallback(list) {
                  return function (cb) {
                    var index = list.indexOf(cb);
                    if (index >= 0) list.splice(index, 1);
                  };
                }
                function emit(list, payload) {
                  list.slice().forEach(function (cb) {
                    setTimeout(function () { cb(payload || {}); }, 0);
                  });
                }
                return {
                  onLoad: addCallback(loadCallbacks),
                  offLoad: removeCallback(loadCallbacks),
                  onError: addCallback(errorCallbacks),
                  offError: removeCallback(errorCallbacks),
                  onClose: addCallback(closeCallbacks),
                  offClose: removeCallback(closeCallbacks),
                  load: function () {
                    emit(loadCallbacks, {});
                    return asyncOk({});
                  },
                  show: function () {
                    var payload = { isEnded: true };
                    emit(closeCallbacks, payload);
                    return asyncOk(payload);
                  },
                  hide: noop,
                  destroy: noop
                };
              }
              function installNonOwnWindowApi(name, api) {
                try {
                  if (Object.prototype.hasOwnProperty.call(window, name)) delete window[name];
                } catch (e) {}
                try {
                  Object.defineProperty(Object.getPrototypeOf(window), name, {
                    configurable: true,
                    get: function () { return api; }
                  });
                } catch (e) {
                  try { window[name] = api; } catch (ignored) {}
                }
              }
              function bindMediaEvent(target, eventName) {
                return function (cb) {
                  if (cb) target.addEventListener(eventName, cb);
                };
              }
              function unbindMediaEvent(target, eventName) {
                return function (cb) {
                  if (cb) target.removeEventListener(eventName, cb);
                };
              }
              function failUnavailable(options, message) {
                var payload = { errMsg: message || 'api unavailable in web sandbox' };
                options && options.fail && options.fail(payload);
                options && options.complete && options.complete(payload);
                return asyncOk(payload);
              }
              function loadSubpackageScript(name) {
                if (!name) return;
                var loader = window.__wxRequire || window.require;
                if (!loader) return;
                try {
                  loader('./subpackages/' + name + '/game.js');
                } catch (e) {
                  loader('subpackages/' + name + '/game.js');
                }
              }
              function systemInfo() {
                return {
                  brand: 'Android',
                  model: 'Android WebView',
                  platform: 'android',
                  system: 'Android',
                  language: navigator.language || 'en',
                  version: 'web',
                  SDKVersion: 'web',
                  pixelRatio: window.devicePixelRatio || 1,
                  screenWidth: window.innerWidth,
                  screenHeight: window.innerHeight,
                  windowWidth: window.innerWidth,
                  windowHeight: window.innerHeight,
                  safeArea: {
                    left: 0,
                    top: 0,
                    right: window.innerWidth,
                    bottom: window.innerHeight,
                    width: window.innerWidth,
                    height: window.innerHeight
                  }
                };
              }
              function installExternalRequestStub() {
                if (!NativeXMLHttpRequest || NativeXMLHttpRequest.__webSandboxWrapped) return;
                function SandboxXMLHttpRequest() {
                  this._native = null;
                  this._headers = {};
                  this._listeners = {};
                  this._url = '';
                  this._method = 'GET';
                  this.readyState = 0;
                  this.status = 0;
                  this.statusText = '';
                  this.response = '';
                  this.responseText = '';
                  this.responseURL = '';
                  this.onreadystatechange = null;
                  this.onload = null;
                  this.onerror = null;
                  this.ontimeout = null;
                  this._responseType = '';
                  this._timeout = 0;
                  this._withCredentials = false;
                }
                SandboxXMLHttpRequest.__webSandboxWrapped = true;
                SandboxXMLHttpRequest.prototype.open = function (method, url, async, user, password) {
                  this._method = method || 'GET';
                  this._url = String(url || '');
                  if (/^https?:\/\//i.test(this._url)) {
                    this.readyState = 1;
                    this.status = 0;
                    this.responseURL = this._url;
                    this._native = null;
                    notifyXhr(this, 'readystatechange');
                    return;
                  }
                  this._native = new NativeXMLHttpRequest();
                  bindNativeXhr(this, this._native);
                  this._native.responseType = this._responseType;
                  this._native.timeout = this._timeout;
                  this._native.withCredentials = this._withCredentials;
                  return this._native.open(method, url, async !== false, user, password);
                };
                Object.defineProperty(SandboxXMLHttpRequest.prototype, 'responseType', {
                  get: function () { return this._native ? this._native.responseType : this._responseType; },
                  set: function (value) {
                    this._responseType = value || '';
                    if (this._native) this._native.responseType = this._responseType;
                  }
                });
                Object.defineProperty(SandboxXMLHttpRequest.prototype, 'timeout', {
                  get: function () { return this._native ? this._native.timeout : this._timeout; },
                  set: function (value) {
                    this._timeout = value || 0;
                    if (this._native) this._native.timeout = this._timeout;
                  }
                });
                Object.defineProperty(SandboxXMLHttpRequest.prototype, 'withCredentials', {
                  get: function () { return this._native ? this._native.withCredentials : this._withCredentials; },
                  set: function (value) {
                    this._withCredentials = !!value;
                    if (this._native) this._native.withCredentials = this._withCredentials;
                  }
                });
                SandboxXMLHttpRequest.prototype.setRequestHeader = function (name, value) {
                  if (this._native) return this._native.setRequestHeader(name, value);
                  this._headers[name] = value;
                };
                SandboxXMLHttpRequest.prototype.getResponseHeader = function (name) {
                  return this._native ? this._native.getResponseHeader(name) : null;
                };
                SandboxXMLHttpRequest.prototype.getAllResponseHeaders = function () {
                  return this._native ? this._native.getAllResponseHeaders() : '';
                };
                SandboxXMLHttpRequest.prototype.overrideMimeType = function (type) {
                  if (this._native && this._native.overrideMimeType) this._native.overrideMimeType(type);
                };
                SandboxXMLHttpRequest.prototype.addEventListener = function (name, cb) {
                  if (!this._listeners[name]) this._listeners[name] = [];
                  this._listeners[name].push(cb);
                  if (this._native) this._native.addEventListener(name, cb);
                };
                SandboxXMLHttpRequest.prototype.removeEventListener = function (name, cb) {
                  var list = this._listeners[name] || [];
                  var index = list.indexOf(cb);
                  if (index >= 0) list.splice(index, 1);
                  if (this._native) this._native.removeEventListener(name, cb);
                };
                SandboxXMLHttpRequest.prototype.send = function (body) {
                  if (this._native) return this._native.send(body);
                  var xhr = this;
                  setTimeout(function () {
                    xhr.readyState = 4;
                    xhr.status = 200;
                    xhr.statusText = 'OK';
                    var payload = {
                      Result: 0,
                      code: 0,
                      errCode: 0,
                      success: true,
                      UserID: 'web-sandbox',
                      userId: 'web-sandbox',
                      openid: 'web-sandbox',
                      token: 'web-sandbox',
                      data: {}
                    };
                    var text = JSON.stringify(payload);
                    xhr.response = xhr._responseType === 'json' ? payload : text;
                    xhr.responseText = text;
                    notifyXhr(xhr, 'readystatechange');
                    notifyXhr(xhr, 'load');
                    notifyXhr(xhr, 'loadend');
                  }, 0);
                };
                SandboxXMLHttpRequest.prototype.abort = function () {
                  if (this._native) return this._native.abort();
                  this.readyState = 0;
                  notifyXhr(this, 'abort');
                  notifyXhr(this, 'loadend');
                };
                function bindNativeXhr(wrapper, nativeXhr) {
                  nativeXhr.onreadystatechange = function () {
                    copyNativeXhrState(wrapper, nativeXhr);
                    if (wrapper.onreadystatechange) wrapper.onreadystatechange.call(wrapper);
                  };
                  ['load', 'error', 'timeout', 'abort', 'loadend', 'progress'].forEach(function (name) {
                    nativeXhr.addEventListener(name, function (event) {
                      copyNativeXhrState(wrapper, nativeXhr);
                      if (name === 'load' && wrapper.onload) wrapper.onload.call(wrapper, event);
                      if (name === 'error' && wrapper.onerror) wrapper.onerror.call(wrapper, event);
                      if (name === 'timeout' && wrapper.ontimeout) wrapper.ontimeout.call(wrapper, event);
                    });
                  });
                }
                function copyNativeXhrState(wrapper, nativeXhr) {
                  wrapper.readyState = nativeXhr.readyState;
                  wrapper.status = nativeXhr.status;
                  wrapper.statusText = nativeXhr.statusText;
                  wrapper.response = nativeXhr.response;
                  try { wrapper.responseText = nativeXhr.responseText; } catch (e) { wrapper.responseText = ''; }
                  wrapper.responseURL = nativeXhr.responseURL;
                }
                function notifyXhr(xhr, name) {
                  var event = { type: name, target: xhr, currentTarget: xhr };
                  if (name === 'readystatechange' && xhr.onreadystatechange) xhr.onreadystatechange.call(xhr, event);
                  if (name === 'load' && xhr.onload) xhr.onload.call(xhr, event);
                  if (name === 'error' && xhr.onerror) xhr.onerror.call(xhr, event);
                  if (name === 'timeout' && xhr.ontimeout) xhr.ontimeout.call(xhr, event);
                  (xhr._listeners[name] || []).slice().forEach(function (cb) { cb.call(xhr, event); });
                }
                window.XMLHttpRequest = SandboxXMLHttpRequest;
                gameGlobal.XMLHttpRequest = SandboxXMLHttpRequest;
              }
              installExternalRequestStub();
              function tryLoadLocalText(path) {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', path, false);
                try { xhr.send(null); } catch (e) { return null; }
                if (xhr.status >= 400 || (xhr.status === 0 && !xhr.responseText)) {
                  return null;
                }
                return xhr.responseText;
              }
              function loadLocalText(path) {
                var candidates = cocosModuleCandidates(path);
                for (var i = 0; i < candidates.length; i++) {
                  var text = tryLoadLocalText(candidates[i]);
                  if (text !== null) return text;
                }
                throw new Error('file not found: ' + path);
              }
              function fileSystemManager() {
                return {
                  readFile: function (options) {
                    try {
                      invokeMiniCallback(options.success, { data: loadLocalText(options.filePath) });
                    } catch (e) {
                      invokeMiniCallback(options.fail, { errMsg: e.message });
                    }
                  },
                  readFileSync: function (path) { return loadLocalText(path); },
                  access: function (options) {
                    try {
                      loadLocalText(options.path);
                      invokeMiniCallback(options.success, {});
                    } catch (e) {
                      invokeMiniCallback(options.fail, { errMsg: e.message });
                    }
                  },
                  accessSync: function (options) {
                    var path = typeof options === 'string' ? options : options.path;
                    loadLocalText(path);
                    return null;
                  },
                  readdir: function (options) {
                    invokeMiniCallback(options.fail, { errMsg: 'readdir is unavailable in web sandbox' });
                  },
                  mkdirSync: noop,
                  rmdirSync: noop,
                  unlink: function (options) { invokeMiniCallback(options.success, {}); },
                  copyFile: function (options) { invokeMiniCallback(options.fail, { errMsg: 'copyFile is unavailable in web sandbox' }); },
                  writeFile: function (options) { invokeMiniCallback(options.success, {}); },
                  writeFileSync: function () { return null; },
                  unzip: function (options) { invokeMiniCallback(options.fail, { errMsg: 'unzip is unavailable in web sandbox' }); }
                };
              }
              var miniApi = window.ks || window.wx || {};
              Object.assign(miniApi, {
                env: miniApi.env || { USER_DATA_PATH: '' },
                canIUse: miniApi.canIUse || function () { return false; },
                getFileSystemManager: miniApi.getFileSystemManager || fileSystemManager,
                getSystemInfoSync: miniApi.getSystemInfoSync || systemInfo,
                getSystemInfo: miniApi.getSystemInfo || function (options) {
                  var info = systemInfo();
                  invokeMiniCallback(options && options.success, info);
                  invokeMiniCallback(options && options.complete, info);
                  return asyncOk(info);
                },
                getMenuButtonBoundingClientRect: miniApi.getMenuButtonBoundingClientRect || function () {
                  return { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
                },
                getUpdateManager: miniApi.getUpdateManager || function () {
                  return { onCheckForUpdate: noop, onUpdateReady: noop, onUpdateFailed: noop, applyUpdate: noop };
                },
                getLaunchOptionsSync: miniApi.getLaunchOptionsSync || function () { return { scene: 1000, query: {} }; },
                getLaunchScene: miniApi.getLaunchScene || function () { return '1000'; },
                updateShareMenu: miniApi.updateShareMenu || function (options) {
                  options && options.success && options.success({});
                  options && options.complete && options.complete({});
                  return asyncOk();
                },
                getNetworkType: miniApi.getNetworkType || function (options) {
                  var payload = { networkType: 'wifi' };
                  invokeMiniCallback(options && options.success, payload);
                  invokeMiniCallback(options && options.complete, payload);
                  return asyncOk(payload);
                },
                login: miniApi.login || function (options) {
                  var payload = { code: 'web-sandbox' };
                  invokeMiniCallback(options && options.success, payload);
                  invokeMiniCallback(options && options.complete, payload);
                  return asyncOk(payload);
                },
                authorize: miniApi.authorize || function (options) {
                  var payload = {};
                  invokeMiniCallback(options && options.success, payload);
                  invokeMiniCallback(options && options.complete, payload);
                  return asyncOk(payload);
                },
                getUserInfo: miniApi.getUserInfo || function (options) {
                  var payload = { userInfo: { nickName: 'Player', avatarUrl: '' } };
                  invokeMiniCallback(options && options.success, payload);
                  invokeMiniCallback(options && options.complete, payload);
                  return asyncOk(payload);
                },
                onNetworkStatusChange: miniApi.onNetworkStatusChange || noop,
                onShow: miniApi.onShow || function (cb) { if (cb) setTimeout(function () { cb({ scene: 1000, query: {} }); }, 0); },
                offShow: miniApi.offShow || noop,
                onHide: miniApi.onHide || noop,
                offHide: miniApi.offHide || noop,
                onError: noop,
                offError: miniApi.offError || noop,
                onMessage: miniApi.onMessage || noop,
                postMessage: miniApi.postMessage || noop,
                getOpenDataContext: miniApi.getOpenDataContext || function () { return { postMessage: noop, canvas: canvas }; },
                getSharedCanvas: miniApi.getSharedCanvas || function () { return canvas; },
                createCanvas: miniApi.createCanvas || function () { return document.createElement('canvas'); },
                createImage: miniApi.createImage || function () { return new Image(); },
                loadFont: miniApi.loadFont || function () { return 'Arial'; },
                createInnerAudioContext: miniApi.createInnerAudioContext || function () {
                  var audio = new Audio();
                  audio.destroy = function () { audio.pause(); audio.src = ''; };
                  audio.onPlay = bindMediaEvent(audio, 'play');
                  audio.offPlay = unbindMediaEvent(audio, 'play');
                  audio.onPause = bindMediaEvent(audio, 'pause');
                  audio.offPause = unbindMediaEvent(audio, 'pause');
                  audio.onStop = bindMediaEvent(audio, 'stop');
                  audio.offStop = unbindMediaEvent(audio, 'stop');
                  audio.onSeeked = bindMediaEvent(audio, 'seeked');
                  audio.offSeeked = unbindMediaEvent(audio, 'seeked');
                  audio.onEnded = bindMediaEvent(audio, 'ended');
                  audio.offEnded = unbindMediaEvent(audio, 'ended');
                  audio.onCanplay = bindMediaEvent(audio, 'canplay');
                  audio.offCanplay = unbindMediaEvent(audio, 'canplay');
                  audio.onError = bindMediaEvent(audio, 'error');
                  audio.offError = unbindMediaEvent(audio, 'error');
                  audio.stop = function () {
                    audio.pause();
                    audio.currentTime = 0;
                    audio.dispatchEvent(new Event('stop'));
                    return asyncOk();
                  };
                  audio.seek = function (time) { audio.currentTime = time || 0; return asyncOk(); };
                  return audio;
                },
                createVideo: miniApi.createVideo || function () {
                  var video = document.createElement('video');
                  video.destroy = function () { video.remove(); };
                  video.onPlay = bindMediaEvent(video, 'play');
                  video.offPlay = unbindMediaEvent(video, 'play');
                  video.onPause = bindMediaEvent(video, 'pause');
                  video.offPause = unbindMediaEvent(video, 'pause');
                  video.onEnded = bindMediaEvent(video, 'ended');
                  video.offEnded = unbindMediaEvent(video, 'ended');
                  video.onCanplay = bindMediaEvent(video, 'canplay');
                  video.offCanplay = unbindMediaEvent(video, 'canplay');
                  video.onError = bindMediaEvent(video, 'error');
                  video.offError = unbindMediaEvent(video, 'error');
                  video.onWaiting = bindMediaEvent(video, 'waiting');
                  video.offWaiting = unbindMediaEvent(video, 'waiting');
                  video.onTimeUpdate = function (cb) {
                    video.addEventListener('timeupdate', function () {
                      cb({ position: video.currentTime, duration: video.duration || 0 });
                    });
                  };
                  video.offTimeUpdate = unbindMediaEvent(video, 'timeupdate');
                  video.requestFullScreen = noop;
                  video.exitFullScreen = noop;
                  video.stop = function () { video.pause(); video.currentTime = 0; return asyncOk(); };
                  video.show = noop;
                  video.hide = noop;
                  return video;
                },
                createRewardedVideoAd: skippedAdHandle,
                createInterstitialAd: skippedAdHandle,
                createBannerAd: skippedAdHandle,
                createCustomAd: skippedAdHandle,
                createGridAd: skippedAdHandle,
                showRewardedVideoAd: function (options) {
                  var payload = { isEnded: true };
                  options && options.success && options.success(payload);
                  options && options.complete && options.complete(payload);
                  return asyncOk(payload);
                },
                showInterstitialAd: function (options) {
                  var payload = {};
                  options && options.success && options.success(payload);
                  options && options.complete && options.complete(payload);
                  return asyncOk(payload);
                },
                request: miniApi.request || function (options) {
                  var payload = {
                    statusCode: 200,
                    data: {
                      Result: 0,
                      code: 0,
                      errCode: 0,
                      success: true,
                      UserID: 'web-sandbox',
                      userId: 'web-sandbox',
                      openid: 'web-sandbox',
                      token: 'web-sandbox',
                      data: {}
                    }
                  };
                  options && options.success && options.success(payload);
                  options && options.complete && options.complete(payload);
                  return asyncOk(payload);
                },
                downloadFile: miniApi.downloadFile || function (options) {
                  options && options.fail && options.fail({ errMsg: 'downloadFile is unavailable in web sandbox' });
                  return { onProgressUpdate: noop };
                },
                loadSubpackage: miniApi.loadSubpackage || function (options) {
                  setTimeout(function () {
                    try {
                      loadSubpackageScript(options && (options.name || options.root));
                      options && options.success && options.success({});
                      options && options.complete && options.complete({});
                    } catch (e) {
                      var payload = { errMsg: e.message || String(e) };
                      options && options.fail && options.fail(payload);
                      options && options.complete && options.complete(payload);
                    }
                  }, 0);
                  return { onProgressUpdate: noop };
                },
                navigateToMiniProgram: miniApi.navigateToMiniProgram || function (options) {
                  return asyncOk({});
                },
                navigateBackMiniProgram: miniApi.navigateBackMiniProgram || function (options) {
                  return asyncOk({});
                },
                openEmbeddedMiniProgram: miniApi.openEmbeddedMiniProgram || function (options) {
                  return asyncOk({});
                },
                exitMiniProgram: miniApi.exitMiniProgram || function (options) {
                  return asyncOk({});
                },
                openCustomerServiceConversation: miniApi.openCustomerServiceConversation || function (options) {
                  return asyncOk({});
                },
                trackGameData: miniApi.trackGameData || noop,
                reportEvent: miniApi.reportEvent || noop,
                reportAnalytics: miniApi.reportAnalytics || noop,
                onAccelerometerChange: miniApi.onAccelerometerChange || noop,
                startAccelerometer: miniApi.startAccelerometer || noop,
                stopAccelerometer: miniApi.stopAccelerometer || noop,
                showToast: miniApi.showToast || function (options) {
                  options && options.success && options.success({});
                  options && options.complete && options.complete({});
                  return asyncOk();
                },
                hideToast: miniApi.hideToast || noop,
                showLoading: miniApi.showLoading || function (options) {
                  options && options.success && options.success({});
                  options && options.complete && options.complete({});
                  return asyncOk();
                },
                hideLoading: miniApi.hideLoading || noop,
                showModal: miniApi.showModal || function (options) {
                  var payload = { confirm: false, cancel: true };
                  options && options.success && options.success(payload);
                  options && options.complete && options.complete(payload);
                  return asyncOk(payload);
                },
                showShareMenu: miniApi.showShareMenu || noop,
                hideShareMenu: miniApi.hideShareMenu || noop,
                shareAppMessage: miniApi.shareAppMessage || noop,
                onShareAppMessage: miniApi.onShareAppMessage || noop,
                offShareAppMessage: miniApi.offShareAppMessage || noop,
                setPreferredFramesPerSecond: miniApi.setPreferredFramesPerSecond || noop,
                onTouchStart: miniApi.onTouchStart || function (cb) { canvas.addEventListener('touchstart', cb, { passive: false }); },
                onTouchMove: miniApi.onTouchMove || function (cb) { canvas.addEventListener('touchmove', cb, { passive: false }); },
                onTouchEnd: miniApi.onTouchEnd || function (cb) { canvas.addEventListener('touchend', cb, { passive: false }); },
                onTouchCancel: miniApi.onTouchCancel || function (cb) { canvas.addEventListener('touchcancel', cb, { passive: false }); },
                showKeyboard: miniApi.showKeyboard || noop,
                hideKeyboard: miniApi.hideKeyboard || noop,
                updateKeyboard: miniApi.updateKeyboard || noop,
                onKeyboardInput: miniApi.onKeyboardInput || noop,
                onKeyboardConfirm: miniApi.onKeyboardConfirm || noop,
                onKeyboardComplete: miniApi.onKeyboardComplete || noop,
                offKeyboardInput: miniApi.offKeyboardInput || noop,
                offKeyboardConfirm: miniApi.offKeyboardConfirm || noop,
                offKeyboardComplete: miniApi.offKeyboardComplete || noop,
                vibrateShort: miniApi.vibrateShort || noop,
                vibrateLong: miniApi.vibrateLong || noop,
                setStorageSync: miniApi.setStorageSync || function (key, value) { localStorage.setItem(key, JSON.stringify(value)); },
                getStorageSync: miniApi.getStorageSync || function (key) {
                  var value = localStorage.getItem(key);
                  try { return JSON.parse(value); } catch (e) { return value; }
                },
                removeStorageSync: miniApi.removeStorageSync || function (key) { localStorage.removeItem(key); },
                clearStorageSync: miniApi.clearStorageSync || function () { localStorage.clear(); }
              });
              window.GameGlobal.wx = miniApi;
              window.GameGlobal.ks = miniApi;
              window.GameGlobal.tt = miniApi;
              window.GameGlobal.qg = miniApi;
              installNonOwnWindowApi('wx', miniApi);
              installNonOwnWindowApi('ks', miniApi);
              installNonOwnWindowApi('tt', miniApi);
              installNonOwnWindowApi('qg', miniApi);
              syncGameGlobalToWindow();

              var moduleCache = {};
              var textCache = {};
              function normalize(path) {
                var parts = [];
                path.split('/').forEach(function (part) {
                  if (!part || part === '.') return;
                  if (part === '..') parts.pop();
                  else parts.push(part);
                });
                return parts.join('/');
              }
              function dirname(path) {
                var index = path.lastIndexOf('/');
                return index >= 0 ? path.slice(0, index) : '';
              }
              function resolve(request, parentDir) {
                var path = request;
                if (path.charAt(0) === '.') path = (parentDir ? parentDir + '/' : '') + path;
                path = normalize(path);
                var candidates = [];
                cocosModuleCandidates(path).forEach(function (candidate) {
                  if (hasFileExtension(candidate)) {
                    candidates.push(candidate);
                  } else {
                    candidates.push(candidate + '.js', candidate + '.json', candidate + '/index.js', candidate);
                  }
                });
                for (var i = 0; i < candidates.length; i++) {
                  var url = candidates[i];
                  var text = tryReadText(url);
                  if (text !== null) {
                    textCache[url] = text;
                    return url;
                  }
                }
                return path;
              }
              function tryReadText(path) {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', path, false);
                try { xhr.send(null); } catch (e) { return null; }
                if (xhr.status >= 400) return null;
                if (xhr.status === 0 && !xhr.responseText) return null;
                return xhr.responseText;
              }
              function readText(path) {
                if (Object.prototype.hasOwnProperty.call(textCache, path)) return textCache[path];
                var text = tryReadText(path);
                if (text === null) throw new Error('Cannot load module ' + path);
                textCache[path] = text;
                return text;
              }
              function hasFileExtension(path) {
                return /\.[^\/.]+${'$'}/.test(path);
              }
              function requireModule(request, parentDir) {
                var resolved = resolve(request, parentDir || '');
                if (moduleCache[resolved]) return moduleCache[resolved].exports;
                var text = readText(resolved);
                var module = { exports: {} };
                moduleCache[resolved] = module;
                if (/\.json(${'$'}|\?)/.test(resolved)) {
                  module.exports = JSON.parse(text);
                  return module.exports;
                }
                var currentDir = dirname(resolved);
                var localRequire = function (child) { return requireModule(child, currentDir); };
                localRequire.resolve = function (child) { return resolve(child, currentDir); };
                var wrapped = new Function(
                  'require',
                  'module',
                  'exports',
                  '__filename',
                  '__dirname',
                  'runtimeGlobal',
                  'with (runtimeGlobal) {\n' + text + '\n}\n//# sourceURL=' + resolved
                );
                wrapped.call(gameGlobal, localRequire, module, module.exports, resolved, currentDir, gameGlobal);
                syncGameGlobalToWindow();
                return module.exports;
              }
              window.require = function (request) { return requireModule(request, ''); };
              window.__cocos_require__ = window.require;
            })();
        """.trimIndent()
    }

    private fun escapeJsString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
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
        private const val SANDBOX_READY_FILE = ".web-sandbox-ready"
        private val BLOCKED_WEB_RESOURCE_MARKERS = listOf(
            "adservice",
            "adserver",
            "adunit",
            "analytics",
            "app-measurement",
            "doubleclick",
            "gdt",
            "googleads",
            "googlesyndication",
            "ksad",
            "pangolin",
            "pangle",
            "track",
            "umeng",
        )
        private val COCOS_BUNDLE_ALIASES = listOf(
            "internal",
            "resources",
            "main",
            "start-scene",
            "share-images",
        )

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
        if (shouldRecordSession && startTime > 0L && ::gamePath.isInitialized && gamePath.isNotBlank()) {
            PlayerProgressRepository.recordSession(this, gamePath, System.currentTimeMillis() - startTime)
        }
        webView.destroy()
    }

    override fun onResume() {
        super.onResume()
        GameOrientationLock.apply(this, gameOrientation)
    }
}
