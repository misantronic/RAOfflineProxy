package com.raofflineproxy.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.databinding.ActivityMainBinding
import com.raofflineproxy.service.ProxyService
import com.raofflineproxy.update.AppUpdateInfo
import java.util.ArrayDeque
import androidx.core.net.toUri
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var proxyMenuItem: MenuItem? = null
    private var snackbar: Snackbar? = null
    private var pendingSnackbarJob: Job? = null
    private var pendingStartTokenWarning = false
    private val pendingErrors = ArrayDeque<QueuedError>()
    private var pendingMessage: SnackbarEvent.Message? = null
    private var progressMessage: String? = null
    private var activeSnackbarKind: ActiveSnackbarKind? = null
    private var suppressNextDismissCallback = false
    private var activeSafGrantTarget: SafGrantTarget? = null
    private var attemptedGenericAllFilesAccess = false

    private val safLauncher = registerForActivityResult(OpenAndroidDataTree()) { uri ->
        if (uri == null) {
            viewModel.onSafRejected(SafGrantTarget.RetroArch)
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PrefsConstants.saveSafUri(this, uri)
        viewModel.onSafGranted(SafGrantTarget.RetroArch)
    }

    private val smartCacheRetroArchSafLauncher = registerForActivityResult(OpenRetroArchHistoryTree()) { uri ->
        if (uri == null) {
            viewModel.onSafRejected(SafGrantTarget.SmartCacheRetroArch)
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PrefsConstants.saveRetroArchSmartCacheSafUri(this, uri)
        viewModel.onSafGranted(SafGrantTarget.SmartCacheRetroArch)
    }

    private val dolphinSafLauncher = registerForActivityResult(OpenDolphinConfigTree()) { uri ->
        if (uri == null) {
            viewModel.onSafRejected(SafGrantTarget.Dolphin)
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PrefsConstants.saveDolphinSafUri(this, uri)
        viewModel.onSafGranted(SafGrantTarget.Dolphin)
    }

    private val smartCacheRomSafLauncher = registerForActivityResult(OpenSmartCacheRomTree()) { uri ->
        if (uri == null) {
            viewModel.onSafRejected(SafGrantTarget.SmartCacheRom)
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PrefsConstants.addSmartCacheRomSafUri(this, uri)
        viewModel.onSafGranted(SafGrantTarget.SmartCacheRom)
    }

    private val allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.hasAllFilesAccess()) {
            attemptedGenericAllFilesAccess = false
            viewModel.onSafGranted(SafGrantTarget.AllFilesAccess)
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !attemptedGenericAllFilesAccess &&
            canResolveIntent(createGenericAllFilesAccessIntent())
        ) {
            attemptedGenericAllFilesAccess = true
            startActivity(createGenericAllFilesAccessIntent())
        } else {
            attemptedGenericAllFilesAccess = false
            viewModel.onSafRejected(SafGrantTarget.AllFilesAccess)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: notification is non-critical */ }

    private val backStackListener = androidx.fragment.app.FragmentManager.OnBackStackChangedListener {
        syncNavigationUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val drawerLayout = binding.drawerLayout
        val navView = binding.navView
        val navVersion = binding.navView.findViewById<android.widget.TextView>(R.id.tv_nav_version)
        navVersion.text = getString(R.string.nav_version_format, BuildConfig.VERSION_NAME)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, R.string.nav_open, R.string.nav_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        supportFragmentManager.addOnBackStackChangedListener(backStackListener)

        navView.setNavigationItemSelectedListener { item ->
            navigateTo(item.itemId)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        if (savedInstanceState == null) {
            showFragment(HomeFragment(), R.id.nav_home, addToBackStack = false)
            if (viewModel.state.value.autostartProxy && !ProxyService.isRunning(this)) {
                viewModel.startProxy(treeUri = PrefsConstants.loadSafUri(this@MainActivity))
            }
            viewModel.checkForAppUpdate()
        } else {
            syncNavigationUi()
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateProxyMenuItem(
                    proxyRunning = state.proxyRunning,
                    isOnline = state.isOnline,
                    proxyToggleInProgress = state.proxyToggleInProgress,
                    needsSafGrant = state.needsSafGrant,
                    hasEnabledEmulator = state.retroArchEnabled || state.dolphinEnabled
                )
                updateNavBadge(navView, R.id.nav_cached_games, state.cachedGames.size)
                updateNavBadge(navView, R.id.nav_pending_awards, state.pendingAwards.size)
                updateNavBadge(navView, R.id.nav_awards_history, state.awardHistory.size)
                maybeShowStartTokenWarning(state)

                if (state.needsSafGrant) {
                    val target = state.safGrantTarget ?: SafGrantTarget.RetroArch
                    if (activeSafGrantTarget != target) {
                        showSafGrantDialog(target)
                    }
                } else {
                    activeSafGrantTarget = null
                }
            }
        }

        lifecycleScope.launch {
            SnackbarManager.events.collect { event ->
                when (event) {
                    is SnackbarEvent.Error -> enqueueError(event.message)
                    is SnackbarEvent.Progress -> showOrClearProgress(event.message)
                    is SnackbarEvent.Message -> showOrQueueMessage(event)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    MainUiEvent.PromptSmartCacheAfterProxyStart -> showSmartCacheAfterProxyStartDialog()
                    MainUiEvent.PromptManualCredentials -> showManualCredentialsDialog()
                    is MainUiEvent.ShowAppUpdate -> showAppUpdateDialog(event.update)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.state.value.proxyRunning) {
            viewModel.validateToken()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        proxyMenuItem = menu.findItem(R.id.action_toggle_proxy)
        proxyMenuItem?.actionView?.findViewById<android.view.View>(R.id.action_proxy_root)
            ?.setOnClickListener { toggleProxy() }
        val state = viewModel.state.value
        updateProxyMenuItem(
            proxyRunning = state.proxyRunning,
            isOnline = state.isOnline,
            proxyToggleInProgress = state.proxyToggleInProgress,
            needsSafGrant = state.needsSafGrant,
            hasEnabledEmulator = state.retroArchEnabled || state.dolphinEnabled
        )
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            return true
        }
        return when (item.itemId) {
            android.R.id.home -> {
                binding.drawerLayout.openDrawer(GravityCompat.START)
                true
            }
            R.id.action_toggle_proxy -> {
                toggleProxy()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun navigateTo(itemId: Int) {
        if (resolveCurrentItemId() == itemId) {
            binding.navView.setCheckedItem(itemId)
            return
        }

        val fragment: Fragment = when (itemId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_cached_games -> CachedGamesFragment()
            R.id.nav_pending_awards -> PendingAwardsFragment()
            R.id.nav_awards_history -> AwardsHistoryFragment()
            R.id.nav_settings -> SettingsFragment()
            R.id.nav_manual_emulator_setup -> ManualEmulatorSetupFragment()
            else -> return
        }
        showFragment(fragment, itemId, addToBackStack = true)
    }

    private fun showFragment(fragment: Fragment, itemId: Int, addToBackStack: Boolean) {
        viewModel.clearTransientMessages()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .apply {
                if (addToBackStack) {
                    addToBackStack(itemId.toString())
                }
            }
            .commit()
        updateNavigationUi(itemId)
    }

    private fun syncNavigationUi() {
        updateNavigationUi(resolveCurrentItemId() ?: R.id.nav_home)
    }

    private fun updateNavigationUi(itemId: Int) {
        val title = when (itemId) {
            R.id.nav_home -> getString(R.string.app_name)
            R.id.nav_cached_games -> getString(R.string.title_cached_games)
            R.id.nav_pending_awards -> getString(R.string.title_pending_awards)
            R.id.nav_awards_history -> getString(R.string.title_awards_history)
            R.id.nav_settings -> getString(R.string.title_settings)
            R.id.nav_manual_emulator_setup -> getString(R.string.title_manual_emulator_setup)
            else -> getString(R.string.app_name)
        }
        supportActionBar?.title = title
        binding.navView.setCheckedItem(itemId)
    }

    private fun resolveCurrentItemId(): Int? = when (supportFragmentManager.findFragmentById(R.id.fragment_container)) {
        is HomeFragment -> R.id.nav_home
        is CachedGamesFragment -> R.id.nav_cached_games
        is PendingAwardsFragment -> R.id.nav_pending_awards
        is AwardsHistoryFragment -> R.id.nav_awards_history
        is SettingsFragment -> R.id.nav_settings
        is ManualEmulatorSetupFragment -> R.id.nav_manual_emulator_setup
        else -> null
    }

    override fun onDestroy() {
        supportFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroy()
    }

    private fun updateNavBadge(navView: NavigationView, itemId: Int, count: Int) {
        val tv = navView.menu.findItem(itemId)
            ?.actionView
            ?.findViewById<android.widget.TextView>(R.id.tv_nav_count)
            ?: return
        tv.text = if (count > 0) getString(R.string.nav_badge_count, count) else ""
    }

    private fun updateProxyMenuItem(
        proxyRunning: Boolean,
        isOnline: Boolean,
        proxyToggleInProgress: Boolean,
        needsSafGrant: Boolean,
        hasEnabledEmulator: Boolean
    ) {
        val item = proxyMenuItem ?: return
        val actionView = item.actionView ?: return
        val label = actionView.findViewById<android.widget.TextView>(R.id.tv_proxy_label)
        val tooltipText = when {
            proxyRunning && isOnline -> getString(R.string.proxy_tooltip_online)
            proxyRunning -> getString(R.string.proxy_tooltip_offline)
            else -> getString(R.string.proxy_start)
        }
        label.text = if (proxyRunning) getString(R.string.proxy_stop) else getString(R.string.proxy_start)
        actionView.tooltipText = tooltipText
        val canToggle = !proxyToggleInProgress && !needsSafGrant && (proxyRunning || hasEnabledEmulator)
        actionView.isEnabled = canToggle
        actionView.alpha = if (canToggle) 1f else 0.45f
    }

    private fun toggleProxy() {
        if (viewModel.state.value.proxyToggleInProgress || viewModel.state.value.needsSafGrant) return

        if (viewModel.state.value.proxyRunning) {
            pendingStartTokenWarning = false
            viewModel.stopProxy(treeUri = PrefsConstants.loadSafUri(this))
        } else {
            pendingStartTokenWarning = true
            viewModel.startProxy(treeUri = PrefsConstants.loadSafUri(this))
        }
    }

    private fun maybeShowStartTokenWarning(state: MainUiState) {
        if (!pendingStartTokenWarning || !state.proxyRunning) return

        when (state.authState) {
            AuthState.Invalid -> {
                pendingStartTokenWarning = false
                showTokenWarningDialog()
            }
            AuthState.Valid -> pendingStartTokenWarning = false
            AuthState.Unknown -> Unit
        }
    }

    private fun showTokenWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.proxy_started_dialog_title)
            .setMessage(R.string.home_token_warning)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showSafGrantDialog(target: SafGrantTarget) {
        activeSafGrantTarget = target
        android.util.Log.i("RAProxy/SmartCache", "showSafGrantDialog target=$target")
        val messageRes = when (target) {
            SafGrantTarget.RetroArch -> R.string.saf_dialog_message
            SafGrantTarget.SmartCacheRetroArch -> R.string.smart_cache_retroarch_access_message
            SafGrantTarget.Dolphin -> R.string.dolphin_saf_dialog_message
            SafGrantTarget.AllFilesAccess -> R.string.smart_cache_all_files_access_message
            SafGrantTarget.SmartCacheRom -> R.string.smart_cache_rom_saf_dialog_message
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.saf_dialog_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.saf_dialog_grant) { _, _ ->
                activeSafGrantTarget = null
                when (target) {
                    SafGrantTarget.RetroArch -> safLauncher.launch(Unit)
                    SafGrantTarget.SmartCacheRetroArch -> smartCacheRetroArchSafLauncher.launch(Unit)
                    SafGrantTarget.Dolphin -> dolphinSafLauncher.launch(Unit)
                    SafGrantTarget.AllFilesAccess -> launchAllFilesAccessSettings()
                    SafGrantTarget.SmartCacheRom -> smartCacheRomSafLauncher.launch(viewModel.consumePendingSmartCacheRomGrantPath())
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                activeSafGrantTarget = null
                when (target) {
                    SafGrantTarget.RetroArch -> viewModel.onSafRejected(SafGrantTarget.RetroArch)
                    SafGrantTarget.SmartCacheRetroArch -> viewModel.onSafRejected(SafGrantTarget.SmartCacheRetroArch)
                    SafGrantTarget.Dolphin -> viewModel.onSafRejected(SafGrantTarget.Dolphin)
                    SafGrantTarget.AllFilesAccess -> viewModel.onSafRejected(SafGrantTarget.AllFilesAccess)
                    SafGrantTarget.SmartCacheRom -> viewModel.onSafRejected(SafGrantTarget.SmartCacheRom)
                }
            }
            .show()
    }

    private fun launchAllFilesAccessSettings() {
        attemptedGenericAllFilesAccess = false
        val appSpecificIntent = createAppSpecificAllFilesAccessIntent()
        if (canResolveIntent(appSpecificIntent)) {
            allFilesAccessLauncher.launch(appSpecificIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attemptedGenericAllFilesAccess = true
            allFilesAccessLauncher.launch(createGenericAllFilesAccessIntent())
        } else {
            viewModel.onSafRejected(SafGrantTarget.AllFilesAccess)
        }
    }

    private fun canResolveIntent(intent: Intent): Boolean =
        intent.resolveActivity(packageManager) != null

    private fun createAppSpecificAllFilesAccessIntent(): Intent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Intent()
        }
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:$packageName".toUri()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createGenericAllFilesAccessIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    private fun showSmartCacheAfterProxyStartDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.smart_cache_prompt_title)
            .setMessage(R.string.smart_cache_prompt_message)
            .setPositiveButton(R.string.smart_cache_prompt_start) { _, _ ->
                viewModel.startSmartCache()
            }
            .setNegativeButton(R.string.smart_cache_prompt_not_now, null)
            .show()
    }

    private fun showManualCredentialsDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_manual_credentials, binding.fragmentContainer, false)
        val usernameInput = dialogView.findViewById<TextInputLayout>(R.id.input_manual_credentials_username)
        val passwordInput = dialogView.findViewById<TextInputLayout>(R.id.input_manual_credentials_password)
        val usernameEdit = dialogView.findViewById<TextInputEditText>(R.id.et_manual_credentials_username)
        val passwordEdit = dialogView.findViewById<TextInputEditText>(R.id.et_manual_credentials_password)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.manual_credentials_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.manual_credentials_save, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.setManualEmulatorPatchingEnabled(false)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = usernameEdit.text?.toString()?.trim().orEmpty()
                val password = passwordEdit.text?.toString()?.trim().orEmpty()
                usernameInput.error = null
                passwordInput.error = null

                when {
                    username.isBlank() -> usernameInput.error = getString(R.string.manual_credentials_username_required)
                    password.isBlank() -> passwordInput.error = getString(R.string.manual_credentials_password_required)
                    else -> {
                        viewModel.saveManualLoginCredentials(username, password)
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showAppUpdateDialog(update: AppUpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_update_dialog_title)
            .setMessage(getString(R.string.app_update_dialog_message, update.versionName, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.app_update_action_download) { _, _ ->
                openUrl(update.apkUrl)
            }
            .setNeutralButton(R.string.app_update_action_release_notes) { _, _ ->
                openUrl(update.releaseUrl)
            }
            .setNegativeButton(R.string.app_update_action_later, null)
            .show()
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun enqueueError(message: String) {
        val existing = pendingErrors.lastOrNull()
        if (existing?.message == message) {
            existing.count += 1
            if (activeSnackbarKind == ActiveSnackbarKind.Error && pendingErrors.firstOrNull() === existing) {
                showCurrentError()
            }
            return
        }

        val activeError = pendingErrors.firstOrNull()
        if (activeSnackbarKind == ActiveSnackbarKind.Error && activeError?.message == message) {
            activeError.count += 1
            showCurrentError()
            return
        }

        pendingErrors.addLast(QueuedError(message))
        if (activeSnackbarKind != ActiveSnackbarKind.Error) {
            showNextSnackbar()
        }
    }

    private fun showOrQueueMessage(event: SnackbarEvent.Message) {
        pendingMessage = event
        progressMessage = null
        if (activeSnackbarKind == ActiveSnackbarKind.Error) return
        showNextSnackbar()
    }

    private fun showOrClearProgress(message: String?) {
        progressMessage = message
        if (activeSnackbarKind == ActiveSnackbarKind.Error) return
        if (message != null && activeSnackbarKind == ActiveSnackbarKind.Progress && snackbar != null) {
            snackbar?.setText(message)
            return
        }
        showNextSnackbar()
    }

    private fun showNextSnackbar() {
        pendingSnackbarJob?.cancel()
        pendingSnackbarJob = null
        if (snackbar != null) {
            suppressNextDismissCallback = true
            snackbar?.dismiss()
        }
        snackbar = null

        when {
            pendingErrors.isNotEmpty() -> showCurrentError()
            progressMessage != null -> showCurrentProgress(progressMessage!!)
            pendingMessage != null -> showCurrentMessage(pendingMessage!!)
            else -> activeSnackbarKind = null
        }
    }

    private fun showCurrentError() {
        val queued = pendingErrors.firstOrNull() ?: run {
            activeSnackbarKind = null
            return
        }

        activeSnackbarKind = ActiveSnackbarKind.Error
        snackbar = Snackbar.make(
            binding.fragmentContainer,
            queued.displayMessage(),
            Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.action_ok) {
            if (pendingErrors.isNotEmpty()) {
                pendingErrors.removeFirst()
            }
            snackbar = null
            activeSnackbarKind = null
            showNextSnackbar()
        }.also { it.show() }
    }

    private fun showCurrentMessage(event: SnackbarEvent.Message) {
        activeSnackbarKind = ActiveSnackbarKind.Message
        pendingMessage = null

        val duration = when (event.duration) {
            SnackbarDuration.Short -> Snackbar.LENGTH_SHORT
            SnackbarDuration.Long -> Snackbar.LENGTH_LONG
            SnackbarDuration.Indefinite -> Snackbar.LENGTH_INDEFINITE
        }

        if (duration == Snackbar.LENGTH_INDEFINITE) {
            snackbar = Snackbar.make(binding.fragmentContainer, event.message, duration)
                .setAction(R.string.action_ok) {
                    snackbar = null
                    activeSnackbarKind = null
                    showNextSnackbar()
                }
                .also { it.show() }
            return
        }

        pendingSnackbarJob = lifecycleScope.launch {
            delay(500)
            snackbar = Snackbar.make(binding.fragmentContainer, event.message, duration)
                .also {
                    it.addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            if (suppressNextDismissCallback) {
                                suppressNextDismissCallback = false
                                return
                            }
                            if (snackbar === transientBottomBar) {
                                snackbar = null
                                activeSnackbarKind = null
                                showNextSnackbar()
                            }
                        }
                    })
                    it.show()
                }
        }
    }

    private fun showCurrentProgress(message: String) {
        activeSnackbarKind = ActiveSnackbarKind.Progress
        snackbar = Snackbar.make(binding.fragmentContainer, message, Snackbar.LENGTH_INDEFINITE)
            .also {
                it.addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (suppressNextDismissCallback) {
                            suppressNextDismissCallback = false
                            return
                        }
                        if (snackbar === transientBottomBar) {
                            snackbar = null
                            activeSnackbarKind = null
                            showNextSnackbar()
                        }
                    }
                })
                it.show()
            }
    }

}

private enum class ActiveSnackbarKind { Error, Message, Progress }

private data class QueuedError(
    val message: String,
    var count: Int = 1
) {
    fun displayMessage(): String = if (count > 1) "$message (x$count)" else message
}

private class OpenAndroidDataTree : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            initialTreeUriForPath("/storage/emulated/0/Android/data/${resolveRetroArchPackage(context)}/files")
                ?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null

    private fun resolveRetroArchPackage(context: Context): String =
        RETROARCH_PACKAGE_CANDIDATES.firstOrNull { packageName ->
            runCatching { context.packageManager.getPackageInfo(packageName, 0) }
                .isSuccess
        } ?: RETROARCH_PACKAGE_CANDIDATES.first()
}

private class OpenDolphinConfigTree : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            initialTreeUriForPath("/storage/emulated/0/Android/data/${resolveDolphinPackage(context)}")
                ?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null

    private fun resolveDolphinPackage(context: Context): String =
        DOLPHIN_PACKAGE_CANDIDATES.firstOrNull { packageName ->
            runCatching { context.packageManager.getPackageInfo(packageName, 0) }
                .isSuccess
        } ?: DOLPHIN_PACKAGE_CANDIDATES.first()
}

private class OpenRetroArchHistoryTree : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            initialTreeUriForPath("/storage/emulated/0/RetroArch")
                ?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

private class OpenSmartCacheRomTree : ActivityResultContract<String?, Uri?>() {
    override fun createIntent(context: Context, input: String?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            initialTreeUriForPath(input ?: "/storage/emulated/0/ROMs")
                ?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
