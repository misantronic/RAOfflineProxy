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
import android.widget.ArrayAdapter
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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.R
import com.raofflineproxy.donation.DonationAmountOption
import com.raofflineproxy.donation.DonationLinks
import com.raofflineproxy.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private data class EmulatorToggleViews(
        val emulator: Emulator,
        val row: LinearLayout,
        val icon: ImageView,
        val checkBox: CheckBox,
        val appIcon: Drawable?
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
        val toggles = inflateEmulatorToggles(
            emulatorSelectorDialogView.findViewById(R.id.layout_emulator_toggles)
        )

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

        toggles.forEach { toggle ->
            toggle.row.setOnClickListener {
                if (toggle.row.isEnabled) {
                    viewModel.setEmulatorEnabled(
                        toggle.emulator,
                        !viewModel.state.value.emulators.isEnabled(toggle.emulator)
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                val installedCount = state.emulators.installedCount
                val noEmulatorInstalled = installedCount == 0
                val onlyOneInstalled = installedCount == 1
                val hasManualSetupManagedEmulator = Emulator.SHIZUKU_MANAGED.any { state.emulators.isInstalled(it) }
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
                btnStartProxy.isEnabled = if (state.proxyRunning) !proxyStartPending else !proxyStartPending && state.hasEnabledEmulator
                btnStartProxy.alpha = if (proxyStartPending) 0.45f else 1f
                btnManualEmulatorSetup.visibility = if (shouldShowManualSetupButton) View.VISIBLE else View.GONE
                btnGoToCachedGames.visibility = if (state.proxyRunning) View.VISIBLE else View.GONE
                // Only makes sense once the user is actually logged into RA — that's also the
                // data a monthly donation needs to tie itself to their account.
                btnSupportDevelopment.visibility =
                    if (!state.hideSupportButton && state.hasLoginCredentials) View.VISIBLE else View.GONE

                emulatorLabel.visibility = if (installedCount > 0) View.VISIBLE else View.GONE
                emulatorSelector.visibility = if (installedCount > 0) View.VISIBLE else View.GONE
                emulatorSelector.isEnabled = !state.proxyRunning
                emulatorSelector.alpha = if (state.proxyRunning) 0.6f else 1f
                toggles.forEach { toggle ->
                    val installed = state.emulators.isInstalled(toggle.emulator)
                    toggle.row.visibility = if (installed) View.VISIBLE else View.GONE
                    toggle.row.isEnabled = installed && !state.proxyRunning && !onlyOneInstalled
                    toggle.row.alpha = if (toggle.row.isEnabled) 1f else 0.5f
                    toggle.icon.setImageDrawable(toggle.appIcon)
                    toggle.checkBox.isEnabled = toggle.row.isEnabled
                    toggle.checkBox.isChecked = state.emulators.isEnabled(toggle.emulator)
                }

                enabledEmulatorIcons.removeAllViews()
                val iconSizePx = (28 * resources.displayMetrics.density).toInt()
                val iconSpacingPx = (6 * resources.displayMetrics.density).toInt()
                toggles.forEach { toggle ->
                    val icon = toggle.appIcon ?: return@forEach
                    if (!state.emulators.isEnabled(toggle.emulator)) return@forEach
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

    private fun showSupportDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_support_donation, null, false)
        val frequencyToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggle_donation_frequency)
        val deliveryToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggle_donation_delivery)
        val amountDropdown = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_donation_amount)
        val emailInput = dialogView.findViewById<TextInputLayout>(R.id.input_donation_email)
        val emailEdit = dialogView.findViewById<TextInputEditText>(R.id.et_donation_email)
        val kofiButton = dialogView.findViewById<Button>(R.id.btn_donation_kofi)
        val testCheckbox = dialogView.findViewById<CheckBox>(R.id.cb_donation_test)

        fun amountOptionsFor(isMonthly: Boolean) = if (isMonthly) DonationLinks.MONTHLY else DonationLinks.ONE_TIME
        fun defaultIndexFor(isMonthly: Boolean) = if (isMonthly) DonationLinks.MONTHLY_DEFAULT_INDEX else DonationLinks.ONE_TIME_DEFAULT_INDEX
        fun selectedAmountOption(isMonthly: Boolean): DonationAmountOption {
            val options = amountOptionsFor(isMonthly)
            val selectedLabel = amountDropdown.text.toString()
            return options.firstOrNull { it.label == selectedLabel } ?: options[defaultIndexFor(isMonthly)]
        }

        frequencyToggle.check(R.id.btn_donation_monthly)
        deliveryToggle.check(R.id.btn_donation_pay_now)

        fun updateFieldVisibility() {
            val isMonthly = frequencyToggle.checkedButtonId == R.id.btn_donation_monthly
            val isEmailDelivery = deliveryToggle.checkedButtonId == R.id.btn_donation_email_link
            emailInput.visibility = if (isEmailDelivery) View.VISIBLE else View.GONE
            testCheckbox.visibility = if (BuildConfig.DEBUG && isEmailDelivery) View.VISIBLE else View.GONE

            val labels = amountOptionsFor(isMonthly).map { it.label }
            amountDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
            amountDropdown.setText(labels.getOrElse(defaultIndexFor(isMonthly)) { labels.first() }, false)
        }
        updateFieldVisibility()
        frequencyToggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updateFieldVisibility() }
        deliveryToggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updateFieldVisibility() }

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
                emailInput.error = null

                val isMonthly = frequencyToggle.checkedButtonId == R.id.btn_donation_monthly
                val isEmailDelivery = deliveryToggle.checkedButtonId == R.id.btn_donation_email_link
                val amountOption = selectedAmountOption(isMonthly)

                if (!isEmailDelivery) {
                    // Pay now: no backend call, just open the matching hosted Stripe link. Pass
                    // the RA username along as client_reference_id when logged in — monthly
                    // links use it to tie the subscription to the account (see the webhook's
                    // linkRaUsernameFromCheckoutSession); one-time links carry it too for
                    // consistency, even though nothing on the backend reads it there yet.
                    viewLifecycleOwner.lifecycleScope.launch {
                        val raUsername = viewModel.currentRaCredentials()?.user
                        val url = if (raUsername != null) {
                            amountOption.url.toUri().buildUpon()
                                .appendQueryParameter("client_reference_id", raUsername)
                                .build()
                        } else {
                            amountOption.url.toUri()
                        }
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        dialog.dismiss()
                    }
                    return@setOnClickListener
                }

                val useTestMode = BuildConfig.DEBUG && testCheckbox.isChecked
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
                    val raCredentials = if (isMonthly) viewModel.currentRaCredentials() else null
                    val result = withContext(Dispatchers.IO) {
                        DonationManager.requestEmailInvoice(amountOption.amountCents, frequency, email, raCredentials, useTestMode)
                    }
                    setButtonLoading(positiveButton, false)
                    result.onSuccess {
                        viewModel.setHideSupportButtonEnabled(true)
                        dialog.dismiss()
                        showOutcomeDialog(R.string.donation_email_sent_title, R.string.donation_email_sent_message)
                    }.onFailure {
                        showOutcomeDialog(R.string.support_dialog_title, R.string.donation_error_message)
                    }
                }
            }
        }

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        // AlertDialog sizes itself as a percentage of screen width by default, which on
        // squarish/4:3 handheld screens (this app's target devices) leaves it uncomfortably
        // narrow — target most of the screen width there, capped so it doesn't get absurdly
        // wide on tablets/desktops.
        dialog.window?.let { window ->
            val density = resources.displayMetrics.density
            val screenWidthPx = resources.displayMetrics.widthPixels
            val targetWidthPx = maxOf((340 * density).toInt(), (screenWidthPx * 0.92f).toInt())
            val maxWidthPx = minOf((600 * density).toInt(), (screenWidthPx * 0.95f).toInt())
            window.setLayout(minOf(targetWidthPx, maxWidthPx), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
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
                val support = viewModel.state.value.emulators
                val allEnabled = support.installedCount > 0 && support.installed.all { support.isEnabled(it) }
                neutralButton.setText(
                    if (allEnabled) R.string.emulator_selector_select_none else R.string.emulator_selector_select_all
                )
                neutralButton.isEnabled = support.installedCount > 1
            }

            refreshNeutralButton()
            neutralButton.setOnClickListener {
                val support = viewModel.state.value.emulators
                val enableAll = !support.installed.all { support.isEnabled(it) }
                support.installed.forEach { emulator ->
                    if (support.isEnabled(emulator) != enableAll) {
                        viewModel.setEmulatorEnabled(emulator, enableAll)
                    }
                }
                refreshNeutralButton()
            }
        }

        dialog.show()
    }

    private fun inflateEmulatorToggles(container: LinearLayout): List<EmulatorToggleViews> {
        val inflater = LayoutInflater.from(container.context)
        return Emulator.entries.map { emulator ->
            val row = inflater.inflate(R.layout.view_emulator_toggle, container, false) as LinearLayout
            row.visibility = View.GONE
            row.findViewById<TextView>(R.id.tv_label).setText(emulator.labelRes)
            container.addView(row)
            EmulatorToggleViews(
                emulator = emulator,
                row = row,
                icon = row.findViewById(R.id.iv_icon),
                checkBox = row.findViewById(R.id.cb_toggle),
                appIcon = loadInstalledAppIcon(emulator.packageCandidates)
            )
        }
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
