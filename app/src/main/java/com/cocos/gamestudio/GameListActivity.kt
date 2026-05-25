package com.cocos.gamestudio

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView

class GameListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_list)

        NativeEngine.nativeSetAssetManager(assets)

        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_games -> {
                    loadFragment(GameListFragment())
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }

        // Default fragment
        if (savedInstanceState == null) {
            loadFragment(GameListFragment())
            bottomNavigation.selectedItemId = R.id.nav_games
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    class GameAdapter(private var allGames: List<GameEntry>, private val onClick: (GameEntry) -> Unit) :
        RecyclerView.Adapter<GameAdapter.MyViewHolder>() {

        private var filteredGames: List<GameEntry> = allGames

        class MyViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.game_name_tv)
            val iconImageView: ImageView = view.findViewById(R.id.game_icon_iv)
            val iconView: TextView = view.findViewById(R.id.game_icon_tv)
            val sizeView: TextView = view.findViewById(R.id.game_size_tv)
        }

        fun updateData(newData: List<GameEntry>) {
            allGames = newData
            filteredGames = newData
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            val normalizedQuery = query.trim()
            filteredGames = if (normalizedQuery.isEmpty()) {
                allGames
            } else {
                allGames.filter {
                    it.displayName.contains(normalizedQuery, ignoreCase = true) ||
                        it.id.contains(normalizedQuery, ignoreCase = true) ||
                        it.assetName.contains(normalizedQuery, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.game_item, parent, false)
            return MyViewHolder(view)
        }

        override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
            val game = filteredGames[position]
            holder.textView.text = game.displayName
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(game.iconColor)
            }
            holder.iconView.background = bg
            holder.iconView.text = game.iconLabel
            GameIconLoader.bind(holder.iconImageView, holder.iconView, game)
            holder.sizeView.text = formatSize(game.sizeBytes)
            holder.view.setOnClickListener { onClick(game) }
        }

        override fun getItemCount() = filteredGames.size

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024
            if (kb < 1024) return "$kb KB"
            val mb = kb / 1024
            return String.format("%.1f MB", mb.toFloat())
        }
    }
}
