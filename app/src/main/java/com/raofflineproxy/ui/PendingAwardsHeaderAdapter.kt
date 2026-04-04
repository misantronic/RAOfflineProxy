package com.raofflineproxy.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.raofflineproxy.R

class PendingAwardsHeaderAdapter : RecyclerView.Adapter<PendingAwardsHeaderAdapter.ViewHolder>() {

    private var showEmpty: Boolean = false

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNoPendingAwards: TextView = view.findViewById(R.id.tv_no_pending_awards)

        fun bind(showEmpty: Boolean) {
            tvNoPendingAwards.visibility = if (showEmpty) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pending_awards_header, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(showEmpty)

    override fun getItemCount() = 1

    fun update(showEmpty: Boolean) {
        this.showEmpty = showEmpty
        notifyItemChanged(0)
    }
}
