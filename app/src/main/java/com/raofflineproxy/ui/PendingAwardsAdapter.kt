package com.raofflineproxy.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.raofflineproxy.R
import com.raofflineproxy.data.PendingAwardUi
import com.raofflineproxy.databinding.ItemPendingAwardBinding

class PendingAwardsAdapter : ListAdapter<PendingAwardUi, PendingAwardsAdapter.ViewHolder>(DIFF) {

    class ViewHolder(private val binding: ItemPendingAwardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(award: PendingAwardUi) {
            val ctx = binding.root.context
            binding.tvGameTitle.text = award.gameTitle
            binding.tvAchievementTitle.text = if (award.hardcore)
                ctx.getString(R.string.achievement_title_hardcore, award.achievementTitle)
            else
                award.achievementTitle
            binding.tvPoints.text = ctx.getString(R.string.points_format, award.points)
            binding.ivGameIcon.loadOrClear(award.gameIconUrl)
            binding.ivBadge.loadOrClear(award.badgeUrl)
            if (award.lastError != null) {
                binding.tvLastError.text = award.lastError
                binding.tvLastError.visibility = android.view.View.VISIBLE
            } else {
                binding.tvLastError.visibility = android.view.View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemPendingAwardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PendingAwardUi>() {
            override fun areItemsTheSame(a: PendingAwardUi, b: PendingAwardUi) =
                a.achievementTitle == b.achievementTitle && a.gameTitle == b.gameTitle
            override fun areContentsTheSame(a: PendingAwardUi, b: PendingAwardUi) = a == b
        }
    }
}
