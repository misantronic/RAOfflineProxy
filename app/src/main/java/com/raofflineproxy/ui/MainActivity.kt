package com.raofflineproxy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import androidx.core.net.toUri

@SuppressLint("UseKtx")
private val ANDROID_DATA_URI: Uri =
    "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata".toUri()

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var proxyMenuItem: MenuItem? = null
    private var snackbar: Snackbar? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val drawerLayout = binding.drawerLayout
        val navView = binding.navView

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, R.string.nav_open, R.string.nav_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        navView.setNavigationItemSelectedListener { item ->
            navigateTo(item.itemId)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        if (savedInstanceState == null) {
            showFragment(HomeFragment(), R.id.nav_home)
            if (viewModel.state.value.autostartProxy) {
                lifecycleScope.launch {
                    viewModel.startProxy(treeUri = PrefsConstants.loadSafUri(this@MainActivity))
                }
            }
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateProxyMenuItem(state.proxyRunning, state.isOnline)
                updateNavBadge(navView, R.id.nav_cached_games, state.cachedGames.size)
                updateNavBadge(navView, R.id.nav_pending_awards, state.pendingAwards.size)

                if (state.needsSafGrant) {
                    showSafGrantDialog()
                }

                handlePatchSnackbar(state)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        proxyMenuItem = menu.findItem(R.id.action_toggle_proxy)
        proxyMenuItem?.actionView?.findViewById<android.view.View>(R.id.action_proxy_root)
            ?.setOnClickListener { toggleProxy() }
        val state = viewModel.state.value
        updateProxyMenuItem(state.proxyRunning, state.isOnline)
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

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
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

    private fun updateProxyMenuItem(proxyRunning: Boolean, isOnline: Boolean) {
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
        actionView.isEnabled = true
        actionView.alpha = 1f
    }

    private fun toggleProxy() {
        if (viewModel.state.value.proxyRunning) {
            viewModel.stopProxy(treeUri = PrefsConstants.loadSafUri(this))
        } else {
            viewModel.startProxy(treeUri = PrefsConstants.loadSafUri(this))
        }
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
        val copyBackMsg = state.cfgCopyBackPath?.let {
            getString(R.string.setup_copy_back_instructions, it)
        }
        val msg = copyBackMsg ?: state.cfgPatchMessage

        when {
            msg == null -> {
                snackbar?.dismiss()
                snackbar = null
            }
            copyBackMsg != null || state.cfgPatchSuccess == false -> {
                snackbar?.dismiss()
                snackbar = Snackbar.make(binding.root, msg, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.action_ok) { viewModel.clearTransientMessages() }
                    .also { it.show() }
            }
            else -> {
                snackbar?.dismiss()
                snackbar = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                    .also { it.show() }
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
