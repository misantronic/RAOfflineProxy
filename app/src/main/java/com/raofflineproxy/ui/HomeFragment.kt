package com.raofflineproxy.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private data class EmulatorToggleViews(
        val row: LinearLayout,
        val icon: ImageView,
        val checkIcon: ImageView,
        val label: TextView
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tokenWarning = view.findViewById<TextView>(R.id.tv_token_warning)
        val btnStartProxy = view.findViewById<MaterialButton>(R.id.btn_start_proxy)
        val btnGoToCachedGames = view.findViewById<MaterialButton>(R.id.btn_go_to_cached_games)
        val homeDescription = view.findViewById<TextView>(R.id.tv_home_description)
        val emulatorLabel = view.findViewById<TextView>(R.id.tv_emulator_label)
        val emulatorToggles = view.findViewById<LinearLayout>(R.id.layout_emulator_toggles)
        val retroArchToggle = bindToggle(view.findViewById(R.id.layout_retroarch_toggle), R.string.emulator_retroarch)
        val dolphinToggle = bindToggle(view.findViewById(R.id.layout_dolphin_toggle), R.string.emulator_dolphin)
        val retroArchAppIcon = loadInstalledAppIcon(RETROARCH_PACKAGE_CANDIDATES)
        val dolphinAppIcon = loadInstalledAppIcon(DOLPHIN_PACKAGE_CANDIDATES)
        val activeBorderColor = requireContext().getColor(R.color.emulator_toggle_border_active)
        val activeBackgroundColor = requireContext().getColor(R.color.emulator_toggle_background_active)
        val defaultBorderColor = requireContext().getColor(R.color.emulator_toggle_border_default)
        val activeTextColor = requireContext().getColor(R.color.emulator_toggle_text_active)
        val defaultTextColor = retroArchToggle.label.currentTextColor

        val fullText = getString(R.string.home_description)
        val linkWord = "documentation"
        val start = fullText.indexOf(linkWord)
        if (start >= 0) {
            val end = start + linkWord.length
            val linkColor = requireContext().getColor(R.color.primary)
            val spannable = SpannableString(fullText)
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.home_docs_url).toUri()))
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            homeDescription.text = spannable
            homeDescription.movementMethod = LinkMovementMethod.getInstance()
        }

        btnStartProxy.setOnClickListener {
            viewModel.startProxy(treeUri = PrefsConstants.loadSafUri(requireContext()))
        }

        btnGoToCachedGames.setOnClickListener {
            (activity as? MainActivity)?.navigateTo(R.id.nav_cached_games)
        }

        retroArchToggle.row.setOnClickListener {
            if (retroArchToggle.row.isEnabled) {
                viewModel.setRetroArchEnabled(!viewModel.state.value.retroArchEnabled)
            }
        }
        dolphinToggle.row.setOnClickListener {
            if (dolphinToggle.row.isEnabled) {
                viewModel.setDolphinEnabled(!viewModel.state.value.dolphinEnabled)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                val installedCount = listOf(state.retroArchInstalled, state.dolphinInstalled).count { it }
                val onlyOneInstalled = installedCount == 1
                val proxyStartPending = state.proxyToggleInProgress || state.needsSafGrant
                tokenWarning.visibility = if (state.proxyRunning && state.authState == AuthState.Invalid) View.VISIBLE else View.GONE
                btnStartProxy.visibility = if (state.proxyRunning) View.GONE else View.VISIBLE
                btnStartProxy.isEnabled = !proxyStartPending && (state.retroArchEnabled || state.dolphinEnabled)
                btnStartProxy.alpha = if (proxyStartPending) 0.45f else 1f
                btnGoToCachedGames.visibility = if (state.proxyRunning) View.VISIBLE else View.GONE

                emulatorLabel.visibility = if (installedCount > 0) View.VISIBLE else View.GONE
                emulatorToggles.visibility = if (installedCount > 0) View.VISIBLE else View.GONE
                retroArchToggle.row.visibility = if (state.retroArchInstalled) View.VISIBLE else View.GONE
                dolphinToggle.row.visibility = if (state.dolphinInstalled) View.VISIBLE else View.GONE

                retroArchToggle.row.isEnabled = state.retroArchInstalled && !state.proxyRunning && !onlyOneInstalled
                dolphinToggle.row.isEnabled = state.dolphinInstalled && !state.proxyRunning && !onlyOneInstalled

                retroArchToggle.icon.setImageDrawable(retroArchAppIcon)
                dolphinToggle.icon.setImageDrawable(dolphinAppIcon)

                applyToggleRowStyle(
                    toggle = retroArchToggle,
                    isSelected = state.retroArchEnabled,
                    isDisabled = !retroArchToggle.row.isEnabled,
                    activeBorderColor = activeBorderColor,
                    activeBackgroundColor = activeBackgroundColor,
                    defaultBorderColor = defaultBorderColor,
                    activeTextColor = activeTextColor,
                    defaultTextColor = defaultTextColor
                )
                applyToggleRowStyle(
                    toggle = dolphinToggle,
                    isSelected = state.dolphinEnabled,
                    isDisabled = !dolphinToggle.row.isEnabled,
                    activeBorderColor = activeBorderColor,
                    activeBackgroundColor = activeBackgroundColor,
                    defaultBorderColor = defaultBorderColor,
                    activeTextColor = activeTextColor,
                    defaultTextColor = defaultTextColor
                )
            }
        }
    }

    private fun loadInstalledAppIcon(packageCandidates: List<String>): Drawable? {
        val packageName = resolveInstalledPackage(requireContext(), packageCandidates) ?: return null
        val packageManager = requireContext().packageManager

        return runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    private fun bindToggle(root: LinearLayout, labelRes: Int): EmulatorToggleViews {
        val label = root.findViewById<TextView>(R.id.tv_label)
        label.setText(labelRes)
        return EmulatorToggleViews(
            row = root,
            icon = root.findViewById(R.id.iv_icon),
            checkIcon = root.findViewById(R.id.iv_check),
            label = label
        )
    }

    private fun applyToggleRowStyle(
        toggle: EmulatorToggleViews,
        isSelected: Boolean,
        isDisabled: Boolean,
        activeBorderColor: Int,
        activeBackgroundColor: Int,
        defaultBorderColor: Int,
        activeTextColor: Int,
        defaultTextColor: Int
    ) {
        val row = toggle.row
        val borderColor = if (isSelected) activeBorderColor else defaultBorderColor
        val background = row.background?.mutate()
        if (background is GradientDrawable) {
            val strokeWidthPx = (2 * resources.displayMetrics.density).toInt()
            background.setStroke(strokeWidthPx, borderColor)
            background.setColor(if (isSelected) activeBackgroundColor else android.graphics.Color.TRANSPARENT)
        } else {
            row.backgroundTintList = ColorStateList.valueOf(borderColor)
        }
        row.alpha = if (isDisabled) 0.55f else 1f
        toggle.label.setTextColor(if (isSelected) activeTextColor else defaultTextColor)
        toggle.checkIcon.visibility = if (isSelected) View.VISIBLE else View.GONE

        if (isDisabled) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            toggle.icon.colorFilter = ColorMatrixColorFilter(matrix)
            toggle.icon.imageAlpha = 160
        } else {
            toggle.icon.colorFilter = null
            toggle.icon.imageAlpha = 255
        }
    }
}
