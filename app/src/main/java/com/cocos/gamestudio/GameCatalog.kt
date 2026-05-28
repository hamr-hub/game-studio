package com.cocos.gamestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class GameEntry(
    val id: String,
    val assetName: String,
    val file: File,
    val displayName: String,
    val description: String,
    val orientation: String,
    val iconLabel: String,
    val iconColor: Int,
    val iconUri: String?,
    val sizeBytes: Long,
    val displayOrder: Int,
)

object GameCatalog {
    private const val TAG = "GameCatalog"
    private const val CATALOG_CACHE_TTL_MS = 60 * 1000L
    private var cachedGames: List<GameEntry>? = null
    private var cachedAtMs: Long = 0L

    private val iconColors = intArrayOf(
        0xFF4DB6AC.toInt(),
        0xFFFF8A65.toInt(),
        0xFF9575CD.toInt(),
        0xFF64B5F6.toInt(),
        0xFFBA68C8.toInt(),
        0xFFFFD54F.toInt(),
        0xFF81C784.toInt(),
        0xFFFFB74D.toInt(),
    )

    private const val ASSET_GAMES_DIR = "games"

    suspend fun listGames(context: Context, forceRefresh: Boolean = false): List<GameEntry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedGames != null && now - cachedAtMs < CATALOG_CACHE_TTL_MS) {
            return@withContext cachedGames!!
        }

        val distribution = GameDistributionRepository.load(context, forceRefresh)
        val entryMap = linkedMapOf<String, GameEntry>()

        listFromFileSystem(File("/sdcard/game-demo")).forEach { addByIdentity(entryMap, it) }
        listFromAssets(context).forEach { addByIdentity(entryMap, it) }

        val result = entryMap.values
            .mapNotNull { applyDistribution(it, distribution) }
            .sortedWith(
                compareBy<GameEntry> { it.displayOrder }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.assetName.lowercase() },
        )
        cachedGames = result
        cachedAtMs = now
        result
    }

    fun loadLastPlayed(context: Context, serialized: String): List<GameEntry> {
        if (serialized.isBlank()) return emptyList()
        // Use cached if available, but this is a sync call usually from ProfileFragment
        val available = (cachedGames ?: emptyList()).associateBy { it.file.path }
        return serialized
            .split(",")
            .mapNotNull { raw -> available[raw] }
            .filter { isLaunchable(it) }
    }

    fun addToRecent(context: android.content.SharedPreferences, path: String) {
        val recentStr = context.getString("recent_games", "") ?: ""
        val recentList = if (recentStr.isBlank()) mutableListOf() else recentStr.split(",").toMutableList()
        recentList.remove(path)
        recentList.add(0, path)
        val limited = recentList.take(5)
        context.edit().putString("recent_games", limited.joinToString(",")).apply()
    }

    private fun addByIdentity(map: LinkedHashMap<String, GameEntry>, entry: GameEntry) {
        val key = entry.id.lowercase()
        if (!map.containsKey(key)) {
            map[key] = entry
        }
    }

    private fun isLaunchable(entry: GameEntry): Boolean {
        return assetPathFromGamePath(entry.file.path) != null || entry.file.exists()
    }

    private fun listFromFileSystem(dir: File): List<GameEntry> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles { _, name -> name.endsWith(".zip", ignoreCase = true) } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { toEntry(context = null, file = it) }
    }

    private fun listFromAssets(context: Context): List<GameEntry> {
        val assetNames = try {
            context.assets.list(ASSET_GAMES_DIR) ?: emptyArray()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to list assets/$ASSET_GAMES_DIR", e)
            emptyArray()
        }

        return assetNames
            .filter { it.endsWith(".zip", ignoreCase = true) }
            .sortedBy { it.lowercase() }
            .map { name ->
                val virtualFile = File("assets://$ASSET_GAMES_DIR/$name")
                toEntry(context, virtualFile)
            }
    }

    private fun toEntry(context: Context?, file: File): GameEntry {
        val path = file.path
        val assetPath = assetPathFromGamePath(path)
        val name = assetPath?.substringAfterLast("/") ?: file.name
        val size = if (assetPath != null && context != null) {
            resolveAssetSize(context, assetPath)
        } else {
            file.length()
        }
        val raw = name.substringBeforeLast(".zip", name)
        val gameId = raw.substringBefore("_").ifBlank { raw }
        val displayName = raw
            .substringAfterLast("/")
            .replace("_", " ")
            .replace(Regex("\\d+\\.\\d+\\.\\d+"), "") // Remove version-like patterns
            .trim()
            .ifBlank { "Mini Game" }
        val color = iconColors[(raw.hashCode() and Int.MAX_VALUE) % iconColors.size]
        val iconLabel = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
        val packageOrientation = resolvePackageOrientation(context, file, assetPath)
        return GameEntry(
            id = gameId,
            assetName = name,
            file = file,
            displayName = displayName,
            description = "This game package is ready to launch.",
            orientation = packageOrientation,
            iconLabel = iconLabel,
            iconColor = color,
            iconUri = null,
            sizeBytes = size,
            displayOrder = Int.MAX_VALUE,
        )
    }

    private fun applyDistribution(
        entry: GameEntry,
        distribution: GameDistributionConfig,
    ): GameEntry? {
        val metadata = distribution.findFor(entry)
        val visible = (metadata?.visible ?: distribution.defaultVisible) &&
            (distribution.tailNumberRule?.allows(entry.id) ?: true)
        if (!visible) {
            return null
        }

        val iconColor = metadata?.iconColor?.let { parseColorOrNull(it) } ?: entry.iconColor
        val configuredLabel = metadata?.iconLabel?.trim()?.takeIf { it.isNotEmpty() }
        val displayName = metadata?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: entry.displayName
        val fallbackLabel = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: entry.iconLabel
        val iconLabel = (configuredLabel ?: fallbackLabel).take(3)
        return entry.copy(
            displayName = displayName,
            description = metadata?.description?.trim()?.takeIf { it.isNotEmpty() } ?: entry.description,
            orientation = metadata?.orientation?.let { GameOrientation.normalize(it) } ?: entry.orientation,
            iconLabel = iconLabel,
            iconColor = iconColor,
            iconUri = metadata?.iconUri?.trim()?.takeIf { it.isNotEmpty() } ?: entry.iconUri,
            displayOrder = metadata?.order ?: entry.displayOrder,
        )
    }

    private fun parseColorOrNull(value: String): Int? {
        return try {
            android.graphics.Color.parseColor(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun assetPathFromGamePath(path: String): String? {
        val trimmed = path.trim()
        val candidate = when {
            trimmed.startsWith("assets://") -> trimmed.removePrefix("assets://").trimStart('/')
            trimmed.startsWith("/assets://") -> trimmed.removePrefix("/assets://").trimStart('/')
            trimmed.startsWith("assets:/") -> trimmed.removePrefix("assets:/").trimStart('/')
            trimmed.startsWith("/assets:/") -> trimmed.removePrefix("/assets:/").trimStart('/')
            else -> null
        }
        return candidate?.takeIf { it.isNotEmpty() }
    }

    private fun resolvePackageOrientation(context: Context?, file: File, assetPath: String?): String {
        val gameJson = readZipText(context, file, assetPath, "game.json")
        return gameJson?.let { parsePackageOrientation(it) } ?: GameOrientation.LANDSCAPE
    }

    private fun parsePackageOrientation(content: String): String? {
        return try {
            val json = JSONObject(content)
            val raw = listOf("deviceOrientation", "orientation", "screenOrientation")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            raw?.let { GameOrientation.normalize(it) }
        } catch (_: Exception) {
            Regex(
                "\"(?:deviceOrientation|orientation|screenOrientation)\"\\s*:\\s*\"([^\"]+)\"",
                RegexOption.IGNORE_CASE,
            ).find(content)?.groupValues?.getOrNull(1)?.let { GameOrientation.normalize(it) }
        }
    }

    private fun readZipText(
        context: Context?,
        file: File,
        assetPath: String?,
        entryName: String,
    ): String? {
        return try {
            if (assetPath != null && context != null) {
                context.assets.open(assetPath).use { input ->
                    ZipInputStream(input).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory && entry.name == entryName) {
                                return zip.bufferedReader(Charsets.UTF_8).readText()
                            }
                            zip.closeEntry()
                        }
                    }
                }
                null
            } else if (file.isFile) {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return null
                    zip.getInputStream(entry).use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read $entryName from ${assetPath ?: file.path}", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to read $entryName from ${assetPath ?: file.path}", e)
            null
        }
    }

    private fun resolveAssetSize(context: Context, assetPath: String): Long {
        try {
            context.assets.openFd(assetPath).use { descriptor ->
                if (descriptor.length >= 0L) return descriptor.length
            }
        } catch (_: IOException) {
            // Compressed APK assets cannot be opened as file descriptors.
        } catch (_: RuntimeException) {
            // Keep catalog loading resilient if a device asset manager behaves differently.
        }

        val apkEntryPath = "assets/$assetPath"
        val sourceDirs = mutableListOf<String>()
        context.applicationInfo.sourceDir?.let { sourceDirs.add(it) }
        context.applicationInfo.splitSourceDirs?.forEach { sourceDirs.add(it) }
        sourceDirs.forEach { sourceDir ->
            try {
                ZipFile(sourceDir).use { apk ->
                    val size = apk.getEntry(apkEntryPath)?.size ?: -1L
                    if (size >= 0L) return size
                }
            } catch (_: IOException) {
                // Try the next source dir, then fall back to counting the stream.
            } catch (_: RuntimeException) {
                // Ignore malformed or inaccessible split sources.
            }
        }

        return try {
            context.assets.open(assetPath).use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read.toLong()
                }
                total
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to measure asset size for $assetPath", e)
            0L
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to measure asset size for $assetPath", e)
            0L
        }
    }
}

object GameSizeFormatter {
    fun format(context: Context, bytes: Long): String {
        if (bytes <= 0L) return context.getString(R.string.game_size_unavailable)
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024L
        if (kb < 1024L) return "$kb KB"
        val mb = bytes / (1024f * 1024f)
        return String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}

object GameOrientation {
    const val LANDSCAPE = "landscape"
    const val PORTRAIT = "portrait"

    fun normalize(value: String): String? {
        return when (value.trim().lowercase()) {
            LANDSCAPE, "sensor_landscape", "sensorlandscape", "landscape_sensor" -> LANDSCAPE
            PORTRAIT, "sensor_portrait", "sensorportrait", "portrait_sensor" -> PORTRAIT
            else -> null
        }
    }
}
