package com.cocos.gamestudio

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class GameListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var viewAdapter: GameListActivity.GameAdapter
    private lateinit var viewManager: GridLayoutManager
    private lateinit var progressBar: ProgressBar
    private lateinit var searchView: SearchView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_game_list, container, false)

        progressBar = view.findViewById(R.id.loading_pb)
        searchView = view.findViewById(R.id.search_view)
        recyclerView = view.findViewById(R.id.game_list_rv)

        viewManager = GridLayoutManager(context, 2)
        viewAdapter = GameListActivity.GameAdapter(emptyList()) { game ->
            showGameDetail(game)
        }

        recyclerView.apply {
            setHasFixedSize(true)
            layoutManager = viewManager
            adapter = viewAdapter
        }

        setupSearch()
        loadGames()
        
        return view
    }

    private fun showGameDetail(game: GameEntry) {
        val dialog = BottomSheetDialog(requireContext())
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_game_detail, null)
        
        val iconTv = bottomSheetView.findViewById<TextView>(R.id.detail_icon_tv)
        val nameTv = bottomSheetView.findViewById<TextView>(R.id.detail_name_tv)
        val sizeTv = bottomSheetView.findViewById<TextView>(R.id.detail_size_tv)
        val playBtn = bottomSheetView.findViewById<MaterialButton>(R.id.play_button)

        iconTv.text = game.iconLabel
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(game.iconColor)
        }
        iconTv.background = bg
        nameTv.text = game.displayName
        sizeTv.text = formatSize(game.sizeBytes)

        playBtn.setOnClickListener {
            dialog.dismiss()
            launchGame(game)
        }

        dialog.setContentView(bottomSheetView)
        dialog.show()
    }

    private fun launchGame(game: GameEntry) {
        val intent = Intent(activity, GameActivity::class.java).apply {
            putExtra("GAME_PATH", game.file.absolutePath)
        }
        startActivity(intent)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024
        if (kb < 1024) return "$kb KB"
        val mb = kb / 1024
        return String.format("%.1f MB", mb.toFloat())
    }

    private fun setupSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewAdapter.filter(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewAdapter.filter(newText ?: "")
                return true
            }
        })
    }

    private fun loadGames() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val gameEntries = GameCatalog.listGames(requireContext())
            viewAdapter.updateData(gameEntries)
            progressBar.visibility = View.GONE
        }
    }
}
