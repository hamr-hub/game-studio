package com.cocos.gamestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

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
        return entry.file.path.startsWith("assets://") || entry.file.exists()
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
        val name = if (path.startsWith("assets://")) {
            path.substringAfterLast("/")
        } else {
            file.name
        }
        val size = if (path.startsWith("assets://") && context != null) {
            try {
                val assetPath = path.removePrefix("assets://").trimStart('/')
                context.assets.open(assetPath).use { it.available().toLong() }
            } catch (_: Exception) {
                0L
            }
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
        return GameEntry(
            id = gameId,
            assetName = name,
            file = file,
            displayName = displayName,
            description = "This game package is ready to launch.",
            orientation = GameOrientation.LANDSCAPE,
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
