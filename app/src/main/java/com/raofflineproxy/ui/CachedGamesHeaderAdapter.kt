package com.raofflineproxy.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.raofflineproxy.R

class CachedGamesHeaderAdapter(
    private val onScan: () -> Unit,
    private val onAdd: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onClear: () -> Unit
) : RecyclerView.Adapter<CachedGamesHeaderAdapter.ViewHolder>() {

    private var state: HeaderState = HeaderState()

    data class HeaderState(
        val scanEnabled: Boolean = false,
        val refreshEnabled: Boolean = false,
        val clearEnabled: Boolean = true,
        val showNoCachedGames: Boolean = false,
        val statusText: String? = null
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnRefresh: MaterialButton = view.findViewById(R.id.btn_refresh_games)
        val btnAdd: MaterialButton = view.findViewById(R.id.btn_add_rom)
        val btnScan: MaterialButton = view.findViewById(R.id.btn_scan_roms)
        val btnClear: MaterialButton = view.findViewById(R.id.btn_clear_cache)
        val tvScanHint: TextView = view.findViewById(R.id.tv_scan_hint)
        val tvNoCachedGames: TextView = view.findViewById(R.id.tv_no_cached_games)

        init {
            btnScan.setOnClickListener { onScan() }
            btnAdd.setOnClickListener { onAdd() }
            btnRefresh.setOnClickListener { onRefresh() }
            btnClear.setOnClickListener { onClear() }
        }

        fun bind(s: HeaderState) {
            btnScan.isEnabled = s.scanEnabled
            btnAdd.isEnabled = s.scanEnabled
            btnScan.alpha = if (s.scanEnabled) 1f else 0.38f
            btnAdd.alpha = if (s.scanEnabled) 1f else 0.38f

            btnRefresh.isEnabled = s.refreshEnabled
            btnRefresh.alpha = if (s.refreshEnabled) 1f else 0.38f

            btnClear.isEnabled = s.clearEnabled
            btnClear.alpha = if (s.clearEnabled) 1f else 0.38f

            tvScanHint.text = s.statusText
            tvScanHint.visibility = if (s.statusText.isNullOrEmpty()) View.GONE else View.VISIBLE

            tvNoCachedGames.visibility = if (s.showNoCachedGames) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cached_games_header, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(state)

    override fun getItemCount() = 1

    fun update(newState: HeaderState) {
        if (newState == state) return
        state = newState
        notifyItemChanged(0)
    }
}
