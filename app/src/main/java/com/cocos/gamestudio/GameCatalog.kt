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
)

object GameCatalog {
    private const val TAG = "GameCatalog"

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

    fun listGames(context: Context): List<GameEntry> {
        val entryMap = linkedMapOf<String, GameEntry>()

        listFromFileSystem(File("/sdcard/game-demo")).forEach { addByName(entryMap, it) }
        listFromAssets(context).forEach { addByName(entryMap, it) }

        return entryMap.values.sortedBy { it.displayName.lowercase() }
    }

    fun loadLastPlayed(context: Context, serialized: String): List<GameEntry> {
        if (serialized.isBlank()) return emptyList()
        val available = listGames(context).associateBy { it.file.absolutePath }
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
        return files.sortedBy { it.name.lowercase() }.map(::toEntry)
    }

    private fun listFromAssets(context: Context): List<GameEntry> {
        val assetNames = try {
            context.assets.list(ASSET_GAMES_DIR) ?: emptyArray()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to list assets/$ASSET_GAMES_DIR", e)
            emptyArray()
        }

        if (assetNames.isEmpty()) return emptyList()

        val targetDir = File(context.filesDir, "games")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Log.w(TAG, "Failed to create cache dir for games: ${targetDir.absolutePath}")
            return emptyList()
        }

        return assetNames
            .filter { it.endsWith(".zip", ignoreCase = true) }
            .sortedBy { it.lowercase() }
            .mapNotNull { name -> copyAssetToFile(context, name, targetDir)?.let(::toEntry) }
    }

    private fun copyAssetToFile(context: Context, name: String, targetDir: File): File? {
        val target = File(targetDir, name)
        if (target.exists()) return target

        return try {
            context.assets.open("$ASSET_GAMES_DIR/$name").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target
        } catch (e: IOException) {
            Log.w(TAG, "Failed to copy demo $name", e)
            null
        }
    }

    private fun toEntry(file: File): GameEntry {
        val raw = file.name.substringBeforeLast(".zip", file.name)
        val displayName = raw
            .substringAfterLast("/")
            .replace("_", " ")
            .trim()
            .ifBlank { "Mini Game" }
        val color = iconColors[(raw.hashCode() and Int.MAX_VALUE) % iconColors.size]
        val iconLabel = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
        return GameEntry(file, displayName, iconLabel, color)
    }
}
