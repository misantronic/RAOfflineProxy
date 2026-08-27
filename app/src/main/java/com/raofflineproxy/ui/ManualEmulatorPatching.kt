package com.raofflineproxy.ui

data class ManualPatchExecutionResult(
    val success: Boolean,
    val message: String,
    val needsPpssppSafGrant: Boolean = false,
    // Hardcore state each emulator's config had before patching. Persisted by the caller so the
    // matching revert can restore it, mirroring the non-Shizuku patch path.
    val hardcoreWasEnabled: Map<Emulator, Boolean> = emptyMap()
)
