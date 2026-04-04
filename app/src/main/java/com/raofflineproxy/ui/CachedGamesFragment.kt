package com.raofflineproxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.snackbar.Snackbar
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
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        viewModel.scanRoms(uri)
    }

    private val addRomLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            requireContext().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
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
            onAdd = { addRomLauncher.launch(arrayOf("*/*")) },
            onRefresh = viewModel::refreshGames,
            onClear = viewModel::clearCache
        )

        view.findViewById<RecyclerView>(R.id.rv_cached_games).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(headerAdapter, gamesAdapter)
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            viewModel.cachedGames.collect { games ->
                gamesAdapter.submitList(games)
            }
        }

        var snackbar: Snackbar? = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                val scanEnabled = state.proxyRunning && state.isOnline && !state.scanInProgress
                headerAdapter.update(
                    CachedGamesHeaderAdapter.HeaderState(
                        scanEnabled = scanEnabled,
                        refreshEnabled = scanEnabled && state.cachedGames.isNotEmpty(),
                        clearEnabled = !state.scanInProgress,
                        showNoCachedGames = state.cachedGames.isEmpty(),
                        showScanHint = !scanEnabled && !state.scanInProgress
                    )
                )

                val msg = state.scanProgress
                when {
                    msg == null -> snackbar?.dismiss().also { snackbar = null }
                    state.scanInProgress -> {
                        val sb = snackbar ?: Snackbar.make(view, msg, Snackbar.LENGTH_INDEFINITE)
                            .also { snackbar = it }
                        sb.setText(msg)
                        sb.show()
                    }
                    else -> {
                        snackbar?.dismiss()
                        snackbar = Snackbar.make(view, msg, Snackbar.LENGTH_LONG).also { it.show() }
                    }
                }
            }
        }
    }
}
