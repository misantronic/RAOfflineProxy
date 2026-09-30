package com.raofflineproxy

import android.app.Application
import com.raofflineproxy.usage.UsageStats

class RAOfflineProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UsageStats.attach(this)
    }
}
