package com.raofflineproxy.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.raofflineproxy.R
import kotlinx.coroutines.launch

private const val PREFS_NAME = "ra_proxy_prefs"
private const val PREF_SAF_URI = "saf_tree_uri"

private val ANDROID_DATA_URI = Uri.parse(
    "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata"
)

class RetroArchSetupFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private val folderPickerLauncher = registerForActivityResult(OpenAndroidDataTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        saveSafUri(uri)
        viewModel.patchCfg(treeUri = uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_retro_arch_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvDesc = view.findViewById<TextView>(R.id.tv_cfg_description)
        val btnPatch = view.findViewById<Button>(R.id.btn_patch_cfg)
        val btnRevert = view.findViewById<Button>(R.id.btn_revert_cfg)
        val btnGrant = view.findViewById<Button>(R.id.btn_grant_folder)

        btnPatch.setOnClickListener { viewModel.patchCfg(treeUri = loadSafUri()) }
        btnRevert.setOnClickListener { viewModel.revertCfg(treeUri = loadSafUri()) }
        btnGrant.setOnClickListener { folderPickerLauncher.launch(Unit) }

        var snackbar: Snackbar? = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                tvDesc.text = if (state.cfgIsPatched == true)
                    getString(R.string.setup_cfg_patched)
                else
                    getString(R.string.setup_cfg_description)

                when (state.cfgIsPatched) {
                    true -> {
                        btnPatch.visibility = View.GONE
                        btnRevert.visibility = View.VISIBLE
                        btnRevert.isEnabled = !state.proxyRunning
                    }
                    else -> {
                        btnPatch.visibility = View.VISIBLE
                        btnRevert.visibility = View.GONE
                    }
                }

                btnGrant.visibility = if (state.needsSafGrant) View.VISIBLE else View.GONE

                val copyBackMsg = state.cfgCopyBackPath?.let {
                    getString(R.string.setup_copy_back_instructions, it)
                }
                val msg = copyBackMsg ?: state.cfgPatchMessage

                when {
                    msg == null -> snackbar?.dismiss().also { snackbar = null }
                    copyBackMsg != null || state.cfgPatchSuccess == false -> {
                        snackbar?.dismiss()
                        snackbar = Snackbar.make(view, msg, Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.action_ok) { viewModel.clearTransientMessages() }
                            .also { it.show() }
                    }
                    else -> {
                        snackbar?.dismiss()
                        snackbar = Snackbar.make(view, msg, Snackbar.LENGTH_LONG)
                            .also { it.show() }
                    }
                }
            }
        }
    }

    private fun saveSafUri(uri: Uri) {
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(PREF_SAF_URI, uri.toString()).apply()
    }

    private fun loadSafUri(): Uri? =
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(PREF_SAF_URI, null)?.let { Uri.parse(it) }
}

private class OpenAndroidDataTree : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Unit): Intent =
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
