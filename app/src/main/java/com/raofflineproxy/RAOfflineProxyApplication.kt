package com.raofflineproxy

import android.app.Application
import com.raofflineproxy.diagnostics.CrashReport
import com.raofflineproxy.service.observeProxyStatusChanges
import com.raofflineproxy.usage.UsageStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RAOfflineProxyApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        UsageStats.attach(this)
        observeProxyStatusChanges(this, appScope)
    }
}
