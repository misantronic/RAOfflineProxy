package com.raofflineproxy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.RequestFailureNotifier
import com.raofflineproxy.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("UseKtx")
private val ANDROID_DATA_URI: Uri =
    "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata".toUri()

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var proxyMenuItem: MenuItem? = null
    private var snackbar: Snackbar? = null
    private var requestErrorSnackbar: Snackbar? = null
    private var pendingSnackbarJob: Job? = null
    private var pendingStartTokenWarning = false
    private var lastHandledPatchMessageId = 0L

    private val safLauncher = registerForActivityResult(OpenAndroidDataTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PrefsConstants.saveSafUri(this, uri)
        viewModel.clearTransientMessages()
        viewModel.startProxy(treeUri = uri)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: notification is non-critical */ }

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

        navView.setNavigationItemSelectedListener { item ->
            navigateTo(item.itemId)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        if (savedInstanceState == null) {
            showFragment(HomeFragment(), R.id.nav_home)
            if (viewModel.state.value.autostartProxy) {
                viewModel.startProxy(treeUri = PrefsConstants.loadSafUri(this@MainActivity))
            }
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateProxyMenuItem(state.proxyRunning, state.isOnline, state.proxyToggleInProgress)
                updateNavBadge(navView, R.id.nav_cached_games, state.cachedGames.size)
                updateNavBadge(navView, R.id.nav_pending_awards, state.pendingAwards.size)
                maybeShowStartTokenWarning(state)

                if (state.needsSafGrant) {
                    showSafGrantDialog()
                }

                handlePatchSnackbar(state)
            }
        }

        lifecycleScope.launch {
            RequestFailureNotifier.events.collect { message ->
                requestErrorSnackbar?.dismiss()
                requestErrorSnackbar = Snackbar.make(binding.fragmentContainer, message, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.action_ok) {
                        requestErrorSnackbar?.dismiss()
                        requestErrorSnackbar = null
                    }
                    .also { it.show() }
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
        updateProxyMenuItem(state.proxyRunning, state.isOnline, state.proxyToggleInProgress)
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
        val fragment: Fragment = when (itemId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_cached_games -> CachedGamesFragment()
            R.id.nav_pending_awards -> PendingAwardsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> return
        }
        showFragment(fragment, itemId)
        binding.navView.setCheckedItem(itemId)
    }

    private fun showFragment(fragment: Fragment, itemId: Int) {
        viewModel.clearTransientMessages()
        val title = when (itemId) {
            R.id.nav_home -> getString(R.string.app_name)
            R.id.nav_cached_games -> getString(R.string.title_cached_games)
            R.id.nav_pending_awards -> getString(R.string.title_pending_awards)
            R.id.nav_settings -> getString(R.string.title_settings)
            else -> getString(R.string.app_name)
        }
        supportActionBar?.title = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun updateNavBadge(navView: NavigationView, itemId: Int, count: Int) {
        val tv = navView.menu.findItem(itemId)
            ?.actionView
            ?.findViewById<android.widget.TextView>(R.id.tv_nav_count)
            ?: return
        tv.text = if (count > 0) getString(R.string.nav_badge_count, count) else ""
    }

    private fun updateProxyMenuItem(proxyRunning: Boolean, isOnline: Boolean, proxyToggleInProgress: Boolean) {
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
        actionView.isEnabled = !proxyToggleInProgress
        actionView.alpha = if (proxyToggleInProgress) 0.45f else 1f
    }

    private fun toggleProxy() {
        if (viewModel.state.value.proxyToggleInProgress) return

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

    private fun showSafGrantDialog() {
        viewModel.clearTransientMessages()
        AlertDialog.Builder(this)
            .setTitle(R.string.saf_dialog_title)
            .setMessage(R.string.saf_dialog_message)
            .setPositiveButton(R.string.saf_dialog_grant) { _, _ ->
                safLauncher.launch(Unit)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handlePatchSnackbar(state: MainUiState) {
        val msg = state.cfgPatchMessage
        val messageId = state.cfgPatchMessageId

        when {
            msg == null -> {
                pendingSnackbarJob?.cancel()
                pendingSnackbarJob = null
                snackbar?.dismiss()
                snackbar = null
            }
            messageId == lastHandledPatchMessageId -> Unit
            state.cfgPatchSuccess == false -> {
                lastHandledPatchMessageId = messageId
                pendingSnackbarJob?.cancel()
                pendingSnackbarJob = null
                snackbar?.dismiss()
                snackbar = Snackbar.make(binding.fragmentContainer, msg, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.action_ok) { viewModel.clearTransientMessages() }
                    .also { it.show() }
            }
            else -> {
                lastHandledPatchMessageId = messageId
                pendingSnackbarJob?.cancel()
                pendingSnackbarJob = lifecycleScope.launch {
                    delay(500)
                    snackbar?.dismiss()
                    snackbar = Snackbar.make(binding.fragmentContainer, msg, Snackbar.LENGTH_LONG)
                        .also { it.show() }
                }
            }
        }
    }

}

private class OpenAndroidDataTree : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra("android.provider.extra.INITIAL_URI", ANDROID_DATA_URI)
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
