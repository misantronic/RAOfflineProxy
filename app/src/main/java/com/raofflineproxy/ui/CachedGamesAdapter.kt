package com.raofflineproxy.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.raofflineproxy.R
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.data.UnlockedAchievement
import com.raofflineproxy.databinding.ItemCachedGameBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ImageView.loadOrClear(url: String?) {
    if (url != null) load(url) { crossfade(true) }
    else setImageDrawable(null)
}

sealed interface CachedGameListItem {
    data class ConsoleHeader(
        val consoleId: Int,
        val consoleName: String,
        val gameCount: Int,
        val isCollapsed: Boolean
    ) : CachedGameListItem

    data class GameItem(val game: CachedGame) : CachedGameListItem
}

class CachedGamesAdapter(
    private val onHeaderClick: (consoleId: Int) -> Unit,
    private val onDelete: (CachedGame) -> Unit
) : ListAdapter<CachedGameListItem, RecyclerView.ViewHolder>(DIFF) {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    private val expandedGameIds = mutableSetOf<String>()

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvConsoleName: TextView = itemView.findViewById(R.id.tv_console_name)
        private val ivChevron: ImageView = itemView.findViewById(R.id.iv_collapse_chevron)

        @SuppressLint("SetTextI18n")
        fun bind(header: CachedGameListItem.ConsoleHeader) {
            tvConsoleName.text = "${header.consoleName} (${header.gameCount})"
            ivChevron.rotation = if (header.isCollapsed) 0f else 180f
            itemView.setOnClickListener { onHeaderClick(header.consoleId) }
        }
    }

    inner class GameViewHolder(private val binding: ItemCachedGameBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(game: CachedGame) {
            val expanded = expandedGameIds.contains(game.gameId)
            binding.tvGameTitle.text = game.title
            binding.tvGameMeta.text = buildMetaText(game)
            binding.btnDeleteGame.setOnClickListener { onDelete(game) }
            binding.ivGameIcon.loadOrClear(game.imageIconUrl)
            bindExpandedState(game, expanded)

            binding.layoutGameRow.setOnClickListener {
                if (game.unlockedAchievements.isEmpty()) return@setOnClickListener
                toggleExpanded(game.gameId)
            }
            binding.ivExpand.setOnClickListener {
                if (game.unlockedAchievements.isEmpty()) return@setOnClickListener
                toggleExpanded(game.gameId)
            }
        }

        private fun bindExpandedState(game: CachedGame, expanded: Boolean) {
            val hasAchievements = game.unlockedAchievements.isNotEmpty()
            binding.ivExpand.visibility = if (hasAchievements) View.VISIBLE else View.INVISIBLE
            binding.ivExpand.rotation = if (expanded) 180f else 0f
            binding.layoutGameRow.contentDescription = binding.root.context.getString(
                if (expanded) R.string.cached_game_collapse else R.string.cached_game_expand
            )

            binding.layoutUnlockedAchievements.removeAllViews()
            binding.layoutUnlockedAchievements.visibility = if (expanded && hasAchievements) View.VISIBLE else View.GONE
            if (!expanded || !hasAchievements) return

            val inflater = LayoutInflater.from(binding.root.context)
            game.unlockedAchievements.forEach { achievement ->
                binding.layoutUnlockedAchievements.addView(
                    inflateAchievement(inflater, binding.layoutUnlockedAchievements, achievement)
                )
            }
        }

        private fun inflateAchievement(
            inflater: LayoutInflater,
            parent: LinearLayout,
            achievement: UnlockedAchievement
        ): View {
            val view = inflater.inflate(R.layout.item_unlocked_achievement, parent, false)
            view.findViewById<ImageView>(R.id.iv_badge).loadOrClear(achievement.badgeUrl)
            view.findViewById<TextView>(R.id.tv_achievement_title).text = achievement.title
            view.findViewById<TextView>(R.id.tv_achievement_description).apply {
                text = achievement.description ?: context.getString(R.string.cached_game_no_description)
            }
            view.findViewById<TextView>(R.id.tv_points).text = view.context.getString(
                R.string.points_format,
                achievement.points
            )
            return view
        }

        private fun toggleExpanded(gameId: String) {
            if (!expandedGameIds.add(gameId)) {
                expandedGameIds.remove(gameId)
            }
            notifyItemChanged(bindingAdapterPosition)
        }

        private fun buildMetaText(game: CachedGame): String {
            val context = binding.root.context
            val dateText = dateFormat.format(Date(game.cachedAt))

            if (game.pendingAwardCount == 0) {
                return context.getString(
                    R.string.game_meta_format,
                    game.unlockedCount,
                    game.totalAchievements,
                    dateText
                )
            }

            val pendingText = binding.root.resources.getQuantityString(
                R.plurals.notification_pending_awards,
                game.pendingAwardCount,
                game.pendingAwardCount
            )
            return context.getString(
                R.string.cached_game_meta_with_pending,
                game.unlockedCount,
                game.totalAchievements,
                pendingText,
                dateText
            )
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is CachedGameListItem.ConsoleHeader -> VIEW_TYPE_HEADER
        is CachedGameListItem.GameItem -> VIEW_TYPE_GAME
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_console_header, parent, false)
            )
            else -> GameViewHolder(
                ItemCachedGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is CachedGameListItem.ConsoleHeader -> (holder as HeaderViewHolder).bind(item)
            is CachedGameListItem.GameItem -> (holder as GameViewHolder).bind(item.game)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_GAME = 1

        private val DIFF = object : DiffUtil.ItemCallback<CachedGameListItem>() {
            override fun areItemsTheSame(a: CachedGameListItem, b: CachedGameListItem): Boolean =
                when {
                    a is CachedGameListItem.ConsoleHeader && b is CachedGameListItem.ConsoleHeader ->
                        a.consoleId == b.consoleId
                    a is CachedGameListItem.GameItem && b is CachedGameListItem.GameItem ->
                        a.game.gameId == b.game.gameId
                    else -> false
                }

            override fun areContentsTheSame(a: CachedGameListItem, b: CachedGameListItem): Boolean =
                a == b
        }
    }
}
