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
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.data.ConsoleNames
import com.raofflineproxy.proxy.CACHE_BUDGET_LIMIT
import com.raofflineproxy.proxy.QueueEstimate
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private const val TAG = "RAProxy/CachedGamesFragment"
private const val KEY_COLLAPSED_CONSOLES = "collapsed_console_ids"

class CachedGamesFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var romPickerUsed = false

    private val collapsedConsoleIds = mutableSetOf<Int>()
    private var currentGames: List<CachedGame> = emptyList()
    private var gamesAdapter: CachedGamesAdapter? = null
    private var headerAdapter: CachedGamesHeaderAdapter? = null
    private var headerState = CachedGamesHeaderAdapter.HeaderState()
    private var searchOpen = false
    private var searchQuery = ""
    private var queueDialog: AlertDialog? = null

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_cached_games, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadCollapsedState()

        val adapter = CachedGamesAdapter(
            onHeaderClick = { consoleId ->
                if (!collapsedConsoleIds.remove(consoleId)) collapsedConsoleIds.add(consoleId)
                saveCollapsedState()
                submitGames()
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
            },
            loadAchievements = { game, onLoaded ->
                viewLifecycleOwner.lifecycleScope.launch {
                    onLoaded(viewModel.cachedGameAchievements(game))
                }
            }
        )
        gamesAdapter = adapter

        val headerAdapter = CachedGamesHeaderAdapter(
            onSmartCache = viewModel::startSmartCache,
            onScan = { romFolderPickerLauncher.launch(createRomFolderPickerIntent()) },
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
            },
            onSearchChanged = { query ->
                searchQuery = query
                publishHeaderState(headerState)
                submitGames()
            },
            onSearchToggled = { open ->
                searchOpen = open
                if (!open) searchQuery = ""
                publishHeaderState(headerState)
                submitGames()
            }
        )
        this.headerAdapter = headerAdapter

        view.findViewById<RecyclerView>(R.id.rv_cached_games).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = ConcatAdapter(headerAdapter, adapter)
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
            itemAnimator = null
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.cachedGames.collect { games ->
                currentGames = games
                submitGames()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                gamesAdapter?.showLocked = state.showLockedAchievements
                gamesAdapter?.setOfflineEarnedAchievements(
                    state.awardHistory.mapTo(HashSet()) { it.achievementId }
                )
                val actionsEnabled = state.isOnline
                    && !state.scanInProgress
                val showSmartCache = !viewModel.isSmartCacheDisabledForShizuku(state)
                val smartCacheEnabled = actionsEnabled && showSmartCache
                val scanEnabled = state.isOnline && !state.scanInProgress
                val statusText = when {
                    !state.isOnline -> getString(R.string.cached_games_offline_hint)
                    state.queuedRomCount == 0 -> getString(R.string.cached_games_counter, state.cachedGames.size)
                    !state.proxyRunning -> getString(
                        R.string.cached_games_counter_queued_paused,
                        state.cachedGames.size,
                        state.queuedRomCount
                    )
                    else -> queuedStatusText(state)
                }
                publishHeaderState(
                    CachedGamesHeaderAdapter.HeaderState(
                        smartCacheEnabled = smartCacheEnabled,
                        showSmartCache = showSmartCache,
                        scanEnabled = scanEnabled,
                        clearEnabled = state.cachedGames.isNotEmpty() && !state.scanInProgress,
                        showNoCachedGames = state.cachedGames.isEmpty(),
                        statusText = statusText
                    )
                )
                updateQueueDialog(state.pendingQueueConfirmation)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        queueDialog?.dismiss()
        queueDialog = null
        gamesAdapter = null
        headerAdapter = null
    }

    private fun publishHeaderState(base: CachedGamesHeaderAdapter.HeaderState) {
        headerState = base.copy(searchOpen = searchOpen, searchQuery = searchQuery)
        headerAdapter?.update(headerState)
    }

    private fun submitGames() {
        gamesAdapter?.submitList(buildGroupedList(currentGames, collapsedConsoleIds, searchQuery))
    }

    private fun updateQueueDialog(estimate: QueueEstimate?) {
        if (estimate == null) {
            queueDialog?.dismiss()
            queueDialog = null
            return
        }
        if (queueDialog != null) return
        queueDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.queue_confirm_title)
            .setMessage(
                getString(
                    R.string.queue_confirm_message,
                    estimate.cachedNow,
                    estimate.queuedAfter,
                    CACHE_BUDGET_LIMIT,
                    formatQueueEta(estimate.etaMinutes)
                )
            )
            .setPositiveButton(R.string.queue_confirm_continue) { _, _ -> viewModel.resolveQueueConfirmation(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.resolveQueueConfirmation(false) }
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun formatQueueEta(minutes: Int): String =
        if (minutes < 60) {
            getString(R.string.queue_eta_minutes, minutes)
        } else {
            getString(R.string.queue_eta_hours, minutes / 60, minutes % 60)
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

    private fun queuedStatusText(state: MainUiState): String {
        val counter = getString(R.string.cached_games_counter_queued, state.cachedGames.size, state.queuedRomCount)
        val nextBatchAt = state.nextQueueBatchAt
        val batch = when {
            state.queueCachingNow -> getString(R.string.cached_games_queue_caching_now)
            nextBatchAt == null -> return counter
            state.nextQueueBatchDue -> getString(R.string.cached_games_queue_next_batch_soon)
            else -> getString(
                R.string.cached_games_queue_next_batch,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nextBatchAt))
            )
        }
        return getString(R.string.cached_games_counter_with_queue_status, counter, batch)
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
    collapsedConsoleIds: Set<Int>,
    query: String = ""
): List<CachedGameListItem> {
    val searching = query.isNotBlank()
    val visibleGames = if (searching) games.filter { it.title.contains(query.trim(), ignoreCase = true) } else games
    val collapsed = if (searching) emptySet() else collapsedConsoleIds
    return groupByConsole(visibleGames, collapsed)
}

private fun groupByConsole(
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
