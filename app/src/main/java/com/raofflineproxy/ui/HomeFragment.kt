package com.raofflineproxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.raofflineproxy.R
import com.raofflineproxy.PrefsConstants
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tokenWarning = view.findViewById<TextView>(R.id.tv_token_warning)
        val btnStartProxy = view.findViewById<MaterialButton>(R.id.btn_start_proxy)

        btnStartProxy.setOnClickListener {
            viewModel.startProxy(treeUri = PrefsConstants.loadSafUri(requireContext()))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                tokenWarning.visibility = if (state.proxyRunning && state.authState == AuthState.Invalid) View.VISIBLE else View.GONE
                btnStartProxy.visibility = if (state.proxyRunning) View.GONE else View.VISIBLE
            }
        }
    }
}
