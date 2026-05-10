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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tokenWarning = view.findViewById<TextView>(R.id.tv_token_warning)
        val btnStartProxy = view.findViewById<MaterialButton>(R.id.btn_start_proxy)
        val btnGoToCachedGames = view.findViewById<MaterialButton>(R.id.btn_go_to_cached_games)
        val homeDescription = view.findViewById<TextView>(R.id.tv_home_description)
        val emulatorLabel = view.findViewById<TextView>(R.id.tv_emulator_label)
        val emulatorToggles = view.findViewById<LinearLayout>(R.id.layout_emulator_toggles)
        val retroArchToggleRow = view.findViewById<LinearLayout>(R.id.layout_retroarch_toggle)
        val dolphinToggleRow = view.findViewById<LinearLayout>(R.id.layout_dolphin_toggle)
        val retroArchIcon = view.findViewById<ImageView>(R.id.iv_retroarch_icon)
        val dolphinIcon = view.findViewById<ImageView>(R.id.iv_dolphin_icon)
        val retroArchAppIcon = loadInstalledAppIcon(RETROARCH_PACKAGE_CANDIDATES)
        val dolphinAppIcon = loadInstalledAppIcon(DOLPHIN_PACKAGE_CANDIDATES)
        val activeBorderColor = requireContext().getColor(R.color.emulator_toggle_border_active)
        val activeBackgroundColor = requireContext().getColor(R.color.emulator_toggle_background_active)
        val defaultBorderColor = requireContext().getColor(R.color.emulator_toggle_border_default)

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

        retroArchToggleRow.setOnClickListener {
            if (retroArchToggleRow.isEnabled) {
                viewModel.setRetroArchEnabled(!viewModel.state.value.retroArchEnabled)
            }
        }
        dolphinToggleRow.setOnClickListener {
            if (dolphinToggleRow.isEnabled) {
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
                retroArchToggleRow.visibility = if (state.retroArchInstalled) View.VISIBLE else View.GONE
                dolphinToggleRow.visibility = if (state.dolphinInstalled) View.VISIBLE else View.GONE

                retroArchToggleRow.isEnabled = state.retroArchInstalled && !state.proxyRunning && !onlyOneInstalled
                dolphinToggleRow.isEnabled = state.dolphinInstalled && !state.proxyRunning && !onlyOneInstalled

                retroArchIcon.setImageDrawable(retroArchAppIcon)
                dolphinIcon.setImageDrawable(dolphinAppIcon)

                applyToggleRowStyle(
                    row = retroArchToggleRow,
                    icon = retroArchIcon,
                    isSelected = state.retroArchEnabled,
                    isDisabled = !retroArchToggleRow.isEnabled,
                    activeBorderColor = activeBorderColor,
                    activeBackgroundColor = activeBackgroundColor,
                    defaultBorderColor = defaultBorderColor
                )
                applyToggleRowStyle(
                    row = dolphinToggleRow,
                    icon = dolphinIcon,
                    isSelected = state.dolphinEnabled,
                    isDisabled = !dolphinToggleRow.isEnabled,
                    activeBorderColor = activeBorderColor,
                    activeBackgroundColor = activeBackgroundColor,
                    defaultBorderColor = defaultBorderColor
                )
            }
        }
    }

    private fun loadInstalledAppIcon(packageCandidates: List<String>): Drawable? {
        val packageName = resolveInstalledPackage(requireContext(), packageCandidates) ?: return null
        val packageManager = requireContext().packageManager

        return runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    private fun applyToggleRowStyle(
        row: LinearLayout,
        icon: ImageView,
        isSelected: Boolean,
        isDisabled: Boolean,
        activeBorderColor: Int,
        activeBackgroundColor: Int,
        defaultBorderColor: Int
    ) {
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

        if (isDisabled) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            icon.colorFilter = ColorMatrixColorFilter(matrix)
            icon.imageAlpha = 160
        } else {
            icon.colorFilter = null
            icon.imageAlpha = 255
        }
    }
}
