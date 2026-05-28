package com.cocos.gamestudio

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class GameListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var viewAdapter: GameListActivity.GameAdapter
    private lateinit var viewManager: GridLayoutManager
    private lateinit var loadingContainer: View
    private lateinit var emptyStateContainer: View
    private lateinit var resultsStatusTv: TextView
    private lateinit var searchView: SearchView
    private var currentQuery: String = ""
    private var allGameCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_game_list, container, false)

        loadingContainer = view.findViewById(R.id.loading_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        resultsStatusTv = view.findViewById(R.id.results_status_tv)
        searchView = view.findViewById(R.id.search_view)
        recyclerView = view.findViewById(R.id.game_list_rv)

        val spanCount = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        viewManager = GridLayoutManager(context, spanCount)
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
        
        val iconContainer = bottomSheetView.findViewById<FrameLayout>(R.id.detail_icon_container)
        val iconIv = bottomSheetView.findViewById<ImageView>(R.id.detail_icon_iv)
        val coverMarkIv = bottomSheetView.findViewById<ImageView>(R.id.detail_cover_mark_iv)
        val iconTv = bottomSheetView.findViewById<TextView>(R.id.detail_icon_tv)
        val orientationTv = bottomSheetView.findViewById<TextView>(R.id.detail_orientation_tv)
        val nameTv = bottomSheetView.findViewById<TextView>(R.id.detail_name_tv)
        val sizeTv = bottomSheetView.findViewById<TextView>(R.id.detail_size_tv)
        val descTv = bottomSheetView.findViewById<TextView>(R.id.detail_desc_tv)
        val playBtn = bottomSheetView.findViewById<MaterialButton>(R.id.play_button)

        bottomSheetView.contentDescription = getString(R.string.game_detail_description, game.displayName)
        iconTv.text = game.iconLabel
        GameCoverStyler.apply(iconContainer, game, resources.getDimension(R.dimen.game_icon_corner))
        orientationTv.text = GameCoverStyler.orientationLabel(requireContext(), game.orientation)
        coverMarkIv.visibility = if (game.iconUri.isNullOrBlank()) View.VISIBLE else View.GONE
        iconTv.contentDescription = getString(R.string.game_icon_description, game.displayName)
        iconIv.contentDescription = getString(R.string.game_icon_description, game.displayName)
        GameIconLoader.bind(iconIv, iconTv, game)
        nameTv.text = game.displayName
        sizeTv.text = GameSizeFormatter.format(requireContext(), game.sizeBytes)
        descTv.text = game.description
        playBtn.contentDescription = getString(R.string.launch_game_named, game.displayName)

        playBtn.setOnClickListener {
            playBtn.isEnabled = false
            playBtn.text = getString(R.string.launching_game)
            playBtn.contentDescription = getString(R.string.launching_named, game.displayName)
            Snackbar.make(requireView(), getString(R.string.launching_named, game.displayName), Snackbar.LENGTH_SHORT).show()
            dialog.dismiss()
            launchGame(game)
        }

        dialog.setContentView(bottomSheetView)
        dialog.show()
    }

    private fun launchGame(game: GameEntry) {
        val intent = Intent(activity, GameActivity::class.java).apply {
            putExtra("GAME_PATH", game.file.path)
            putExtra(GameActivity.EXTRA_GAME_ORIENTATION, game.orientation)
        }
        startActivity(intent)
    }

    private fun setupSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                currentQuery = query.orEmpty()
                val count = viewAdapter.filter(currentQuery)
                updateCatalogState(count)
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentQuery = newText.orEmpty()
                val count = viewAdapter.filter(currentQuery)
                updateCatalogState(count)
                return true
            }
        })
    }

    private fun loadGames() {
        loadingContainer.visibility = View.VISIBLE
        emptyStateContainer.visibility = View.GONE
        recyclerView.visibility = View.INVISIBLE
        resultsStatusTv.text = getString(R.string.loading_games)
        lifecycleScope.launch {
            val gameEntries = GameCatalog.listGames(requireContext())
            allGameCount = gameEntries.size
            viewAdapter.updateData(gameEntries)
            loadingContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            updateCatalogState(viewAdapter.filteredCount())
        }
    }

    private fun updateCatalogState(visibleCount: Int) {
        val query = currentQuery.trim()
        resultsStatusTv.text = if (query.isEmpty()) {
            getString(R.string.games_available, allGameCount)
        } else {
            getString(R.string.games_matching, visibleCount, query)
        }
        val isEmpty = visibleCount == 0 && loadingContainer.visibility != View.VISIBLE
        emptyStateContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
}
