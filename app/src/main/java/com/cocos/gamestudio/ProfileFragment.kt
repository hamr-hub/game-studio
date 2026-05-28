package com.cocos.gamestudio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.os.Build
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {

    private lateinit var gamesCountTv: TextView
    private lateinit var recentGamesRv: RecyclerView
    private lateinit var galleryRv: RecyclerView
    private lateinit var recentEmptyTv: TextView
    private lateinit var galleryEmptyTv: TextView
    private lateinit var adapter: GameListActivity.GameAdapter
    private lateinit var galleryAdapter: GalleryAdapter
    private lateinit var settingsBtn: View
    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadGallery()
        } else {
            galleryAdapter.updateData(emptyList())
            updateGalleryEmpty(true, getString(R.string.gallery_permission_denied))
        }
    }

    companion object {
        private const val PREFS_NAME = "user_prefs"
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
        gamesCountTv = view.findViewById<TextView>(R.id.profile_games_count_tv)
        recentGamesRv = view.findViewById<RecyclerView>(R.id.recent_games_rv)
        galleryRv = view.findViewById(R.id.gallery_rv)
        recentEmptyTv = view.findViewById(R.id.recent_empty_tv)
        galleryEmptyTv = view.findViewById(R.id.gallery_empty_tv)
        settingsBtn = view.findViewById(R.id.settings_btn)

        settingsBtn.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        
        avatarIv.setImageResource(R.drawable.ic_nav_profile)
        nicknameTv.text = prefs.getString("nickname", getString(R.string.profile_default_nickname))
        levelTv.text = getString(R.string.profile_level, prefs.getInt("level", DEFAULT_LEVEL))
        val minutesPlayed = prefs.getLong("minutes_played", DEFAULT_MINUTES)
        minutesTv.text = minutesPlayed.toString()

        recentGamesRv.layoutManager = LinearLayoutManager(context)
        adapter = GameListActivity.GameAdapter(emptyList()) { game ->
            val intent = Intent(requireContext(), GameActivity::class.java).apply {
                putExtra("GAME_PATH", game.file.path)
                putExtra(GameActivity.EXTRA_GAME_ORIENTATION, game.orientation)
            }
            startActivity(intent)
        }
        recentGamesRv.adapter = adapter

        galleryRv.layoutManager = GridLayoutManager(context, 3)
        galleryAdapter = GalleryAdapter(emptyList())
        galleryRv.adapter = galleryAdapter

        loadData()
        ensureGalleryPermission()

        return view
    }

    private fun loadGallery() {
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                val list = mutableListOf<Uri>()
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                } else {
                    null
                }
                val selectionArgs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    arrayOf("%CocosStudio%")
                } else {
                    null
                }
                
                context?.contentResolver?.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        list.add(contentUri)
                    }
                }
                list
            }
            galleryAdapter.updateData(images)
            updateGalleryEmpty(images.isEmpty())
        }
    }

    private fun ensureGalleryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            loadGallery()
        } else {
            mediaPermissionLauncher.launch(permission)
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val context = requireContext()
            val prefs = context.getSharedPreferences(PREFS_NAME, 0)
            val recentStr = prefs.getString("recent_games", "") ?: ""
            
            // Ensure catalog is loaded
            GameCatalog.listGames(context) 
            val recentGames = GameCatalog.loadLastPlayed(context, recentStr)
            
            adapter.updateData(recentGames)
            gamesCountTv.text = recentGames.size.toString()
            val isEmpty = recentGames.isEmpty()
            recentEmptyTv.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recentGamesRv.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun updateGalleryEmpty(isEmpty: Boolean, message: String? = null) {
        galleryEmptyTv.text = message ?: getString(R.string.no_captures)
        galleryEmptyTv.visibility = if (isEmpty) View.VISIBLE else View.GONE
        galleryRv.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    class GalleryAdapter(private var images: List<Uri>) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.gallery_item_iv)
        }

        fun updateData(newImages: List<Uri>) {
            images = newImages
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.gallery_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.imageView.setImageResource(R.drawable.ic_capture_placeholder)
            holder.imageView.contentDescription = holder.imageView.context.getString(
                R.string.capture_description,
                position + 1,
            )
            holder.imageView.setImageURI(images[position])
        }

        override fun getItemCount() = images.size
    }
}
