package com.raofflineproxy.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
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
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "CachedGamesFragment"

class CachedGamesFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var romPickerUsed = false

    private val romFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        romPickerUsed = true
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
        romPickerUsed = true
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
            onSmartCache = viewModel::startSmartCache,
            onScan = { romFolderPickerLauncher.launch(createRomFolderPickerIntent()) },
            onAdd = {
                addRomLauncher.launch(createAddRomIntent())
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
                    .create()
                    .also { it.setCanceledOnTouchOutside(false) }
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
                val actionsEnabled = state.hasLoginCredentials
                    && state.isOnline
                    && !state.scanInProgress
                val smartCacheEnabled = actionsEnabled
                    && state.cachedGames.size < MAX_CACHED_GAMES
                val scanEnabled = state.hasLoginCredentials
                    && state.isOnline
                    && !state.scanInProgress
                    && state.cachedGames.size < MAX_CACHED_GAMES
                val statusText = when {
                    !state.hasLoginCredentials -> getString(R.string.cached_games_credentials_hint)
                    !state.isOnline -> getString(R.string.cached_games_offline_hint)
                    else -> getString(R.string.cached_games_counter, state.cachedGames.size, MAX_CACHED_GAMES)
                }
                headerAdapter.update(
                    CachedGamesHeaderAdapter.HeaderState(
                        smartCacheEnabled = smartCacheEnabled,
                        scanEnabled = scanEnabled,
                        refreshEnabled = actionsEnabled && state.cachedGames.isNotEmpty(),
                        clearEnabled = state.cachedGames.isNotEmpty() && !state.scanInProgress,
                        showNoCachedGames = state.cachedGames.isEmpty(),
                        statusText = statusText
                    )
                )
            }
        }
    }

    private fun createRomFolderPickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            val initialUri = guessRomFolderInitialUri()
            Log.i(TAG, "ROM folder picker initialUri=$initialUri candidates=${romFolderCandidates(requireContext())}")
            initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

    private fun createAddRomIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            val initialUri = guessRomFolderInitialUri()
            Log.i(TAG, "Add ROM picker initialUri=$initialUri candidates=${romFolderCandidates(requireContext())}")
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    private fun guessRomFolderInitialUri() =
        if (romPickerUsed) null else {
            val context = requireContext()
            (existingRomFolderCandidates(context).firstOrNull() ?: preferredRomPickerRoots(context).firstOrNull())
                ?.let(::initialTreeUriForPath)
        }

    private fun existingRomFolderCandidates(context: Context): List<String> =
        romFolderCandidates(context).filter { File(it).isDirectory }

    private fun preferredRomPickerRoots(context: Context): List<String> {
        val removableRoots = linkedSetOf<String>()
        val otherRoots = linkedSetOf<String>()

        context.getExternalFilesDirs(null)
            .filterNotNull()
            .forEach { file ->
                val root = file.absolutePath.substringBefore("/Android/data", missingDelimiterValue = "")
                    .trim()
                    .trimEnd('/')
                if (root.isBlank()) return@forEach
                if (Environment.isExternalStorageRemovable(file)) {
                    removableRoots.add(root)
                } else {
                    otherRoots.add(root)
                }
            }

        listOf(
            Environment.getExternalStorageDirectory().path,
            "/storage/emulated/0",
            "/storage/self/primary"
        ).forEach(otherRoots::add)

        return (removableRoots + otherRoots).toList()
    }

    private fun romFolderCandidates(context: Context): List<String> {
        val roots = preferredRomPickerRoots(context)
        val names = setOf("ROMs", "Roms", "roms", "ROMS")

        return roots.flatMap { root -> names.map { name -> "$root/$name" } }
    }
}
