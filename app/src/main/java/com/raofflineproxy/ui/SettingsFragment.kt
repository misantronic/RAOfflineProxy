package com.raofflineproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.raofflineproxy.R
import com.raofflineproxy.diagnostics.LogUploader
import com.raofflineproxy.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var syncingState = false
    private var sendingLogs = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val cbAutostart = view.findViewById<CheckBox>(R.id.cb_autostart_proxy)
        val cbSmartCaching = view.findViewById<CheckBox>(R.id.cb_smart_caching)
        val cbAppUpdateCheck = view.findViewById<CheckBox>(R.id.cb_app_update_check)
        val cbHideSupportButton = view.findViewById<CheckBox>(R.id.cb_hide_support_button)
        val btnManageSubscription = view.findViewById<Button>(R.id.btn_manage_subscription)
        val inputProxyPort = view.findViewById<TextInputLayout>(R.id.input_proxy_port)
        val etProxyPort = view.findViewById<TextInputEditText>(R.id.et_proxy_port)
        val tvProxyPortHint = view.findViewById<TextView>(R.id.tv_proxy_port_hint)
        val btnClearCache = view.findViewById<Button>(R.id.btn_clear_cache)
        val btnClearPermissions = view.findViewById<Button>(R.id.btn_clear_permissions)
        val btnClearDatabase = view.findViewById<Button>(R.id.btn_clear_database)
        val btnSendLogs = view.findViewById<Button>(R.id.btn_send_logs)
        cbAutostart.text = getString(R.string.setting_autostart_label, getString(R.string.app_name))
        etProxyPort.filters = arrayOf(InputFilter.LengthFilter(5))

        cbAutostart.setOnCheckedChangeListener { _, isChecked ->
            if (syncingState) return@setOnCheckedChangeListener
            viewModel.setAutostartProxy(isChecked)
        }
        cbSmartCaching.setOnCheckedChangeListener { _, isChecked ->
            if (syncingState) return@setOnCheckedChangeListener
            viewModel.setSmartCachingEnabled(isChecked)
        }
        cbAppUpdateCheck.setOnCheckedChangeListener { _, isChecked ->
            if (syncingState) return@setOnCheckedChangeListener
            viewModel.setAppUpdateCheckEnabled(isChecked)
        }
        cbHideSupportButton.setOnCheckedChangeListener { _, isChecked ->
            if (syncingState) return@setOnCheckedChangeListener
            viewModel.setHideSupportButtonEnabled(isChecked)
        }

        btnManageSubscription.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val raCredentials = viewModel.currentRaCredentials() ?: return@launch
                btnManageSubscription.isEnabled = false
                val result = withContext(Dispatchers.IO) {
                    DonationManager.getManageSubscriptionUrl(raCredentials)
                }
                btnManageSubscription.isEnabled = true
                result.onSuccess { url ->
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                }.onFailure {
                    SnackbarManager.showMessage(getString(R.string.manage_subscription_error), SnackbarDuration.Short)
                }
            }
        }

        fun submitProxyPort(): Boolean {
            val portText = etProxyPort.text?.toString()?.trim().orEmpty()
            if (viewModel.setProxyPort(portText)) {
                inputProxyPort.error = null
                etProxyPort.clearFocus()
                return true
            }

            inputProxyPort.error = getString(R.string.setting_proxy_port_invalid)
            return false
        }

        etProxyPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && etProxyPort.isEnabled) {
                submitProxyPort()
            }
        }
        etProxyPort.setOnEditorActionListener { _, _, _ -> submitProxyPort() }
        etProxyPort.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                submitProxyPort()
            } else {
                false
            }
        }

        btnClearCache.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_cache_confirm_title)
                .setMessage(R.string.clear_cache_confirm_message)
                .setPositiveButton(R.string.clear_action) { _, _ ->
                    viewModel.clearCache()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { it.setCanceledOnTouchOutside(false) }
                .show()
        }

        btnClearDatabase.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_database_confirm_title)
                .setMessage(R.string.clear_database_confirm_message)
                .setPositiveButton(R.string.clear_action) { _, _ ->
                    viewModel.clearDatabase()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { it.setCanceledOnTouchOutside(false) }
                .show()
        }

        btnClearPermissions.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_permissions_confirm_title)
                .setMessage(R.string.clear_permissions_confirm_message)
                .setPositiveButton(R.string.clear_action) { _, _ ->
                    viewModel.clearPermissions()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { it.setCanceledOnTouchOutside(false) }
                .show()
        }

        btnSendLogs.setOnClickListener {
            showSendLogsDetailsDialog(btnSendLogs)
        }

        view.findViewById<Button>(R.id.btn_contact_feedback).setOnClickListener {
            val url = getString(R.string.contact_feedback_url)
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }

        view.findViewById<Button>(R.id.btn_privacy_policy).setOnClickListener {
            val url = getString(R.string.privacy_policy_url)
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                syncingState = true
                if (cbAutostart.isChecked != state.autostartProxy) {
                    cbAutostart.isChecked = state.autostartProxy
                }
                if (cbSmartCaching.isChecked != state.smartCachingEnabled) {
                    cbSmartCaching.isChecked = state.smartCachingEnabled
                }
                if (cbAppUpdateCheck.isChecked != state.appUpdateCheckEnabled) {
                    cbAppUpdateCheck.isChecked = state.appUpdateCheckEnabled
                }
                if (cbHideSupportButton.isChecked != state.hideSupportButton) {
                    cbHideSupportButton.isChecked = state.hideSupportButton
                }
                val proxyPortText = state.proxyPort.toString()
                if (etProxyPort.text?.toString() != proxyPortText && !etProxyPort.hasFocus()) {
                    etProxyPort.setText(proxyPortText)
                }
                inputProxyPort.isEnabled = !state.proxyRunning
                etProxyPort.isEnabled = !state.proxyRunning
                tvProxyPortHint.isEnabled = !state.proxyRunning
                btnClearCache.isEnabled = !state.proxyRunning
                btnClearPermissions.isEnabled = !state.proxyRunning
                btnClearDatabase.isEnabled = !state.proxyRunning
                if (!sendingLogs) {
                    btnSendLogs.isEnabled = state.isOnline
                }
                if (!state.proxyRunning) {
                    inputProxyPort.error = null
                }
                syncingState = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-checked every time Settings becomes visible (not just on first creation), since
        // a subscription made via the emailed Payment Link happens outside the app — the user
        // might complete it in a browser and come straight back here.
        val btnManageSubscription = view?.findViewById<Button>(R.id.btn_manage_subscription) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val raCredentials = viewModel.currentRaCredentials()
            val hasActiveSubscription = raCredentials != null && withContext(Dispatchers.IO) {
                DonationManager.checkSubscriptionStatus(raCredentials).getOrDefault(false)
            }
            btnManageSubscription.visibility = if (hasActiveSubscription) View.VISIBLE else View.GONE
        }
    }

    private fun showSendLogsSuccessDialog(id: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.send_logs_success_title)
            .setMessage(getString(R.string.send_logs_success_message, id))
            .setPositiveButton(R.string.send_logs_copy_id) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("RAOfflineProxy log ID", id))
                SnackbarManager.showMessage(getString(R.string.send_logs_id_copied), SnackbarDuration.Short)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    // The "Add more details" checkbox reveals the email/description fields rather than showing
    // them upfront as optional — a visible-but-optional pair of fields reads as "you should
    // probably fill this in", which isn't true here. Once checked, though, a support request
    // needs both fields to pass server-side validation, so Send is blocked until both are
    // filled. The loading state lives on this dialog's own Send button, not on btnSendLogs
    // (which only opens the dialog) — btnSendLogs just stays disabled meanwhile so a second
    // dialog can't be opened mid-upload.
    private fun showSendLogsDetailsDialog(btnSendLogs: Button) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_send_logs_details, view as? ViewGroup, false)
        val cbAddDetails = dialogView.findViewById<CheckBox>(R.id.cb_send_logs_details)
        val detailsFields = dialogView.findViewById<View>(R.id.layout_send_logs_details_fields)
        val emailInput = dialogView.findViewById<TextInputLayout>(R.id.input_send_logs_email)
        val descriptionInput = dialogView.findViewById<TextInputLayout>(R.id.input_send_logs_description)
        val emailEdit = dialogView.findViewById<TextInputEditText>(R.id.et_send_logs_email)
        val descriptionEdit = dialogView.findViewById<TextInputEditText>(R.id.et_send_logs_description)

        cbAddDetails.setOnCheckedChangeListener { _, isChecked ->
            detailsFields.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                emailInput.error = null
                descriptionInput.error = null
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.send_logs_details_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_send_logs, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

            positiveButton.setOnClickListener {
                val addDetails = cbAddDetails.isChecked
                val email = if (addDetails) emailEdit.text?.toString()?.trim().orEmpty() else ""
                val description = if (addDetails) descriptionEdit.text?.toString()?.trim().orEmpty() else ""
                emailInput.error = null
                descriptionInput.error = null

                if (addDetails && (email.isEmpty() || description.isEmpty())) {
                    val requiredError = getString(R.string.send_logs_details_field_required)
                    if (email.isEmpty()) emailInput.error = requiredError
                    if (description.isEmpty()) descriptionInput.error = requiredError
                    return@setOnClickListener
                }

                sendingLogs = true
                btnSendLogs.isEnabled = false
                positiveButton.isEnabled = false
                negativeButton.isEnabled = false
                cbAddDetails.isEnabled = false
                emailEdit.isEnabled = false
                descriptionEdit.isEnabled = false

                val spinnerSizePx = (18 * resources.displayMetrics.density).toInt()
                val spinner = CircularProgressDrawable(requireContext()).apply {
                    setStyle(CircularProgressDrawable.DEFAULT)
                    setColorSchemeColors(positiveButton.currentTextColor)
                    setBounds(0, 0, spinnerSizePx, spinnerSizePx)
                    start()
                }
                positiveButton.text = null
                positiveButton.setCompoundDrawables(spinner, null, null, null)

                val appContext = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { LogUploader.uploadLogs(appContext) }
                    sendingLogs = false
                    btnSendLogs.isEnabled = viewModel.state.value.isOnline

                    result.onSuccess { id ->
                        if (email.isNotEmpty() && description.isNotEmpty()) {
                            val submitResult = withContext(Dispatchers.IO) {
                                LogUploader.submitSupportRequest(appContext, id, email, description)
                            }
                            submitResult.onFailure { error ->
                                SnackbarManager.showMessage(
                                    getString(
                                        R.string.send_logs_support_request_failed,
                                        error.message ?: error.toString()
                                    ),
                                    SnackbarDuration.Long
                                )
                            }
                        }
                        dialog.dismiss()
                        showSendLogsSuccessDialog(id)
                    }
                    result.onFailure { error ->
                        dialog.dismiss()
                        AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.send_logs_failed, error.message ?: error.toString()))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }
}
