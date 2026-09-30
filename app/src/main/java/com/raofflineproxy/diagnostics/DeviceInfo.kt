package com.raofflineproxy.diagnostics

import android.content.Context
import android.os.Build
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.ui.loadEmulatorSupport

internal data class DeviceInfo(
    val device: String,
    val osVersion: String,
    val appVersion: String,
    val enabledEmulators: List<String>
)

internal fun deviceInfo(context: Context): DeviceInfo = DeviceInfo(
    device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    osVersion = androidVersionLabel(),
    appVersion = BuildConfig.VERSION_NAME,
    enabledEmulators = loadEmulatorSupport(context).enabled.map { it.displayName }
)

private fun androidVersionLabel(): String =
    Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: Build.VERSION.SDK_INT.toString()
