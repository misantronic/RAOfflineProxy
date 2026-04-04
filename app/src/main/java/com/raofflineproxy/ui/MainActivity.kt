package com.raofflineproxy.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.raofflineproxy.R
import com.raofflineproxy.databinding.ActivityMainBinding
import com.raofflineproxy.service.ProxyService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var proxyMenuItem: MenuItem? = null

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
                    viewModel.state.first { it.cfgIsPatched != null }
                    if (viewModel.state.value.cfgIsPatched == true) {
                        ProxyService.start(this@MainActivity)
                        viewModel.onProxyStarted()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateProxyMenuItem(state.proxyRunning, state.isOnline, state.cfgIsPatched)
                updateNavBadge(navView, R.id.nav_cached_games, state.cachedGames.size)
                updateNavBadge(navView, R.id.nav_pending_awards, state.pendingAwards.size)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        proxyMenuItem = menu.findItem(R.id.action_toggle_proxy)
        proxyMenuItem?.actionView?.findViewById<android.view.View>(R.id.action_proxy_root)
            ?.setOnClickListener { toggleProxy() }
        val state = viewModel.state.value
        updateProxyMenuItem(state.proxyRunning, state.isOnline, state.cfgIsPatched)
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
            R.id.nav_retro_arch_setup -> RetroArchSetupFragment()
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
            R.id.nav_retro_arch_setup -> getString(R.string.title_retro_arch_setup)
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

    private fun updateProxyMenuItem(proxyRunning: Boolean, isOnline: Boolean, cfgIsPatched: Boolean?) {
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
        val enabled = proxyRunning || cfgIsPatched == true
        actionView.isEnabled = enabled
        actionView.alpha = if (enabled) 1f else 0.38f
    }

    private fun toggleProxy() {
        if (viewModel.state.value.proxyRunning) {
            ProxyService.stop(this)
            viewModel.onProxyStopped()
        } else {
            ProxyService.start(this)
            viewModel.onProxyStarted()
        }
    }
}
