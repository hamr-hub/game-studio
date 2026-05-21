package com.cocos.gamestudio

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GameListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var viewAdapter: GameListActivity.GameAdapter
    private lateinit var viewManager: GridLayoutManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_game_list, container, false)

        val gameEntries = listGames()

        viewManager = GridLayoutManager(context, 2)
        viewAdapter = GameListActivity.GameAdapter(gameEntries) { game ->
            val intent = Intent(activity, GameActivity::class.java).apply {
                putExtra("GAME_PATH", game.file.absolutePath)
            }
            startActivity(intent)
        }

        recyclerView = view.findViewById<RecyclerView>(R.id.game_list_rv).apply {
            setHasFixedSize(true)
            layoutManager = viewManager
            adapter = viewAdapter
        }
        
        return view
    }

    private fun listGames(): List<GameEntry> {
        val context = requireContext()
        return GameCatalog.listGames(context)
    }
}
