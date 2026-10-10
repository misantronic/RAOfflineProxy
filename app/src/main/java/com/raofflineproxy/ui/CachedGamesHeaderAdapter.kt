package com.raofflineproxy.ui

import android.view.LayoutInflater
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.raofflineproxy.R

class CachedGamesHeaderAdapter(
    private val onSmartCache: () -> Unit,
    private val onScan: () -> Unit,
    private val onClear: () -> Unit,
    private val onSearchChanged: (String) -> Unit,
    private val onSearchToggled: (Boolean) -> Unit
) : RecyclerView.Adapter<CachedGamesHeaderAdapter.ViewHolder>() {

    private var state: HeaderState = HeaderState()

    data class HeaderState(
        val smartCacheEnabled: Boolean = false,
        val showSmartCache: Boolean = true,
        val scanEnabled: Boolean = false,
        val clearEnabled: Boolean = true,
        val showNoCachedGames: Boolean = false,
        val statusText: String? = null,
        val searchOpen: Boolean = false,
        val searchQuery: String = ""
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnSmartCache: MaterialButton = view.findViewById(R.id.btn_smart_cache)
        val btnSearch: MaterialButton = view.findViewById(R.id.btn_search)
        val etSearch: EditText = view.findViewById(R.id.et_search)
        val btnScan: MaterialButton = view.findViewById(R.id.btn_scan_roms)
        val btnClear: MaterialButton = view.findViewById(R.id.btn_clear_cache)
        val tvScanHint: TextView = view.findViewById(R.id.tv_scan_hint)
        val tvNoCachedGames: TextView = view.findViewById(R.id.tv_no_cached_games)

        init {
            btnSmartCache.setOnClickListener { onSmartCache() }
            btnScan.setOnClickListener { onScan() }
            btnClear.setOnClickListener { onClear() }
            btnSearch.setOnClickListener { onSearchToggled(!state.searchOpen) }
            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString().orEmpty()
                    if (query != state.searchQuery) onSearchChanged(query)
                }
            })
            etSearch.setOnEditorActionListener { v, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
                hideKeyboard(v)
                true
            }
        }

        private fun hideKeyboard(target: View) {
            val imm = target.context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(target.windowToken, 0)
        }

        private fun bindSearch(s: HeaderState) {
            val wasOpen = etSearch.visibility == View.VISIBLE
            etSearch.visibility = if (s.searchOpen) View.VISIBLE else View.GONE
            btnSearch.setIconResource(if (s.searchOpen) R.drawable.ic_close else R.drawable.ic_search)
            if (etSearch.text.toString() != s.searchQuery) etSearch.setText(s.searchQuery)
            if (s.searchOpen && !wasOpen) {
                etSearch.requestFocus()
                etSearch.context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
            }
            if (!s.searchOpen && wasOpen) hideKeyboard(etSearch)
        }

        fun bind(s: HeaderState) {
            bindSearch(s)
            btnSmartCache.visibility = if (s.showSmartCache) View.VISIBLE else View.GONE
            btnSmartCache.isEnabled = s.smartCacheEnabled
            btnSmartCache.alpha = if (s.smartCacheEnabled) 1f else 0.38f

            btnScan.isEnabled = s.scanEnabled
            btnScan.alpha = if (s.scanEnabled) 1f else 0.38f

            btnClear.isEnabled = s.clearEnabled
            btnClear.alpha = if (s.clearEnabled) 1f else 0.38f

            tvScanHint.text = s.statusText
            tvScanHint.visibility =
                if (s.statusText.isNullOrEmpty() || s.searchOpen) View.GONE else View.VISIBLE

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
