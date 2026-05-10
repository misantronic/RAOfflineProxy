package com.raofflineproxy.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.raofflineproxy.R

class AwardsHistoryHeaderAdapter(
    private val onClear: () -> Unit
) : RecyclerView.Adapter<AwardsHistoryHeaderAdapter.ViewHolder>() {

    private var state: HeaderState = HeaderState()

    data class HeaderState(
        val awardCount: Int = 0,
        val clearEnabled: Boolean = false,
        val showEmpty: Boolean = false
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvCount: TextView = view.findViewById(R.id.tv_awards_history_count)
        private val btnClear: MaterialButton = view.findViewById(R.id.btn_clear_awards_history)
        private val tvEmpty: TextView = view.findViewById(R.id.tv_no_awards_history)

        init {
            btnClear.setOnClickListener { onClear() }
        }

        fun bind(headerState: HeaderState) {
            tvCount.text = itemView.context.getString(
                R.string.awards_history_counter,
                headerState.awardCount
            )
            btnClear.isEnabled = headerState.clearEnabled
            btnClear.alpha = if (headerState.clearEnabled) 1f else 0.38f
            tvEmpty.visibility = if (headerState.showEmpty) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_awards_history_header, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(state)

    override fun getItemCount() = 1

    fun update(newState: HeaderState) {
        if (newState == state) return
        state = newState
        notifyItemChanged(0)
    }
}
