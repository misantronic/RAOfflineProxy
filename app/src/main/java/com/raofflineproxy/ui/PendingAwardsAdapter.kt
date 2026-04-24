package com.raofflineproxy.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.raofflineproxy.R
import com.raofflineproxy.data.PendingAwardUi
import com.raofflineproxy.databinding.ItemPendingAwardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PendingAwardsAdapter(
    private val onDelete: (PendingAwardUi) -> Unit
) : ListAdapter<PendingAwardUi, PendingAwardsAdapter.ViewHolder>(DIFF) {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    class ViewHolder(
        private val binding: ItemPendingAwardBinding,
        private val onDelete: (PendingAwardUi) -> Unit
    ) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(award: PendingAwardUi, dateFormat: SimpleDateFormat) {
            val ctx = binding.root.context
            binding.tvGameTitle.text = award.gameTitle
            val achievementTitle = if (award.hardcore)
                ctx.getString(R.string.achievement_title_hardcore, award.achievementTitle)
            else
                award.achievementTitle
            val queuedAt = dateFormat.format(Date(award.queuedAt))
            val title = "$achievementTitle · $queuedAt"
            binding.tvAchievementTitle.text = SpannableStringBuilder(title).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, achievementTitle.length, 0)
            }
            binding.tvPoints.text = ctx.getString(R.string.points_format, award.points)
            binding.ivGameIcon.loadOrClear(award.gameIconUrl)
            binding.ivBadge.loadOrClear(award.badgeUrl)
            if (award.lastError != null) {
                binding.tvLastError.text = award.lastError
                binding.tvLastError.visibility = View.VISIBLE
            } else {
                binding.tvLastError.visibility = View.GONE
            }
            binding.btnDeleteAward.setOnClickListener { onDelete(award) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemPendingAwardBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        onDelete
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), dateFormat)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PendingAwardUi>() {
            override fun areItemsTheSame(a: PendingAwardUi, b: PendingAwardUi) = a.id == b.id
            override fun areContentsTheSame(a: PendingAwardUi, b: PendingAwardUi) = a == b
        }
    }
}
