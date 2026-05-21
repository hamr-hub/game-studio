package com.cocos.gamestudio

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProfileFragment : Fragment() {

    companion object {
        private const val PREFS_NAME = "user_prefs"
        private const val DEFAULT_NICKNAME = "Cocos Expert"
        private const val DEFAULT_LEVEL = 42
        private const val DEFAULT_MINUTES = 1337L
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        val avatarIv = view.findViewById<ImageView>(R.id.profile_avatar_iv)
        val nicknameTv = view.findViewById<TextView>(R.id.profile_nickname_tv)
        val levelTv = view.findViewById<TextView>(R.id.profile_level_tv)
        val minutesTv = view.findViewById<TextView>(R.id.profile_minutes_tv)
        val gamesCountTv = view.findViewById<TextView>(R.id.profile_games_count_tv)
        val recentGamesRv = view.findViewById<RecyclerView>(R.id.recent_games_rv)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        if (!prefs.contains("nickname")) {
            prefs.edit().apply {
                putString("nickname", DEFAULT_NICKNAME)
                putInt("level", DEFAULT_LEVEL)
                putLong("minutes_played", DEFAULT_MINUTES)
                apply()
            }
        }

        avatarIv.setImageResource(android.R.drawable.ic_menu_myplaces)
        nicknameTv.text = prefs.getString("nickname", DEFAULT_NICKNAME)
        levelTv.text = "Level ${prefs.getInt("level", DEFAULT_LEVEL)}"
        minutesTv.text = "${prefs.getLong("minutes_played", DEFAULT_MINUTES)} 分钟"

        val recentGames = getRecentGames()
        gamesCountTv.text = recentGames.size.toString()

        recentGamesRv.layoutManager = LinearLayoutManager(context)
        recentGamesRv.adapter = GameListActivity.GameAdapter(recentGames) { game ->
            val intent = Intent(requireContext(), GameActivity::class.java).apply {
                putExtra("GAME_PATH", game.file.absolutePath)
            }
            startActivity(intent)
        }

        return view
    }

    private fun getRecentGames(): List<GameEntry> {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        val recentStr = prefs.getString("recent_games", "") ?: ""
        return GameCatalog.loadLastPlayed(requireContext(), recentStr)
    }
}
