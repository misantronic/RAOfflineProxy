package com.raofflineproxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val hint = view.findViewById<TextView>(R.id.tv_home_setup_hint)
        val btn = view.findViewById<Button>(R.id.btn_home_go_setup)
        val tokenWarning = view.findViewById<TextView>(R.id.tv_token_warning)

        btn.setOnClickListener {
            (activity as? MainActivity)?.navigateTo(R.id.nav_retro_arch_setup)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (state.cfgIsPatched == null) return@collect
                val notPatched = state.cfgIsPatched == false
                hint.visibility = if (notPatched) View.VISIBLE else View.GONE
                btn.visibility = if (notPatched) View.VISIBLE else View.GONE
                tokenWarning.visibility = if (!notPatched && state.authState == AuthState.Invalid) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.validateToken()
    }
}
