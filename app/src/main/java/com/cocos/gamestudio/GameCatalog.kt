package com.cocos.gamestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

data class GameEntry(
    val file: File,
    val displayName: String,
    val iconLabel: String,
    val iconColor: Int,
    val sizeBytes: Long,
)

object GameCatalog {
    private const val TAG = "GameCatalog"
    private var cachedGames: List<GameEntry>? = null

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
        if (!forceRefresh && cachedGames != null) {
            return@withContext cachedGames!!
        }

        val entryMap = linkedMapOf<String, GameEntry>()

        listFromFileSystem(File("/sdcard/game-demo")).forEach { addByName(entryMap, it) }
        listFromAssets(context).forEach { addByName(entryMap, it) }

        val result = entryMap.values.sortedBy { it.displayName.lowercase() }
        cachedGames = result
        result
    }

    fun loadLastPlayed(context: Context, serialized: String): List<GameEntry> {
        if (serialized.isBlank()) return emptyList()
        // Use cached if available, but this is a sync call usually from ProfileFragment
        val available = (cachedGames ?: emptyList()).associateBy { it.file.path }
        return serialized
            .split(",")
            .mapNotNull { raw -> available[raw] }
            .filter { it.file.exists() }
    }

    fun addToRecent(context: android.content.SharedPreferences, path: String) {
        val recentStr = context.getString("recent_games", "") ?: ""
        val recentList = if (recentStr.isBlank()) mutableListOf() else recentStr.split(",").toMutableList()
        recentList.remove(path)
        recentList.add(0, path)
        val limited = recentList.take(5)
        context.edit().putString("recent_games", limited.joinToString(",")).apply()
    }

    private fun addByName(map: LinkedHashMap<String, GameEntry>, entry: GameEntry) {
        val key = entry.file.name.lowercase()
        if (!map.containsKey(key)) {
            map[key] = entry
        }
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
        val displayName = raw
            .substringAfterLast("/")
            .replace("_", " ")
            .replace(Regex("\\d+\\.\\d+\\.\\d+"), "") // Remove version-like patterns
            .trim()
            .ifBlank { "Mini Game" }
        val color = iconColors[(raw.hashCode() and Int.MAX_VALUE) % iconColors.size]
        val iconLabel = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
        return GameEntry(file, displayName, iconLabel, color, size)
    }
}
