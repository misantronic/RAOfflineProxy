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
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.raofflineproxy.MAX_CACHED_GAMES
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.data.ConsoleNames
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RAProxy/CachedGamesFragment"
private const val KEY_COLLAPSED_CONSOLES = "collapsed_console_ids"

class CachedGamesFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var romPickerUsed = false

    private val collapsedConsoleIds = mutableSetOf<Int>()
    private var currentGames: List<CachedGame> = emptyList()
    private var gamesAdapter: CachedGamesAdapter? = null

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
        loadCollapsedState()

        val adapter = CachedGamesAdapter(
            onHeaderClick = { consoleId ->
                if (!collapsedConsoleIds.remove(consoleId)) collapsedConsoleIds.add(consoleId)
                saveCollapsedState()
                gamesAdapter?.submitList(buildGroupedList(currentGames, collapsedConsoleIds))
            },
            onDelete = viewModel::deleteCachedGame,
            onDeleteConsole = { header ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.delete_console_games_confirm_title, header.consoleName))
                    .setMessage(
                        getString(
                            R.string.delete_console_games_confirm_message,
                            header.gameCount,
                            header.consoleName
                        )
                    )
                    .setPositiveButton(R.string.clear_action) { _, _ ->
                        viewModel.deleteConsoleGames(header.consoleId)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .also { it.setCanceledOnTouchOutside(false) }
                    .show()
            }
        )
        gamesAdapter = adapter

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
            this.adapter = ConcatAdapter(headerAdapter, adapter)
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
            itemAnimator = null
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            viewModel.cachedGames.collect { games ->
                currentGames = games
                gamesAdapter?.submitList(buildGroupedList(games, collapsedConsoleIds))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                gamesAdapter?.showLocked = state.showLockedAchievements
                gamesAdapter?.setAwardOrigins(
                    state.pendingAwards.mapTo(HashSet()) { it.achievementId },
                    state.awardHistory.mapTo(HashSet()) { it.achievementId }
                )
                val actionsEnabled = state.isOnline
                    && !state.scanInProgress
                val showSmartCache = !viewModel.isSmartCacheDisabledForShizuku(state)
                val smartCacheEnabled = actionsEnabled
                    && showSmartCache
                    && state.cachedGames.size < MAX_CACHED_GAMES
                val scanEnabled = state.isOnline
                    && !state.scanInProgress
                    && state.cachedGames.size < MAX_CACHED_GAMES
                val statusText = when {
                    !state.isOnline -> getString(R.string.cached_games_offline_hint)
                    else -> getString(R.string.cached_games_counter, state.cachedGames.size, MAX_CACHED_GAMES)
                }
                headerAdapter.update(
                    CachedGamesHeaderAdapter.HeaderState(
                        smartCacheEnabled = smartCacheEnabled,
                        showSmartCache = showSmartCache,
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

    override fun onDestroyView() {
        super.onDestroyView()
        gamesAdapter = null
    }

    private fun loadCollapsedState() {
        collapsedConsoleIds.clear()
        requireContext()
            .getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_COLLAPSED_CONSOLES, emptySet())
            .orEmpty()
            .mapNotNullTo(collapsedConsoleIds) { it.toIntOrNull() }
    }

    private fun saveCollapsedState() {
        requireContext()
            .getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_COLLAPSED_CONSOLES, collapsedConsoleIds.mapTo(mutableSetOf()) { it.toString() }) }
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

private fun buildGroupedList(
    games: List<CachedGame>,
    collapsedConsoleIds: Set<Int>
): List<CachedGameListItem> {
    if (games.isEmpty()) return emptyList()
    val countByConsole = games.groupingBy { it.consoleId }.eachCount()
    val result = mutableListOf<CachedGameListItem>()
    var lastConsoleId: Int? = null
    for (game in games) {
        if (game.consoleId != lastConsoleId) {
            result += CachedGameListItem.ConsoleHeader(
                consoleId = game.consoleId,
                consoleName = ConsoleNames.nameForId(game.consoleId),
                gameCount = countByConsole[game.consoleId] ?: 0,
                isCollapsed = game.consoleId in collapsedConsoleIds
            )
            lastConsoleId = game.consoleId
        }
        if (game.consoleId !in collapsedConsoleIds) {
            result += CachedGameListItem.GameItem(game)
        }
    }
    return result
}
