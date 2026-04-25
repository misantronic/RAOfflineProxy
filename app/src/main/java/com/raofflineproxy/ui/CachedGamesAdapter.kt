package com.raofflineproxy.ui

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

class CachedGamesAdapter(
    private val onDelete: (CachedGame) -> Unit
) : ListAdapter<CachedGame, CachedGamesAdapter.ViewHolder>(DIFF) {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    private val expandedGameIds = mutableSetOf<String>()

    inner class ViewHolder(private val binding: ItemCachedGameBinding) :
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemCachedGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CachedGame>() {
            override fun areItemsTheSame(a: CachedGame, b: CachedGame) = a.gameId == b.gameId
            override fun areContentsTheSame(a: CachedGame, b: CachedGame) = a == b
        }
    }
}
