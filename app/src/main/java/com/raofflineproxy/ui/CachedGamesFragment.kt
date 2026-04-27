package com.raofflineproxy.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.raofflineproxy.MAX_CACHED_GAMES
import com.raofflineproxy.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CachedGamesFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private val romFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        viewModel.scanRoms(uri)
    }

    private val addRomLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = buildList {
            data.data?.let(::add)
            val clipData = data.clipData
            if (clipData != null) {
                for (index in 0 until clipData.itemCount) {
                    add(clipData.getItemAt(index).uri)
                }
            }
        }.distinct()
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.addRom(uris)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_cached_games, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val gamesAdapter = CachedGamesAdapter(
            onDelete = viewModel::deleteCachedGame
        )
        val headerAdapter = CachedGamesHeaderAdapter(
            onScan = { romFolderPickerLauncher.launch(null) },
            onAdd = {
                addRomLauncher.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                )
            },
            onRefresh = viewModel::refreshGames,
            onClear = {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clear_cache_confirm_title)
                    .setMessage(R.string.clear_cache_confirm_message)
                    .setPositiveButton(R.string.clear_action) { _, _ ->
                        viewModel.clearCache()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )

        view.findViewById<RecyclerView>(R.id.rv_cached_games).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(headerAdapter, gamesAdapter)
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
            itemAnimator = null
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            viewModel.cachedGames.collect { games ->
                gamesAdapter.submitList(games)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                val actionsEnabled = state.proxyRunning
                    && state.isOnline
                    && !state.scanInProgress
                val scanEnabled = state.proxyRunning
                    && state.isOnline
                    && !state.scanInProgress
                    && state.cachedGames.size < MAX_CACHED_GAMES
                val statusText = if (state.proxyRunning) {
                    getString(R.string.cached_games_counter, state.cachedGames.size, MAX_CACHED_GAMES)
                } else {
                    getString(R.string.cached_games_scan_hint)
                }
                headerAdapter.update(
                    CachedGamesHeaderAdapter.HeaderState(
                        scanEnabled = scanEnabled,
                        refreshEnabled = actionsEnabled && state.cachedGames.isNotEmpty(),
                        clearEnabled = !state.proxyRunning && !state.scanInProgress,
                        showNoCachedGames = state.cachedGames.isEmpty(),
                        statusText = statusText
                    )
                )
            }
        }
    }
}
