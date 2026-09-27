package com.flycast.emulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

private const val TAG = "E2eStubEmulator"
private const val SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val HOST_OVERRIDE_FILE = "host_override"

class RetroAchievementsHostOverrideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = if (intent.action.orEmpty().endsWith(SET_ACTION_SUFFIX)) {
            intent.getStringExtra("host").orEmpty()
        } else {
            ""
        }
        File(context.filesDir, HOST_OVERRIDE_FILE).writeText(host)
        Log.i(TAG, "action=${intent.action} host=$host")
    }
}
