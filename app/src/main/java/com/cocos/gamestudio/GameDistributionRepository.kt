package com.cocos.gamestudio

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

data class GameDistributionConfig(
    val defaultVisible: Boolean = true,
    val hasDefaultVisible: Boolean = false,
    val entries: List<GameDistributionEntry> = emptyList(),
) {
    fun findFor(game: GameEntry): GameDistributionEntry? {
        val keys = setOf(
            game.file.path,
            game.assetName,
            game.assetName.substringBeforeLast(".zip", game.assetName),
            game.id,
        ).map { normalizeKey(it) }

        return entries.firstOrNull { entry ->
            entry.matchKeys.any { it in keys }
        }
    }

    fun merge(overrides: GameDistributionConfig): GameDistributionConfig {
        val merged = linkedMapOf<String, GameDistributionEntry>()
        entries.forEach { entry ->
            merged[entry.mergeKey] = entry
        }
        overrides.entries.forEach { override ->
            val key = findMergeKey(merged, override)
            merged[key] = merged[key]?.merge(override) ?: override
        }
        return GameDistributionConfig(
            defaultVisible = if (overrides.hasDefaultVisible) overrides.defaultVisible else defaultVisible,
            hasDefaultVisible = hasDefaultVisible || overrides.hasDefaultVisible,
            entries = merged.values.toList(),
        )
    }

    private fun findMergeKey(
        entriesByKey: LinkedHashMap<String, GameDistributionEntry>,
        override: GameDistributionEntry,
    ): String {
        val overrideKeys = override.matchKeys
        return entriesByKey.entries.firstOrNull { (_, entry) ->
            entry.matchKeys.any { it in overrideKeys }
        }?.key ?: override.mergeKey
    }
}

data class GameDistributionEntry(
    val id: String?,
    val assetName: String?,
    val path: String?,
    val visible: Boolean?,
    val order: Int?,
    val displayName: String?,
    val description: String?,
    val iconLabel: String?,
    val iconColor: String?,
    val iconUri: String?,
) {
    val mergeKey: String
        get() = normalizeKey(id ?: assetName ?: path ?: displayName ?: "")

    val matchKeys: Set<String>
        get() = listOfNotNull(
            id,
            assetName,
            assetName?.substringBeforeLast(".zip", assetName),
            path,
            displayName,
        ).map { normalizeKey(it) }.toSet()

    fun merge(override: GameDistributionEntry): GameDistributionEntry {
        return GameDistributionEntry(
            id = override.id ?: id,
            assetName = override.assetName ?: assetName,
            path = override.path ?: path,
            visible = override.visible ?: visible,
            order = override.order ?: order,
            displayName = override.displayName ?: displayName,
            description = override.description ?: description,
            iconLabel = override.iconLabel ?: iconLabel,
            iconColor = override.iconColor ?: iconColor,
            iconUri = override.iconUri ?: iconUri,
        )
    }
}

object GameDistributionRepository {
    private const val TAG = "GameDistribution"
    private const val DEFAULT_CONFIG_ASSET = "game_distribution.json"
    private const val PREFS_NAME = "game_distribution"
    private const val KEY_REMOTE_JSON = "remote_json"
    private const val KEY_REMOTE_FETCHED_AT = "remote_fetched_at"
    private const val KEY_REMOTE_URL_OVERRIDE = "remote_url"
    private const val META_REMOTE_URL = "com.cocos.gamestudio.GAME_DISTRIBUTION_URL"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L

    fun load(context: Context, forceRefresh: Boolean = false): GameDistributionConfig {
        val defaults = readDefaultConfig(context)
        val remoteUrl = resolveRemoteUrl(context)
        if (remoteUrl.isBlank()) {
            return defaults
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(KEY_REMOTE_FETCHED_AT, 0L)
        val cachedJson = prefs.getString(KEY_REMOTE_JSON, null)
        val shouldFetch = forceRefresh || cachedJson.isNullOrBlank() || now - cachedAt > CACHE_TTL_MS

        val remoteJson = if (shouldFetch) {
            fetchRemoteConfig(remoteUrl)?.also { json ->
                prefs.edit()
                    .putString(KEY_REMOTE_JSON, json)
                    .putLong(KEY_REMOTE_FETCHED_AT, now)
                    .apply()
            } ?: cachedJson
        } else {
            cachedJson
        }

        val remoteConfig = remoteJson?.let { parseConfig(it) }
        return if (remoteConfig != null) defaults.merge(remoteConfig) else defaults
    }

    private fun readDefaultConfig(context: Context): GameDistributionConfig {
        return try {
            context.assets.open(DEFAULT_CONFIG_ASSET).bufferedReader().use { reader ->
                parseConfig(reader.readText()) ?: GameDistributionConfig()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default distribution config unavailable", e)
            GameDistributionConfig()
        }
    }

    private fun resolveRemoteUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_REMOTE_URL_OVERRIDE, null)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }

        val manifestUrl = try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            appInfo.metaData?.getString(META_REMOTE_URL)?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (manifestUrl.isNotBlank()) {
            return manifestUrl
        }

        return try {
            context.getString(R.string.game_distribution_config_url).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchRemoteConfig(remoteUrl: String): String? {
        return try {
            val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { reader ->
                        reader.readText()
                    }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote distribution config fetch failed: $remoteUrl", e)
            null
        }
    }

    private fun parseConfig(rawJson: String): GameDistributionConfig? {
        return try {
            val root = JSONObject(rawJson)
            val hasDefaultVisible = root.has("defaultVisible") || root.has("default_visible")
            val defaultVisible = when {
                root.has("defaultVisible") -> root.optBoolean("defaultVisible", true)
                root.has("default_visible") -> root.optBoolean("default_visible", true)
                else -> true
            }
            GameDistributionConfig(
                defaultVisible = defaultVisible,
                hasDefaultVisible = hasDefaultVisible,
                entries = parseEntries(root.opt("games")),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Invalid game distribution config", e)
            null
        }
    }

    private fun parseEntries(rawGames: Any?): List<GameDistributionEntry> {
        return when (rawGames) {
            is JSONArray -> (0 until rawGames.length()).mapNotNull { index ->
                rawGames.optJSONObject(index)?.let { parseEntry(it, index, null) }
            }
            is JSONObject -> {
                val result = mutableListOf<GameDistributionEntry>()
                val keys = rawGames.keys()
                var index = 0
                while (keys.hasNext()) {
                    val key = keys.next()
                    rawGames.optJSONObject(key)?.let { result += parseEntry(it, index, key) }
                    index += 1
                }
                result
            }
            else -> emptyList()
        }
    }

    private fun parseEntry(
        item: JSONObject,
        index: Int,
        objectKey: String?,
    ): GameDistributionEntry {
        val icon = item.optJSONObject("icon")
        val explicitOrder = optInt(item, "order") ?: optInt(item, "sortOrder") ?: optInt(item, "sort_order")
        val fallbackOrder = if (objectKey == null) index else null
        return GameDistributionEntry(
            id = optString(item, "id") ?: objectKey,
            assetName = optString(item, "asset") ?: optString(item, "assetName") ?: optString(item, "file"),
            path = optString(item, "path"),
            visible = parseVisible(item),
            order = explicitOrder ?: fallbackOrder,
            displayName = optString(item, "displayName") ?: optString(item, "display_name") ?: optString(item, "name") ?: optString(item, "title"),
            description = optString(item, "description") ?: optString(item, "subtitle"),
            iconLabel = optString(item, "iconLabel") ?: optString(item, "icon_label") ?: icon?.let { optString(it, "label") },
            iconColor = optString(item, "iconColor") ?: optString(item, "icon_color") ?: icon?.let { optString(it, "color") },
            iconUri = optString(item, "iconUrl")
                ?: optString(item, "icon_url")
                ?: optString(item, "iconUri")
                ?: optString(item, "icon_uri")
                ?: optString(item, "iconAsset")
                ?: optString(item, "icon_asset")
                ?: icon?.let { optString(it, "url") ?: optString(it, "uri") ?: optString(it, "asset") },
        )
    }

    private fun parseVisible(item: JSONObject): Boolean? {
        return when {
            item.has("visible") -> item.optBoolean("visible", true)
            item.has("enabled") -> item.optBoolean("enabled", true)
            item.has("hidden") -> !item.optBoolean("hidden", false)
            else -> null
        }
    }

    private fun optString(item: JSONObject, key: String): String? {
        if (!item.has(key) || item.isNull(key)) return null
        return item.optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun optInt(item: JSONObject, key: String): Int? {
        if (!item.has(key) || item.isNull(key)) return null
        return max(0, item.optInt(key))
    }
}

fun normalizeKey(value: String): String {
    return value
        .trim()
        .lowercase()
        .removePrefix("assets://games/")
        .removePrefix("/assets://games/")
        .removePrefix("games/")
}
