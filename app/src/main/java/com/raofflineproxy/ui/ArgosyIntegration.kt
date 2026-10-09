package com.raofflineproxy.ui

import android.content.Context

private val ARGOSY_PACKAGES = listOf("com.nendo.argosy", "com.nendo.argosy.debug")

internal fun isArgosyInstalled(context: Context): Boolean =
    resolveInstalledPackage(context, ARGOSY_PACKAGES) != null
