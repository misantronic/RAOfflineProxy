package com.raofflineproxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val cbAutostart = view.findViewById<CheckBox>(R.id.cb_autostart_proxy)
        cbAutostart.text = getString(R.string.setting_autostart_label, getString(R.string.app_name))

        cbAutostart.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutostartProxy(isChecked)
        }

        view.findViewById<Button>(R.id.btn_clear_cache).setOnClickListener {
            viewModel.clearCache()
        }

        view.findViewById<Button>(R.id.btn_clear_database).setOnClickListener {
            viewModel.clearDatabase()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (cbAutostart.isChecked != state.autostartProxy) {
                    cbAutostart.isChecked = state.autostartProxy
                }
                state.clearCacheMessage?.let { msg ->
                    Snackbar.make(view, msg, Snackbar.LENGTH_LONG).show()
                    viewModel.clearTransientMessages()
                }
                state.clearDatabaseMessage?.let { msg ->
                    Snackbar.make(view, msg, Snackbar.LENGTH_LONG).show()
                    viewModel.clearTransientMessages()
                }
            }
        }
    }
}
