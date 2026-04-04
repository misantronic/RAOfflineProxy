package com.raofflineproxy.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.raofflineproxy.R
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.databinding.ItemCachedGameBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CachedGamesAdapter(
    private val onDelete: (CachedGame) -> Unit
) : ListAdapter<CachedGame, CachedGamesAdapter.ViewHolder>(DIFF) {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    inner class ViewHolder(private val binding: ItemCachedGameBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(game: CachedGame) {
            binding.tvGameTitle.text = game.title
            binding.tvGameMeta.text = binding.root.context.getString(
                R.string.game_meta_format,
                game.unlockedCount,
                game.totalAchievements,
                dateFormat.format(Date(game.cachedAt))
            )
            binding.btnDeleteGame.setOnClickListener { onDelete(game) }

            if (game.imageIconUrl != null) {
                binding.ivGameIcon.load(game.imageIconUrl) { crossfade(true) }
            } else {
                binding.ivGameIcon.setImageDrawable(null)
            }
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
