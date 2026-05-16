package com.raofflineproxy.ui

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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val cbAutostart = view.findViewById<CheckBox>(R.id.cb_autostart_proxy)
        val cbSmartCaching = view.findViewById<CheckBox>(R.id.cb_smart_caching)
        val inputProxyPort = view.findViewById<TextInputLayout>(R.id.input_proxy_port)
        val etProxyPort = view.findViewById<TextInputEditText>(R.id.et_proxy_port)
        val tvProxyPortHint = view.findViewById<TextView>(R.id.tv_proxy_port_hint)
        val btnClearCache = view.findViewById<Button>(R.id.btn_clear_cache)
        val btnClearDatabase = view.findViewById<Button>(R.id.btn_clear_database)
        cbAutostart.text = getString(R.string.setting_autostart_label, getString(R.string.app_name))
        etProxyPort.filters = arrayOf(InputFilter.LengthFilter(5))

        cbAutostart.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutostartProxy(isChecked)
        }
        cbSmartCaching.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSmartCachingEnabled(isChecked)
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
                .show()
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
                if (cbAutostart.isChecked != state.autostartProxy) {
                    cbAutostart.isChecked = state.autostartProxy
                }
                if (cbSmartCaching.isChecked != state.smartCachingEnabled) {
                    cbSmartCaching.isChecked = state.smartCachingEnabled
                }
                val proxyPortText = state.proxyPort.toString()
                if (etProxyPort.text?.toString() != proxyPortText && !etProxyPort.hasFocus()) {
                    etProxyPort.setText(proxyPortText)
                }
                inputProxyPort.isEnabled = !state.proxyRunning
                etProxyPort.isEnabled = !state.proxyRunning
                tvProxyPortHint.isEnabled = !state.proxyRunning
                btnClearCache.isEnabled = !state.proxyRunning
                btnClearDatabase.isEnabled = !state.proxyRunning
                if (!state.proxyRunning) {
                    inputProxyPort.error = null
                }
            }
        }
    }
}
