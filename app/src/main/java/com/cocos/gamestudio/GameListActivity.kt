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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class GameListActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_list)

        NativeEngine.nativeSetAssetManager(assets)

        toolbar = findViewById(R.id.main_toolbar)
        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_games -> {
                    loadFragment(
                        GameListFragment(),
                        getString(R.string.game_catalog),
                        getString(R.string.game_catalog_subtitle),
                    )
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment(), getString(R.string.nav_profile), null)
                    true
                }
                else -> false
            }
        }

        // Default fragment
        if (savedInstanceState == null) {
            loadFragment(
                GameListFragment(),
                getString(R.string.game_catalog),
                getString(R.string.game_catalog_subtitle),
            )
            bottomNavigation.selectedItemId = R.id.nav_games
        }
    }

    private fun loadFragment(fragment: Fragment, title: String, subtitle: String?) {
        toolbar.title = title
        toolbar.subtitle = subtitle
        toolbar.contentDescription = title
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

        fun filter(query: String): Int {
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
            return filteredGames.size
        }

        fun filteredCount(): Int = filteredGames.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.game_item, parent, false)
            return MyViewHolder(view)
        }

        override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
            val game = filteredGames[position]
            val context = holder.view.context
            holder.textView.text = game.displayName
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = holder.view.resources.getDimension(R.dimen.game_icon_corner)
                setColor(game.iconColor)
                setStroke(1, ContextCompat.getColor(context, R.color.surface))
            }
            holder.iconView.background = bg
            holder.iconView.text = game.iconLabel
            holder.iconView.contentDescription = context.getString(R.string.game_icon_description, game.displayName)
            holder.iconImageView.contentDescription = context.getString(R.string.game_icon_description, game.displayName)
            GameIconLoader.bind(holder.iconImageView, holder.iconView, game)
            holder.sizeView.text = GameSizeFormatter.format(context, game.sizeBytes)
            holder.view.contentDescription = context.getString(
                R.string.game_card_description,
                game.displayName,
                holder.sizeView.text,
            )
            holder.view.setOnClickListener { onClick(game) }
        }

        override fun getItemCount() = filteredGames.size
    }
}
