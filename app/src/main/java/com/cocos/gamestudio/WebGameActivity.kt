package com.cocos.gamestudio

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
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
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    TAG,
                    "console/${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}",
                )
                return true
            }
        }
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

        applyPackageOrientation(baseDir)
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
            return sandboxRoot
        }

        return if (unpackZip(source, sandboxRoot)) sandboxRoot else null
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
            Log.e(TAG, "Failed unpacking zip game package", e)
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

        requestedOrientation = when {
            content.contains("\"deviceOrientation\"", ignoreCase = true) &&
                content.contains("\"portrait\"", ignoreCase = true) ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            content.contains("\"deviceOrientation\"", ignoreCase = true) &&
                content.contains("\"landscape\"", ignoreCase = true) ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> requestedOrientation
        }
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

              function syncGameGlobalToWindow() {
                [
                  '__globalAdapter',
                  'ks',
                  'wx',
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
              function loadLocalText(path) {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', path, false);
                try { xhr.send(null); } catch (e) { throw e; }
                if (xhr.status >= 400 || (xhr.status === 0 && !xhr.responseText)) {
                  throw new Error('file not found: ' + path);
                }
                return xhr.responseText;
              }
              function fileSystemManager() {
                return {
                  readFile: function (options) {
                    try {
                      options.success && options.success({ data: loadLocalText(options.filePath) });
                    } catch (e) {
                      options.fail && options.fail({ errMsg: e.message });
                    }
                  },
                  readFileSync: function (path) { return loadLocalText(path); },
                  access: function (options) {
                    try {
                      loadLocalText(options.path);
                      options.success && options.success({});
                    } catch (e) {
                      options.fail && options.fail({ errMsg: e.message });
                    }
                  },
                  accessSync: function (options) {
                    var path = typeof options === 'string' ? options : options.path;
                    loadLocalText(path);
                    return null;
                  },
                  readdir: function (options) {
                    options.fail && options.fail({ errMsg: 'readdir is unavailable in web sandbox' });
                  },
                  mkdirSync: noop,
                  rmdirSync: noop,
                  unlink: function (options) { options.success && options.success({}); },
                  copyFile: function (options) { options.fail && options.fail({ errMsg: 'copyFile is unavailable in web sandbox' }); },
                  writeFile: function (options) { options.success && options.success({}); },
                  writeFileSync: function () { return null; },
                  unzip: function (options) { options.fail && options.fail({ errMsg: 'unzip is unavailable in web sandbox' }); }
                };
              }
              var miniApi = window.ks || window.wx || {};
              Object.assign(miniApi, {
                env: miniApi.env || { USER_DATA_PATH: '' },
                canIUse: miniApi.canIUse || function () { return false; },
                getFileSystemManager: miniApi.getFileSystemManager || fileSystemManager,
                getSystemInfoSync: miniApi.getSystemInfoSync || systemInfo,
                getLaunchOptionsSync: miniApi.getLaunchOptionsSync || function () { return { scene: 1000, query: {} }; },
                onNetworkStatusChange: miniApi.onNetworkStatusChange || noop,
                onShow: miniApi.onShow || function (cb) { if (cb) setTimeout(function () { cb({ scene: 1000, query: {} }); }, 0); },
                offShow: miniApi.offShow || noop,
                onHide: miniApi.onHide || noop,
                offHide: miniApi.offHide || noop,
                onError: miniApi.onError || function (cb) {
                  window.addEventListener('error', function (event) { cb && cb(event.error || event.message); });
                },
                offError: miniApi.offError || noop,
                onMessage: miniApi.onMessage || noop,
                getOpenDataContext: miniApi.getOpenDataContext || function () { return { postMessage: noop, canvas: canvas }; },
                getSharedCanvas: miniApi.getSharedCanvas || function () { return canvas; },
                createCanvas: miniApi.createCanvas || function () { return document.createElement('canvas'); },
                createImage: miniApi.createImage || function () { return new Image(); },
                createInnerAudioContext: miniApi.createInnerAudioContext || function () {
                  var audio = new Audio();
                  audio.destroy = function () { audio.pause(); audio.src = ''; };
                  audio.onEnded = function (cb) { audio.addEventListener('ended', cb); };
                  audio.offEnded = function (cb) { audio.removeEventListener('ended', cb); };
                  audio.seek = function (time) { audio.currentTime = time || 0; };
                  return audio;
                },
                createVideo: miniApi.createVideo || function () {
                  var video = document.createElement('video');
                  video.destroy = function () { video.remove(); };
                  video.onPlay = function (cb) { video.addEventListener('play', cb); };
                  video.offPlay = function (cb) { video.removeEventListener('play', cb); };
                  video.onPause = function (cb) { video.addEventListener('pause', cb); };
                  video.offPause = function (cb) { video.removeEventListener('pause', cb); };
                  video.onEnded = function (cb) { video.addEventListener('ended', cb); };
                  video.offEnded = function (cb) { video.removeEventListener('ended', cb); };
                  video.onTimeUpdate = function (cb) {
                    video.addEventListener('timeupdate', function () {
                      cb({ position: video.currentTime, duration: video.duration || 0 });
                    });
                  };
                  video.stop = function () { video.pause(); video.currentTime = 0; return asyncOk(); };
                  video.show = noop;
                  video.hide = noop;
                  return video;
                },
                createRewardedVideoAd: miniApi.createRewardedVideoAd || eventHandle,
                createInterstitialAd: miniApi.createInterstitialAd || eventHandle,
                createBannerAd: miniApi.createBannerAd || eventHandle,
                createCustomAd: miniApi.createCustomAd || eventHandle,
                createGridAd: miniApi.createGridAd || eventHandle,
                request: miniApi.request || function (options) {
                  options && options.fail && options.fail({ errMsg: 'request is unavailable in web sandbox' });
                },
                downloadFile: miniApi.downloadFile || function (options) {
                  options && options.fail && options.fail({ errMsg: 'downloadFile is unavailable in web sandbox' });
                  return { onProgressUpdate: noop };
                },
                loadSubpackage: miniApi.loadSubpackage || function (options) {
                  options && options.success && options.success({});
                  return { onProgressUpdate: noop };
                },
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
              window.ks = miniApi;
              window.wx = miniApi;
              window.GameGlobal.wx = miniApi;
              window.GameGlobal.ks = miniApi;
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
                var candidates = [path, path + '.js', path + '.json', path + '/index.js'];
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
