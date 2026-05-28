package com.cocos.gamestudio

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

data class GameProgress(
    val path: String,
    val displayName: String,
    val iconLabel: String,
    val iconColor: Int,
    val orientation: String,
    val totalPlayMs: Long,
    val sessionCount: Int,
    val lastPlayedAt: Long,
)

data class Achievement(
    val title: String,
    val description: String,
    val iconRes: Int,
    val unlocked: Boolean,
)

data class RewardGoal(
    val title: String,
    val description: String,
    val progress: Int,
    val target: Int,
    val points: Int,
)

data class LeaderboardEntry(
    val rank: Int,
    val displayName: String,
    val iconLabel: String,
    val iconColor: Int,
    val scoreText: String,
    val metaText: String,
)

data class PlayerProgressSnapshot(
    val nickname: String,
    val title: String,
    val level: Int,
    val points: Int,
    val xpProgress: Int,
    val xpTarget: Int,
    val totalPlayMs: Long,
    val totalSessions: Int,
    val uniqueGames: Int,
    val streakDays: Int,
    val achievements: List<Achievement>,
    val rewards: List<RewardGoal>,
    val history: List<GameProgress>,
    val leaderboard: List<LeaderboardEntry>,
)

object PlayerProgressRepository {
    private const val PREFS_NAME = "user_prefs"
    private const val KEY_STAT_PATHS = "game_stat_paths"
    private const val KEY_PLAY_DAYS = "play_days"
    private const val KEY_TOTAL_PLAY_MS = "total_play_ms"
    private const val KEY_LEGACY_MINUTES = "minutes_played"
    private const val XP_PER_LEVEL = 500
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun recordSession(context: Context, rawPath: String, durationMs: Long) {
        val path = normalizeGamePath(rawPath)
        if (path.isBlank() || durationMs <= 0L) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = statId(path)
        val now = System.currentTimeMillis()
        val safeDurationMs = max(durationMs, 1000L)

        val knownPaths = readLines(prefs.getString(KEY_STAT_PATHS, "").orEmpty()).toMutableList()
        if (!knownPaths.contains(path)) knownPaths.add(path)

        val totalMs = prefs.getLong("${id}_total_ms", 0L) + safeDurationMs
        val sessions = prefs.getInt("${id}_sessions", 0) + 1
        val totalPlayMs = readTotalPlayMs(prefs) + safeDurationMs

        val days = readLines(prefs.getString(KEY_PLAY_DAYS, "").orEmpty()).toMutableSet()
        days.add((now / DAY_MS).toString())

        val editor = prefs.edit()
            .putString(KEY_STAT_PATHS, knownPaths.joinToString("\n"))
            .putString(KEY_PLAY_DAYS, days.sorted().joinToString("\n"))
            .putLong(KEY_TOTAL_PLAY_MS, totalPlayMs)
            .putLong(KEY_LEGACY_MINUTES, totalPlayMs / 60000L)
            .putLong("${id}_total_ms", totalMs)
            .putInt("${id}_sessions", sessions)
            .putLong("${id}_last_played", now)
            .putString("${id}_display_name", fallbackDisplayName(path))
        editor.apply()

        GameCatalog.addToRecent(prefs, path)
    }

    fun snapshot(context: Context, catalog: List<GameEntry>): PlayerProgressSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stats = gameProgress(prefs, catalog)
        val totalPlayMs = readTotalPlayMs(prefs, stats.sumOf { it.totalPlayMs })
        val totalSessions = stats.sumOf { it.sessionCount }
        val uniqueGames = stats.count { it.sessionCount > 0 }
        val streakDays = currentStreak(readLines(prefs.getString(KEY_PLAY_DAYS, "").orEmpty()))
        val points = calculatePoints(totalPlayMs, totalSessions, uniqueGames, streakDays)
        val level = points / XP_PER_LEVEL + 1
        val xpProgress = points % XP_PER_LEVEL
        val achievements = achievements(totalPlayMs, totalSessions, uniqueGames, streakDays, points, stats)
        val title = rankTitle(level, achievements.count { it.unlocked })

        return PlayerProgressSnapshot(
            nickname = prefs.getString("nickname", "Cocos Expert") ?: "Cocos Expert",
            title = title,
            level = level,
            points = points,
            xpProgress = xpProgress,
            xpTarget = XP_PER_LEVEL,
            totalPlayMs = totalPlayMs,
            totalSessions = totalSessions,
            uniqueGames = uniqueGames,
            streakDays = streakDays,
            achievements = achievements,
            rewards = rewards(totalPlayMs, totalSessions, uniqueGames, streakDays),
            history = stats.sortedByDescending { it.lastPlayedAt },
            leaderboard = leaderboard(stats),
        )
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 min"
        val minutes = ms / 60000L
        if (minutes < 1L) return "<1 min"
        val hours = minutes / 60L
        val remainder = minutes % 60L
        return if (hours > 0L) {
            if (remainder > 0L) "${hours}h ${remainder}m" else "${hours}h"
        } else {
            "$minutes min"
        }
    }

    private fun gameProgress(
        prefs: android.content.SharedPreferences,
        catalog: List<GameEntry>,
    ): List<GameProgress> {
        val catalogByPath = catalog.associateBy { normalizeGamePath(it.file.path) }
        val paths = readLines(prefs.getString(KEY_STAT_PATHS, "").orEmpty())
        return paths.mapNotNull { path ->
            val id = statId(path)
            val entry = catalogByPath[path]
            val totalMs = prefs.getLong("${id}_total_ms", 0L)
            val sessions = prefs.getInt("${id}_sessions", 0)
            val lastPlayedAt = prefs.getLong("${id}_last_played", 0L)
            if (totalMs <= 0L && sessions <= 0) return@mapNotNull null
            GameProgress(
                path = path,
                displayName = entry?.displayName ?: prefs.getString("${id}_display_name", fallbackDisplayName(path)).orEmpty(),
                iconLabel = entry?.iconLabel ?: fallbackIconLabel(path),
                iconColor = entry?.iconColor ?: fallbackIconColor(path),
                orientation = entry?.orientation ?: GameOrientation.LANDSCAPE,
                totalPlayMs = totalMs,
                sessionCount = sessions,
                lastPlayedAt = lastPlayedAt,
            )
        }
    }

    private fun achievements(
        totalPlayMs: Long,
        totalSessions: Int,
        uniqueGames: Int,
        streakDays: Int,
        points: Int,
        stats: List<GameProgress>,
    ): List<Achievement> {
        val longestGameMs = stats.maxOfOrNull { it.totalPlayMs } ?: 0L
        return listOf(
            Achievement(
                "First Run",
                "Launch any game once.",
                R.drawable.ic_medal,
                totalSessions >= 1,
            ),
            Achievement(
                "Explorer",
                "Play three different games.",
                R.drawable.ic_compass,
                uniqueGames >= 3,
            ),
            Achievement(
                "Focus Mode",
                "Spend 30 minutes in one game.",
                R.drawable.ic_flame,
                longestGameMs >= 30 * 60000L,
            ),
            Achievement(
                "Steady Streak",
                "Play for three days in a row.",
                R.drawable.ic_streak,
                streakDays >= 3,
            ),
            Achievement(
                "Arcade Pro",
                "Earn 1,000 player points.",
                R.drawable.ic_trophy,
                points >= 1000,
            ),
            Achievement(
                "Marathon",
                "Reach 10 total play hours.",
                R.drawable.ic_clock,
                totalPlayMs >= 10 * 60 * 60000L,
            ),
        )
    }

    private fun rewards(totalPlayMs: Long, totalSessions: Int, uniqueGames: Int, streakDays: Int): List<RewardGoal> {
        val totalMinutes = totalPlayMs / 60000L
        return listOf(
            RewardGoal("Warm-up", "Reach 10 total play minutes.", totalMinutes.coerceAtMost(10).toInt(), 10, 80),
            RewardGoal("Game Sampler", "Try 3 different games.", uniqueGames.coerceAtMost(3), 3, 120),
            RewardGoal("Session Builder", "Complete 5 game sessions.", totalSessions.coerceAtMost(5), 5, 150),
            RewardGoal("Three Day Streak", "Return for 3 days in a row.", streakDays.coerceAtMost(3), 3, 200),
        )
    }

    private fun leaderboard(stats: List<GameProgress>): List<LeaderboardEntry> {
        return stats
            .sortedWith(compareByDescending<GameProgress> { it.totalPlayMs }.thenByDescending { it.sessionCount })
            .take(10)
            .mapIndexed { index, item ->
                LeaderboardEntry(
                    rank = index + 1,
                    displayName = item.displayName,
                    iconLabel = item.iconLabel,
                    iconColor = item.iconColor,
                    scoreText = formatDuration(item.totalPlayMs),
                    metaText = "${item.sessionCount} sessions",
                )
            }
    }

    private fun calculatePoints(totalPlayMs: Long, totalSessions: Int, uniqueGames: Int, streakDays: Int): Int {
        val minutePoints = (totalPlayMs / 60000L * 10L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return minutePoints + totalSessions * 35 + uniqueGames * 120 + streakDays * 60
    }

    private fun readTotalPlayMs(
        prefs: android.content.SharedPreferences,
        statsTotalMs: Long = 0L,
    ): Long {
        val storedTotalMs = prefs.getLong(KEY_TOTAL_PLAY_MS, -1L)
        val legacyTotalMs = prefs.getLong(KEY_LEGACY_MINUTES, 0L) * 60000L
        return max(max(storedTotalMs, legacyTotalMs), statsTotalMs)
    }

    private fun currentStreak(days: List<String>): Int {
        val values = days.mapNotNull { it.toLongOrNull() }.toSet()
        if (values.isEmpty()) return 0
        var cursor = System.currentTimeMillis() / DAY_MS
        if (!values.contains(cursor)) cursor -= 1
        var count = 0
        while (values.contains(cursor)) {
            count += 1
            cursor -= 1
        }
        return count
    }

    private fun rankTitle(level: Int, medals: Int): String {
        return when {
            level >= 10 || medals >= 5 -> "Arcade Master"
            level >= 6 || medals >= 3 -> "Cocos Strategist"
            level >= 3 || medals >= 1 -> "Game Explorer"
            else -> "New Challenger"
        }
    }

    private fun fallbackDisplayName(path: String): String {
        val raw = path.substringAfterLast('/').substringBeforeLast(".zip")
        val id = raw.substringBefore('_').ifBlank { raw }
        return if (id.isNotBlank()) "Game $id" else "Unknown Game"
    }

    private fun fallbackIconLabel(path: String): String {
        val digits = path.filter { it.isDigit() }.takeLast(2)
        return digits.ifBlank { "G" }
    }

    private fun fallbackIconColor(path: String): Int {
        val colors = intArrayOf(
            0xFF0F766E.toInt(),
            0xFF2563EB.toInt(),
            0xFFD97706.toInt(),
            0xFF7C3AED.toInt(),
            0xFF15803D.toInt(),
        )
        return colors[(path.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun normalizeGamePath(path: String): String {
        val trimmed = path.trim()
        return when {
            trimmed.startsWith("assets://") -> trimmed
            trimmed.startsWith("assets:/") -> "assets://${trimmed.removePrefix("assets:/").trimStart('/')}"
            trimmed.startsWith("/assets://") -> trimmed.removePrefix("/")
            trimmed.startsWith("/assets:/") -> "assets://${trimmed.removePrefix("/assets:/").trimStart('/')}"
            else -> trimmed
        }
    }

    private fun statId(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
    }

    private fun readLines(value: String): List<String> {
        return value.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
