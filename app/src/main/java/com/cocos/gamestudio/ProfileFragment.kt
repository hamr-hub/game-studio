package com.cocos.gamestudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {

    private lateinit var nicknameTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var levelTv: TextView
    private lateinit var pointsTv: TextView
    private lateinit var minutesTv: TextView
    private lateinit var streakTv: TextView
    private lateinit var xpCaptionTv: TextView
    private lateinit var xpProgress: ProgressBar
    private lateinit var badgesRv: RecyclerView
    private lateinit var rewardsRv: RecyclerView
    private lateinit var historyRv: RecyclerView
    private lateinit var leaderboardRv: RecyclerView
    private lateinit var galleryRv: RecyclerView
    private lateinit var historyEmptyTv: TextView
    private lateinit var leaderboardEmptyTv: TextView
    private lateinit var galleryEmptyTv: TextView
    private lateinit var badgeAdapter: BadgeAdapter
    private lateinit var rewardAdapter: RewardAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var leaderboardAdapter: LeaderboardAdapter
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        val avatarIv = view.findViewById<ImageView>(R.id.profile_avatar_iv)
        nicknameTv = view.findViewById(R.id.profile_nickname_tv)
        titleTv = view.findViewById(R.id.profile_title_tv)
        levelTv = view.findViewById(R.id.profile_level_tv)
        pointsTv = view.findViewById(R.id.profile_points_tv)
        minutesTv = view.findViewById(R.id.profile_minutes_tv)
        streakTv = view.findViewById(R.id.profile_streak_tv)
        xpCaptionTv = view.findViewById(R.id.profile_xp_caption_tv)
        xpProgress = view.findViewById(R.id.profile_xp_progress)
        badgesRv = view.findViewById(R.id.badges_rv)
        rewardsRv = view.findViewById(R.id.rewards_rv)
        historyRv = view.findViewById(R.id.recent_games_rv)
        leaderboardRv = view.findViewById(R.id.leaderboard_rv)
        galleryRv = view.findViewById(R.id.gallery_rv)
        historyEmptyTv = view.findViewById(R.id.recent_empty_tv)
        leaderboardEmptyTv = view.findViewById(R.id.leaderboard_empty_tv)
        galleryEmptyTv = view.findViewById(R.id.gallery_empty_tv)
        settingsBtn = view.findViewById(R.id.settings_btn)

        avatarIv.setImageResource(R.drawable.ic_nav_profile)
        settingsBtn.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        setupAdapters()
        loadData()
        ensureGalleryPermission()

        return view
    }

    override fun onResume() {
        super.onResume()
        if (::historyAdapter.isInitialized) {
            loadData()
        }
    }

    private fun setupAdapters() {
        badgeAdapter = BadgeAdapter(emptyList())
        badgesRv.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        badgesRv.adapter = badgeAdapter

        rewardAdapter = RewardAdapter(emptyList())
        rewardsRv.layoutManager = LinearLayoutManager(context)
        rewardsRv.adapter = rewardAdapter

        historyAdapter = HistoryAdapter(emptyList()) { game ->
            startActivity(Intent(requireContext(), GameActivity::class.java).apply {
                putExtra("GAME_PATH", game.path)
                putExtra(GameActivity.EXTRA_GAME_ORIENTATION, game.orientation)
            })
        }
        historyRv.layoutManager = LinearLayoutManager(context)
        historyRv.adapter = historyAdapter

        leaderboardAdapter = LeaderboardAdapter(emptyList())
        leaderboardRv.layoutManager = LinearLayoutManager(context)
        leaderboardRv.adapter = leaderboardAdapter

        galleryAdapter = GalleryAdapter(emptyList())
        galleryRv.layoutManager = GridLayoutManager(context, 3)
        galleryRv.adapter = galleryAdapter
    }

    private fun loadData() {
        lifecycleScope.launch {
            val context = requireContext()
            val catalog = GameCatalog.listGames(context)
            val snapshot = withContext(Dispatchers.Default) {
                PlayerProgressRepository.snapshot(context, catalog)
            }
            bindProgress(snapshot)
        }
    }

    private fun bindProgress(snapshot: PlayerProgressSnapshot) {
        nicknameTv.text = snapshot.nickname
        titleTv.text = snapshot.title
        levelTv.text = getString(R.string.profile_level, snapshot.level)
        pointsTv.text = snapshot.points.toString()
        minutesTv.text = PlayerProgressRepository.formatDuration(snapshot.totalPlayMs)
        streakTv.text = snapshot.streakDays.toString()
        xpCaptionTv.text = getString(
            R.string.profile_level_progress,
            snapshot.level,
            snapshot.xpProgress,
            snapshot.xpTarget,
        )
        xpProgress.max = snapshot.xpTarget
        xpProgress.progress = snapshot.xpProgress

        badgeAdapter.updateData(snapshot.achievements)
        rewardAdapter.updateData(snapshot.rewards)
        historyAdapter.updateData(snapshot.history.take(6))
        leaderboardAdapter.updateData(snapshot.leaderboard)

        val hasHistory = snapshot.history.isNotEmpty()
        historyEmptyTv.visibility = if (hasHistory) View.GONE else View.VISIBLE
        historyRv.visibility = if (hasHistory) View.VISIBLE else View.GONE

        val hasLeaderboard = snapshot.leaderboard.isNotEmpty()
        leaderboardEmptyTv.visibility = if (hasLeaderboard) View.GONE else View.VISIBLE
        leaderboardRv.visibility = if (hasLeaderboard) View.VISIBLE else View.GONE
    }

    private fun loadGallery() {
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                val list = mutableListOf<Uri>()
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                } else {
                    null
                }
                val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                        list.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()))
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

    private fun updateGalleryEmpty(isEmpty: Boolean, message: String? = null) {
        galleryEmptyTv.text = message ?: getString(R.string.no_captures)
        galleryEmptyTv.visibility = if (isEmpty) View.VISIBLE else View.GONE
        galleryRv.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    class BadgeAdapter(private var badges: List<Achievement>) : RecyclerView.Adapter<BadgeAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(R.id.badge_icon_iv)
            val titleView: TextView = view.findViewById(R.id.badge_title_tv)
            val statusView: TextView = view.findViewById(R.id.badge_status_tv)
        }

        fun updateData(newBadges: List<Achievement>) {
            badges = newBadges
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.badge_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val badge = badges[position]
            val context = holder.itemView.context
            holder.iconView.setImageResource(badge.iconRes)
            holder.iconView.alpha = if (badge.unlocked) 1f else 0.35f
            holder.titleView.text = badge.title
            holder.titleView.alpha = if (badge.unlocked) 1f else 0.6f
            holder.statusView.text = context.getString(
                if (badge.unlocked) R.string.achievement_unlocked else R.string.achievement_locked
            )
            holder.itemView.contentDescription = "${badge.title}. ${badge.description}. ${holder.statusView.text}"
        }

        override fun getItemCount() = badges.size
    }

    class RewardAdapter(private var rewards: List<RewardGoal>) : RecyclerView.Adapter<RewardAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleView: TextView = view.findViewById(R.id.reward_title_tv)
            val descView: TextView = view.findViewById(R.id.reward_desc_tv)
            val progressBar: ProgressBar = view.findViewById(R.id.reward_progress_bar)
            val pointsView: TextView = view.findViewById(R.id.reward_points_tv)
            val progressView: TextView = view.findViewById(R.id.reward_progress_tv)
        }

        fun updateData(newRewards: List<RewardGoal>) {
            rewards = newRewards
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.reward_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val reward = rewards[position]
            val context = holder.itemView.context
            holder.titleView.text = reward.title
            holder.descView.text = reward.description
            holder.progressBar.max = reward.target
            holder.progressBar.progress = reward.progress
            holder.pointsView.text = context.getString(R.string.reward_points, reward.points)
            holder.progressView.text = context.getString(R.string.reward_progress, reward.progress, reward.target)
            holder.itemView.contentDescription = "${reward.title}. ${reward.description}. ${holder.progressView.text}"
        }

        override fun getItemCount() = rewards.size
    }

    class HistoryAdapter(
        private var games: List<GameProgress>,
        private val onClick: (GameProgress) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: TextView = view.findViewById(R.id.history_icon_tv)
            val titleView: TextView = view.findViewById(R.id.history_title_tv)
            val metaView: TextView = view.findViewById(R.id.history_meta_tv)
            val durationView: TextView = view.findViewById(R.id.history_duration_tv)
        }

        fun updateData(newGames: List<GameProgress>) {
            games = newGames
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.profile_history_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val game = games[position]
            val context = holder.itemView.context
            holder.iconView.background = iconBackground(context, game.iconColor)
            holder.iconView.text = game.iconLabel
            holder.titleView.text = game.displayName
            holder.metaView.text = context.getString(
                R.string.history_game_meta,
                PlayerProgressRepository.formatDuration(game.totalPlayMs),
                game.sessionCount,
            )
            holder.durationView.text = PlayerProgressRepository.formatDuration(game.totalPlayMs)
            holder.itemView.contentDescription = "${game.displayName}. ${holder.metaView.text}"
            holder.itemView.setOnClickListener { onClick(game) }
        }

        override fun getItemCount() = games.size
    }

    class LeaderboardAdapter(
        private var entries: List<LeaderboardEntry>,
    ) : RecyclerView.Adapter<LeaderboardAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val rankView: TextView = view.findViewById(R.id.leaderboard_rank_tv)
            val iconView: TextView = view.findViewById(R.id.leaderboard_icon_tv)
            val titleView: TextView = view.findViewById(R.id.leaderboard_title_tv)
            val metaView: TextView = view.findViewById(R.id.leaderboard_meta_tv)
            val scoreView: TextView = view.findViewById(R.id.leaderboard_score_tv)
        }

        fun updateData(newEntries: List<LeaderboardEntry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.leaderboard_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = entries[position]
            val context = holder.itemView.context
            holder.rankView.text = context.getString(R.string.leaderboard_rank, item.rank)
            holder.iconView.background = iconBackground(context, item.iconColor)
            holder.iconView.text = item.iconLabel
            holder.titleView.text = item.displayName
            holder.metaView.text = item.metaText
            holder.scoreView.text = item.scoreText
            holder.itemView.contentDescription = "${holder.rankView.text}. ${item.displayName}. ${item.scoreText}"
        }

        override fun getItemCount() = entries.size
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

    companion object {
        private fun iconBackground(context: android.content.Context, color: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.resources.getDimension(R.dimen.radius_md)
                setColor(color)
                setStroke(1, ContextCompat.getColor(context, R.color.surface))
            }
        }
    }
}
