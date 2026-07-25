package com.raofflineproxy.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.raofflineproxy.R
import com.raofflineproxy.donation.DonationManager
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private data class EmulatorToggleViews(
        val row: LinearLayout,
        val icon: ImageView,
        val checkBox: CheckBox,
        val label: TextView
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tokenWarning = view.findViewById<TextView>(R.id.tv_token_warning)
        val manualSetupWarning = view.findViewById<TextView>(R.id.tv_manual_setup_warning)
        val shizukuInfo = view.findViewById<TextView>(R.id.tv_shizuku_info)
        val btnStartProxy = view.findViewById<MaterialButton>(R.id.btn_start_proxy)
        val btnManualEmulatorSetup = view.findViewById<MaterialButton>(R.id.btn_manual_emulator_setup)
        val btnGoToCachedGames = view.findViewById<MaterialButton>(R.id.btn_go_to_cached_games)
        val btnSupportDevelopment = view.findViewById<Button>(R.id.btn_support_development)
        val homeDescription = view.findViewById<TextView>(R.id.tv_home_description)
        val emulatorLabel = view.findViewById<TextView>(R.id.tv_emulator_label)
        val emulatorSelector = view.findViewById<LinearLayout>(R.id.layout_emulator_selector)
        val enabledEmulatorIcons = view.findViewById<LinearLayout>(R.id.layout_enabled_emulator_icons)
        val emulatorSelectorDialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_emulator_selector, null, false)
        val retroArchToggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_retroarch_toggle), R.string.emulator_retroarch)
        val dolphinToggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_dolphin_toggle), R.string.emulator_dolphin)
        val ppssppToggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_ppsspp_toggle), R.string.emulator_ppsspp)
        val armsx2Toggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_armsx2_toggle), R.string.emulator_armsx2)
        val flycastToggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_flycast_toggle), R.string.emulator_flycast)
        val melonDualDsToggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_melondualds_toggle), R.string.emulator_melondualds)
        val mupen64Toggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_mupen64_toggle), R.string.emulator_mupen64)
        val emuCoreXToggle = bindToggle(emulatorSelectorDialogView.findViewById(R.id.layout_emucorex_toggle), R.string.emulator_emucorex)
        val retroArchAppIcon = loadInstalledAppIcon(RETROARCH_PACKAGE_CANDIDATES)
        val dolphinAppIcon = loadInstalledAppIcon(DOLPHIN_PACKAGE_CANDIDATES)
        val ppssppAppIcon = loadInstalledAppIcon(UI_PPSSPP_PACKAGE_CANDIDATES)
        val armsx2AppIcon = loadInstalledAppIcon(UI_ARMSX2_PACKAGE_CANDIDATES)
        val flycastAppIcon = loadInstalledAppIcon(UI_FLYCAST_PACKAGE_CANDIDATES)
        val melonDualDsAppIcon = loadInstalledAppIcon(UI_MELONDUALDS_PACKAGE_CANDIDATES)
        val mupen64AppIcon = loadInstalledAppIcon(UI_MUPEN64_PACKAGE_CANDIDATES)
        val emuCoreXAppIcon = loadInstalledAppIcon(UI_EMUCOREX_PACKAGE_CANDIDATES)

        if (resources.getBoolean(R.bool.show_home_description)) {
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
        } else {
            homeDescription.visibility = View.GONE
        }

        btnStartProxy.setOnClickListener {
            if (viewModel.state.value.proxyRunning) {
                (activity as? MainActivity)?.requestStopProxy()
            } else {
                (activity as? MainActivity)?.requestStartProxy()
            }
        }

        btnManualEmulatorSetup.setOnClickListener {
            (activity as? MainActivity)?.navigateTo(R.id.nav_manual_emulator_setup)
        }

        btnGoToCachedGames.setOnClickListener {
            (activity as? MainActivity)?.navigateTo(R.id.nav_cached_games)
        }

        btnSupportDevelopment.setOnClickListener {
            showSupportDialog()
        }

        emulatorSelector.setOnClickListener {
            if (emulatorSelector.isEnabled) {
                showEmulatorSelectorDialog(emulatorSelectorDialogView)
            }
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
        ppssppToggle.row.setOnClickListener {
            if (ppssppToggle.row.isEnabled) {
                viewModel.setPpssppEnabled(!viewModel.state.value.ppssppEnabled)
            }
        }
        armsx2Toggle.row.setOnClickListener {
            if (armsx2Toggle.row.isEnabled) {
                viewModel.setArmsx2Enabled(!viewModel.state.value.armsx2Enabled)
            }
        }
        flycastToggle.row.setOnClickListener {
            if (flycastToggle.row.isEnabled) {
                viewModel.setFlycastEnabled(!viewModel.state.value.flycastEnabled)
            }
        }
        melonDualDsToggle.row.setOnClickListener {
            if (melonDualDsToggle.row.isEnabled) {
                viewModel.setMelonDualDsEnabled(!viewModel.state.value.melonDualDsEnabled)
            }
        }
        mupen64Toggle.row.setOnClickListener {
            if (mupen64Toggle.row.isEnabled) {
                viewModel.setMupen64Enabled(!viewModel.state.value.mupen64Enabled)
            }
        }
        emuCoreXToggle.row.setOnClickListener {
            if (emuCoreXToggle.row.isEnabled) {
                viewModel.setEmuCoreXEnabled(!viewModel.state.value.emuCoreXEnabled)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                val installedCount = listOf(state.retroArchInstalled, state.dolphinInstalled, state.ppssppInstalled, state.armsx2Installed, state.flycastInstalled, state.melonDualDsInstalled, state.mupen64Installed, state.emuCoreXInstalled).count { it }
                val noEmulatorInstalled = installedCount == 0
                val onlyOneInstalled = installedCount == 1
                val hasManualSetupManagedEmulator = state.retroArchInstalled || state.dolphinInstalled || state.ppssppInstalled
                val proxyStartPending = state.proxyToggleInProgress || state.needsSafGrant
                val shouldRecommendManualSetup = hasManualSetupManagedEmulator &&
                    !state.manualEmulatorPatchingEnabled &&
                    shouldRecommendManualSetupForDevice()
                val manualSetupNeedsShizuku = state.manualEmulatorPatchingEnabled &&
                    hasManualSetupManagedEmulator &&
                    !state.shizukuManualPatchingEnabled
                val shouldShowManualSetupButton = !state.proxyRunning &&
                    (shouldRecommendManualSetup || manualSetupNeedsShizuku)
                tokenWarning.text = getString(R.string.home_no_emulator_warning)
                tokenWarning.visibility = if (noEmulatorInstalled) View.VISIBLE else View.GONE
                manualSetupWarning.text = getString(R.string.home_manual_setup_warning, androidVersionLabel())
                manualSetupWarning.visibility = if (shouldRecommendManualSetup) View.VISIBLE else View.GONE
                shizukuInfo.visibility = if (state.manualEmulatorPatchingEnabled && state.shizukuManualPatchingEnabled) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                btnStartProxy.visibility = if (shouldShowManualSetupButton) View.GONE else View.VISIBLE
                btnStartProxy.text = getString(if (state.proxyRunning) R.string.proxy_stop else R.string.proxy_start)
                btnStartProxy.isEnabled = if (state.proxyRunning) !proxyStartPending else !proxyStartPending && (state.retroArchEnabled || state.dolphinEnabled || state.ppssppEnabled || state.armsx2Enabled || state.flycastEnabled || state.melonDualDsEnabled || state.mupen64Enabled || state.emuCoreXEnabled)
                btnStartProxy.alpha = if (proxyStartPending) 0.45f else 1f
                btnManualEmulatorSetup.visibility = if (shouldShowManualSetupButton) View.VISIBLE else View.GONE
                btnGoToCachedGames.visibility = if (state.proxyRunning) View.VISIBLE else View.GONE

                emulatorLabel.visibility = if (installedCount > 0) View.VISIBLE else View.GONE
                emulatorSelector.visibility = if (installedCount > 0) View.VISIBLE else View.GONE
                emulatorSelector.isEnabled = !state.proxyRunning
                emulatorSelector.alpha = if (state.proxyRunning) 0.6f else 1f
                retroArchToggle.row.visibility = if (state.retroArchInstalled) View.VISIBLE else View.GONE
                dolphinToggle.row.visibility = if (state.dolphinInstalled) View.VISIBLE else View.GONE
                ppssppToggle.row.visibility = if (state.ppssppInstalled) View.VISIBLE else View.GONE
                armsx2Toggle.row.visibility = if (state.armsx2Installed) View.VISIBLE else View.GONE
                flycastToggle.row.visibility = if (state.flycastInstalled) View.VISIBLE else View.GONE
                melonDualDsToggle.row.visibility = if (state.melonDualDsInstalled) View.VISIBLE else View.GONE
                mupen64Toggle.row.visibility = if (state.mupen64Installed) View.VISIBLE else View.GONE
                emuCoreXToggle.row.visibility = if (state.emuCoreXInstalled) View.VISIBLE else View.GONE

                retroArchToggle.row.isEnabled = state.retroArchInstalled && !state.proxyRunning && !onlyOneInstalled
                dolphinToggle.row.isEnabled = state.dolphinInstalled && !state.proxyRunning && !onlyOneInstalled
                ppssppToggle.row.isEnabled = state.ppssppInstalled && !state.proxyRunning && !onlyOneInstalled
                armsx2Toggle.row.isEnabled = state.armsx2Installed && !state.proxyRunning && !onlyOneInstalled
                flycastToggle.row.isEnabled = state.flycastInstalled && !state.proxyRunning && !onlyOneInstalled
                melonDualDsToggle.row.isEnabled = state.melonDualDsInstalled && !state.proxyRunning && !onlyOneInstalled
                mupen64Toggle.row.isEnabled = state.mupen64Installed && !state.proxyRunning && !onlyOneInstalled
                emuCoreXToggle.row.isEnabled = state.emuCoreXInstalled && !state.proxyRunning && !onlyOneInstalled

                listOf(retroArchToggle, dolphinToggle, ppssppToggle, armsx2Toggle, flycastToggle, melonDualDsToggle, mupen64Toggle, emuCoreXToggle).forEach { toggle ->
                    toggle.row.alpha = if (toggle.row.isEnabled) 1f else 0.5f
                    toggle.checkBox.isEnabled = toggle.row.isEnabled
                }

                retroArchToggle.icon.setImageDrawable(retroArchAppIcon)
                dolphinToggle.icon.setImageDrawable(dolphinAppIcon)
                ppssppToggle.icon.setImageDrawable(ppssppAppIcon)
                armsx2Toggle.icon.setImageDrawable(armsx2AppIcon)
                flycastToggle.icon.setImageDrawable(flycastAppIcon)
                melonDualDsToggle.icon.setImageDrawable(melonDualDsAppIcon)
                mupen64Toggle.icon.setImageDrawable(mupen64AppIcon)
                emuCoreXToggle.icon.setImageDrawable(emuCoreXAppIcon)

                applyToggleRowStyle(toggle = retroArchToggle, isSelected = state.retroArchEnabled)
                applyToggleRowStyle(toggle = dolphinToggle, isSelected = state.dolphinEnabled)
                applyToggleRowStyle(toggle = ppssppToggle, isSelected = state.ppssppEnabled)
                applyToggleRowStyle(toggle = armsx2Toggle, isSelected = state.armsx2Enabled)
                applyToggleRowStyle(toggle = flycastToggle, isSelected = state.flycastEnabled)
                applyToggleRowStyle(toggle = melonDualDsToggle, isSelected = state.melonDualDsEnabled)
                applyToggleRowStyle(toggle = mupen64Toggle, isSelected = state.mupen64Enabled)
                applyToggleRowStyle(toggle = emuCoreXToggle, isSelected = state.emuCoreXEnabled)

                enabledEmulatorIcons.removeAllViews()
                val iconSizePx = (28 * resources.displayMetrics.density).toInt()
                val iconSpacingPx = (6 * resources.displayMetrics.density).toInt()
                listOf(
                    (state.retroArchInstalled && state.retroArchEnabled) to retroArchAppIcon,
                    (state.dolphinInstalled && state.dolphinEnabled) to dolphinAppIcon,
                    (state.ppssppInstalled && state.ppssppEnabled) to ppssppAppIcon,
                    (state.armsx2Installed && state.armsx2Enabled) to armsx2AppIcon,
                    (state.flycastInstalled && state.flycastEnabled) to flycastAppIcon,
                    (state.melonDualDsInstalled && state.melonDualDsEnabled) to melonDualDsAppIcon,
                    (state.mupen64Installed && state.mupen64Enabled) to mupen64AppIcon,
                    (state.emuCoreXInstalled && state.emuCoreXEnabled) to emuCoreXAppIcon
                ).forEach { (enabled, icon) ->
                    if (enabled && icon != null) {
                        // Never share the Drawable instance with the dialog row's icon ImageView:
                        // Drawable.setBounds() mutates the instance itself, so two ImageViews
                        // fighting over the same object's bounds causes icons to intermittently
                        // render blank/clipped depending on which view laid out last.
                        val clusterIcon = icon.constantState?.newDrawable(resources) ?: icon
                        enabledEmulatorIcons.addView(ImageView(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                                marginEnd = iconSpacingPx
                            }
                            setImageDrawable(clusterIcon)
                        })
                    }
                }
            }
        }
    }

    private fun showSupportDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_support_donation, null, false)
        val frequencyToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggle_donation_frequency)
        val deliveryToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggle_donation_delivery)
        val amountInput = dialogView.findViewById<TextInputLayout>(R.id.input_donation_amount)
        val amountEdit = dialogView.findViewById<TextInputEditText>(R.id.et_donation_amount)
        val emailInput = dialogView.findViewById<TextInputLayout>(R.id.input_donation_email)
        val emailEdit = dialogView.findViewById<TextInputEditText>(R.id.et_donation_email)
        val kofiButton = dialogView.findViewById<Button>(R.id.btn_donation_kofi)

        frequencyToggle.check(R.id.btn_donation_monthly)
        deliveryToggle.check(R.id.btn_donation_pay_now)
        deliveryToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                emailInput.visibility = if (checkedId == R.id.btn_donation_email_link) View.VISIBLE else View.GONE
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.support_dialog_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.donation_submit, null)
            .create()

        kofiButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.support_kofi_url).toUri()))
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                amountInput.error = null
                emailInput.error = null

                val amountText = amountEdit.text?.toString()?.trim().orEmpty()
                val amountDollars = amountText.toDoubleOrNull()
                when {
                    amountText.isBlank() -> {
                        amountInput.error = getString(R.string.donation_amount_required)
                        return@setOnClickListener
                    }
                    amountDollars == null || amountDollars < 1.0 || amountDollars > 1000.0 -> {
                        amountInput.error = getString(R.string.donation_amount_invalid)
                        return@setOnClickListener
                    }
                }
                val amountCents = (amountDollars!! * 100).roundToInt()
                val isMonthly = frequencyToggle.checkedButtonId == R.id.btn_donation_monthly
                val isEmailDelivery = deliveryToggle.checkedButtonId == R.id.btn_donation_email_link

                if (isEmailDelivery) {
                    val email = emailEdit.text?.toString()?.trim().orEmpty()
                    when {
                        email.isBlank() -> {
                            emailInput.error = getString(R.string.donation_email_required)
                            return@setOnClickListener
                        }
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                            emailInput.error = getString(R.string.donation_email_invalid)
                            return@setOnClickListener
                        }
                    }

                    setButtonLoading(positiveButton, true)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val frequency = if (isMonthly) "monthly" else "once"
                        val result = withContext(Dispatchers.IO) {
                            DonationManager.requestEmailInvoice(amountCents, frequency, email)
                        }
                        setButtonLoading(positiveButton, false)
                        result.onSuccess {
                            dialog.dismiss()
                            showOutcomeDialog(R.string.donation_email_sent_title, R.string.donation_email_sent_message)
                        }.onFailure {
                            showOutcomeDialog(R.string.support_dialog_title, R.string.donation_error_message)
                        }
                    }
                } else {
                    setButtonLoading(positiveButton, true)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            if (isMonthly) {
                                DonationManager.createSubscription(amountCents).map { checkout ->
                                    Triple(checkout.clientSecret, checkout.customerId, checkout.ephemeralKey)
                                }
                            } else {
                                DonationManager.createPaymentIntent(amountCents).map { clientSecret -> Triple(clientSecret, null, null) }
                            }
                        }
                        setButtonLoading(positiveButton, false)
                        result.onSuccess { (clientSecret, customerId, ephemeralKey) ->
                            dialog.dismiss()
                            (activity as? MainActivity)?.presentDonationCheckout(clientSecret, customerId, ephemeralKey)
                        }.onFailure {
                            showOutcomeDialog(R.string.support_dialog_title, R.string.donation_error_message)
                        }
                    }
                }
            }
        }

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun setButtonLoading(button: Button, loading: Boolean) {
        button.isEnabled = !loading
        if (loading) {
            val spinner = CircularProgressDrawable(requireContext()).apply {
                setStyle(CircularProgressDrawable.DEFAULT)
                setColorSchemeColors(requireContext().getColor(R.color.dialog_action))
                val size = (18 * resources.displayMetrics.density).toInt()
                setBounds(0, 0, size, size)
                start()
            }
            button.tag = spinner
            button.text = ""
            button.setCompoundDrawablesRelative(spinner, null, null, null)
        } else {
            (button.tag as? CircularProgressDrawable)?.stop()
            button.tag = null
            button.setCompoundDrawablesRelative(null, null, null, null)
            button.text = getString(R.string.donation_submit)
        }
    }

    private fun showOutcomeDialog(titleRes: Int, messageRes: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun loadInstalledAppIcon(packageCandidates: List<String>): Drawable? {
        val packageName = resolveInstalledPackage(requireContext(), packageCandidates) ?: return null
        val packageManager = requireContext().packageManager

        return runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    private fun showEmulatorSelectorDialog(dialogContentView: View) {
        (dialogContentView.parent as? ViewGroup)?.removeView(dialogContentView)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_emulators_label)
            .setView(dialogContentView)
            .setNeutralButton(R.string.emulator_selector_select_all, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            fun refreshNeutralButton() {
                val installed = installedEmulatorToggles()
                val allEnabled = installed.isNotEmpty() && installed.all { it.second }
                neutralButton.setText(
                    if (allEnabled) R.string.emulator_selector_select_none else R.string.emulator_selector_select_all
                )
                neutralButton.isEnabled = installed.size > 1
            }

            refreshNeutralButton()
            neutralButton.setOnClickListener {
                val installed = installedEmulatorToggles()
                val allEnabled = installed.isNotEmpty() && installed.all { it.second }
                installed.forEach { (setEnabled, enabled) ->
                    if (allEnabled) setEnabled(false) else if (!enabled) setEnabled(true)
                }
                refreshNeutralButton()
            }
        }

        dialog.show()
    }

    private fun installedEmulatorToggles(): List<Pair<(Boolean) -> Unit, Boolean>> {
        val state = viewModel.state.value
        return buildList {
            if (state.retroArchInstalled) add(viewModel::setRetroArchEnabled to state.retroArchEnabled)
            if (state.dolphinInstalled) add(viewModel::setDolphinEnabled to state.dolphinEnabled)
            if (state.ppssppInstalled) add(viewModel::setPpssppEnabled to state.ppssppEnabled)
            if (state.armsx2Installed) add(viewModel::setArmsx2Enabled to state.armsx2Enabled)
            if (state.flycastInstalled) add(viewModel::setFlycastEnabled to state.flycastEnabled)
            if (state.melonDualDsInstalled) add(viewModel::setMelonDualDsEnabled to state.melonDualDsEnabled)
            if (state.mupen64Installed) add(viewModel::setMupen64Enabled to state.mupen64Enabled)
            if (state.emuCoreXInstalled) add(viewModel::setEmuCoreXEnabled to state.emuCoreXEnabled)
        }
    }

    private fun bindToggle(root: LinearLayout, labelRes: Int): EmulatorToggleViews {
        val label = root.findViewById<TextView>(R.id.tv_label)
        label.setText(labelRes)
        return EmulatorToggleViews(
            row = root,
            icon = root.findViewById(R.id.iv_icon),
            checkBox = root.findViewById(R.id.cb_toggle),
            label = label
        )
    }

    private fun applyToggleRowStyle(toggle: EmulatorToggleViews, isSelected: Boolean) {
        toggle.checkBox.isChecked = isSelected
    }

    private fun shouldRecommendManualSetupForDevice(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return true
        }

        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }

        val deviceFingerprint = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT
        ).joinToString(" ").lowercase()

        return "anbernic" in deviceFingerprint || "mangmi" in deviceFingerprint
    }

    private fun androidVersionLabel(): String = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: Build.VERSION.SDK_INT.toString()
}
