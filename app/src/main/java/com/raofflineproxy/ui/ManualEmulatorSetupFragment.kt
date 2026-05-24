package com.raofflineproxy.ui

import android.os.Bundle
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class ManualEmulatorSetupFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_manual_emulator_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val checkbox = view.findViewById<CheckBox>(R.id.cb_manual_emulator_patching)
        val content = view.findViewById<LinearLayout>(R.id.layout_manual_patching_content)
        val tabLayout = view.findViewById<TabLayout>(R.id.tab_manual_patching)
        val adbContent = view.findViewById<LinearLayout>(R.id.layout_manual_patching_adb)
        val prerequisitesBody = view.findViewById<TextView>(R.id.tv_manual_adb_prerequisites_body)
        val actionsBody = view.findViewById<TextView>(R.id.tv_manual_adb_actions_body)
        prerequisitesBody.movementMethod = LinkMovementMethod.getInstance()
        actionsBody.movementMethod = LinkMovementMethod.getInstance()

        if (tabLayout.tabCount == 0) {
            tabLayout.addTab(tabLayout.newTab().setText(R.string.manual_patching_tab_adb))
        }

        checkbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setManualEmulatorPatchingEnabled(isChecked)
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                adbContent.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (checkbox.isChecked != state.manualEmulatorPatchingEnabled) {
                    checkbox.isChecked = state.manualEmulatorPatchingEnabled
                }

                content.visibility = if (state.manualEmulatorPatchingEnabled) View.VISIBLE else View.GONE
                adbContent.visibility = if (state.manualEmulatorPatchingEnabled) View.VISIBLE else View.GONE

                prerequisitesBody.text = renderHtml(getString(R.string.manual_patching_adb_prerequisites_body))
                actionsBody.text = renderHtml(
                    getString(
                        R.string.manual_patching_adb_actions_body
                    )
                )
            }
        }
    }
}

private fun renderHtml(text: String): Spanned =
    HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
