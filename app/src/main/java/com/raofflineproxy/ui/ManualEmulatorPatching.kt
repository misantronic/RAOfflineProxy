package com.raofflineproxy.ui

data class ManualPatchExecutionResult(
    val success: Boolean,
    val message: String,
    val needsPpssppSafGrant: Boolean = false
)
