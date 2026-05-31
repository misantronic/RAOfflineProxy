package com.raofflineproxy.ui

import android.os.Bundle
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class ManualEmulatorSetupFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_manual_emulator_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val checkbox = view.findViewById<CheckBox>(R.id.cb_manual_emulator_patching)
        val content = view.findViewById<LinearLayout>(R.id.layout_manual_patching_content)
        val shizukuStatus = view.findViewById<TextView>(R.id.tv_manual_shizuku_status)
        val shizukuBody = view.findViewById<TextView>(R.id.tv_manual_shizuku_body)
        val shizukuPermissionButton = view.findViewById<Button>(R.id.btn_manual_shizuku_permission)
        val ppssppResetLocationLabel = view.findViewById<TextView>(R.id.tv_manual_ppsspp_reset_label)
        val ppssppResetLocationButton = view.findViewById<Button>(R.id.btn_manual_ppsspp_reset_location)
        val shizukuToggleButton = view.findViewById<Button>(R.id.btn_manual_shizuku_toggle)
        shizukuBody.movementMethod = LinkMovementMethod.getInstance()

        checkbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setManualEmulatorPatchingEnabled(isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (checkbox.isChecked != state.manualEmulatorPatchingEnabled) {
                    checkbox.isChecked = state.manualEmulatorPatchingEnabled
                }

                content.visibility = if (state.manualEmulatorPatchingEnabled) View.VISIBLE else View.GONE
                val shizukuViewsVisibility = if (state.manualEmulatorPatchingEnabled) View.VISIBLE else View.GONE
                shizukuStatus.visibility = shizukuViewsVisibility
                shizukuBody.visibility = shizukuViewsVisibility
                shizukuPermissionButton.visibility = shizukuViewsVisibility
                shizukuToggleButton.visibility = shizukuViewsVisibility
                shizukuStatus.text = getString(R.string.manual_patching_shizuku_status, shizukuStatusLabel(requireContext(), state.shizukuStatus))
                shizukuBody.text = renderHtml(getString(R.string.manual_patching_shizuku_body))
                shizukuPermissionButton.visibility = if (state.shizukuStatus == ShizukuStatus.Ready) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                shizukuPermissionButton.isEnabled = state.shizukuStatus != ShizukuStatus.Ready && state.shizukuStatus != ShizukuStatus.Unsupported && !state.shizukuOperationInProgress
                shizukuPermissionButton.text = when (state.shizukuStatus) {
                    ShizukuStatus.NotInstalled -> getString(R.string.manual_patching_shizuku_install)
                    ShizukuStatus.NotRunning -> getString(R.string.manual_patching_shizuku_start)
                    ShizukuStatus.PermissionDenied -> getString(R.string.manual_patching_shizuku_permission)
                    else -> getString(R.string.manual_patching_shizuku_permission)
                }
                shizukuToggleButton.visibility = if (state.shizukuStatus == ShizukuStatus.Ready || state.shizukuManualPatchingEnabled) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                ppssppResetLocationLabel.visibility = if (state.shizukuManualPatchingEnabled && state.ppssppEnabled) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                ppssppResetLocationButton.visibility = if (state.shizukuManualPatchingEnabled && state.ppssppEnabled) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                ppssppResetLocationButton.isEnabled = state.shizukuManualPatchingEnabled &&
                    state.ppssppEnabled &&
                    !state.proxyRunning &&
                    !state.shizukuOperationInProgress &&
                    !state.ppssppShizukuRootModeUnknown
                shizukuToggleButton.isEnabled = !state.proxyRunning &&
                    !state.shizukuOperationInProgress &&
                    (state.shizukuManualPatchingEnabled || state.shizukuStatus == ShizukuStatus.Ready)
                shizukuToggleButton.text = if (state.shizukuManualPatchingEnabled) {
                    getString(R.string.manual_patching_shizuku_disable)
                } else {
                    getString(R.string.manual_patching_shizuku_enable)
                }
            }
        }

        shizukuPermissionButton.setOnClickListener { viewModel.requestShizukuPermission() }
        ppssppResetLocationButton.setOnClickListener { viewModel.resetPpssppShizukuLocationChoice() }
        shizukuToggleButton.setOnClickListener { viewModel.toggleShizukuManualPatchingEnabled() }
        viewModel.refreshShizukuStatus()
    }
}

private fun renderHtml(text: String): Spanned =
    HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
