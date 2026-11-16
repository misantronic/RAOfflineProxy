package com.raofflineproxy.ui

import android.content.Intent
import android.graphics.Paint
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
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
import com.raofflineproxy.donation.SupportPrompt
import com.raofflineproxy.usage.UsageCounterKeys
import com.raofflineproxy.usage.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SupportDialog(
    private val activity: AppCompatActivity,
    private val viewModel: MainViewModel,
    private val prompt: SupportPrompt? = null
) {
    private val dialogView: View =
        LayoutInflater.from(activity).inflate(R.layout.dialog_support_donation, null, false)
    private val frequencyToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggle_donation_frequency)
    private val deliveryToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggle_donation_delivery)
    private val amountDropdown = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_donation_amount)
    private val emailInput = dialogView.findViewById<TextInputLayout>(R.id.input_donation_email)
    private val emailEdit = dialogView.findViewById<TextInputEditText>(R.id.et_donation_email)
    private val testCheckbox = dialogView.findViewById<CheckBox>(R.id.cb_donation_test)
    private val moreOptions = dialogView.findViewById<View>(R.id.layout_donation_more_options)
    private val moreOptionsButton = dialogView.findViewById<Button>(R.id.btn_donation_more_options)

    private val isMonthly: Boolean
        get() = frequencyToggle.checkedButtonId == R.id.btn_donation_monthly
    private val isEmailDelivery: Boolean
        get() = deliveryToggle.checkedButtonId == R.id.btn_donation_email_link

    fun show(): AlertDialog {
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.support_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.donation_submit, null)
        if (prompt != null) {
            dialogView.findViewById<TextView>(R.id.tv_donation_message).text =
                activity.getString(R.string.support_prompt_message, prompt.syncedAwards)
            builder
                .setNegativeButton(R.string.support_prompt_not_now, null)
                .setNeutralButton(R.string.support_prompt_never) { _, _ -> viewModel.disableSupportPrompt() }
        } else {
            builder.setNegativeButton(android.R.string.cancel, null)
        }
        val dialog = builder.create()

        frequencyToggle.check(R.id.btn_donation_onetime)
        deliveryToggle.check(R.id.btn_donation_pay_now)
        updateFieldVisibility()
        frequencyToggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updateFieldVisibility() }
        deliveryToggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updateFieldVisibility() }

        addQuickAmountButtons(dialog)
        val kofiLink = dialogView.findViewById<TextView>(R.id.btn_donation_kofi)
        kofiLink.paintFlags = kofiLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        kofiLink.setOnClickListener {
            recordStarted()
            activity.startActivity(Intent(Intent.ACTION_VIEW, activity.getString(R.string.support_kofi_url).toUri()))
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            val submitButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submitButton.visibility = View.GONE
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                ?.setTextColor(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).textColors)
            moreOptionsButton.setOnClickListener {
                moreOptionsButton.visibility = View.GONE
                moreOptions.visibility = View.VISIBLE
                submitButton.visibility = View.VISIBLE
            }
            submitButton.setOnClickListener { submit(dialog, submitButton) }
        }

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        widenForHandhelds(dialog)
        UsageStats.recordFeature(
            if (prompt != null) UsageCounterKeys.FEATURE_SUPPORT_PROMPT_SHOWN else UsageCounterKeys.FEATURE_SUPPORT_OPENED
        )
        return dialog
    }

    private fun addQuickAmountButtons(dialog: AlertDialog) {
        val container = dialogView.findViewById<LinearLayout>(R.id.layout_donation_quick_amounts)
        val gapPx = (8 * activity.resources.displayMetrics.density).toInt()
        DonationLinks.QUICK_ONE_TIME.forEachIndexed { index, option ->
            container.addView(MaterialButton(activity).apply {
                text = option.label
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (index == 0) 0 else gapPx
                }
                setOnClickListener { openCheckout(option, dialog) }
            })
        }
    }

    private fun amountOptions(): List<DonationAmountOption> =
        if (isMonthly) DonationLinks.MONTHLY else DonationLinks.ONE_TIME

    private fun defaultAmountIndex(): Int =
        if (isMonthly) DonationLinks.MONTHLY_DEFAULT_INDEX else DonationLinks.ONE_TIME_DEFAULT_INDEX

    private fun selectedAmountOption(): DonationAmountOption {
        val options = amountOptions()
        val selectedLabel = amountDropdown.text.toString()
        return options.firstOrNull { it.label == selectedLabel } ?: options[defaultAmountIndex()]
    }

    private fun updateFieldVisibility() {
        emailInput.visibility = if (isEmailDelivery) View.VISIBLE else View.GONE
        testCheckbox.visibility = if (BuildConfig.DEBUG && isEmailDelivery) View.VISIBLE else View.GONE

        val labels = amountOptions().map { it.label }
        amountDropdown.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels))
        amountDropdown.setText(labels.getOrElse(defaultAmountIndex()) { labels.first() }, false)
    }

    private fun recordStarted() {
        UsageStats.recordFeature(
            if (prompt != null) UsageCounterKeys.FEATURE_SUPPORT_PROMPT_STARTED else UsageCounterKeys.FEATURE_SUPPORT_STARTED
        )
    }

    // No backend call: opens the hosted Stripe link. The RA username goes along as
    // client_reference_id; monthly links use it to tie the subscription to the account
    // (linkRaUsernameFromCheckoutSession in the webhook).
    private fun openCheckout(option: DonationAmountOption, dialog: AlertDialog) {
        recordStarted()
        activity.lifecycleScope.launch {
            val raUsername = viewModel.currentRaCredentials()?.user
            val url = if (raUsername != null) {
                option.url.toUri().buildUpon()
                    .appendQueryParameter("client_reference_id", raUsername)
                    .build()
            } else {
                option.url.toUri()
            }
            activity.startActivity(Intent(Intent.ACTION_VIEW, url))
            dialog.dismiss()
        }
    }

    private fun submit(dialog: AlertDialog, submitButton: Button) {
        emailInput.error = null
        val amountOption = selectedAmountOption()
        if (!isEmailDelivery) {
            openCheckout(amountOption, dialog)
            return
        }

        val email = emailEdit.text?.toString()?.trim().orEmpty()
        val emailError = when {
            email.isBlank() -> R.string.donation_email_required
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> R.string.donation_email_invalid
            else -> null
        }
        if (emailError != null) {
            emailInput.error = activity.getString(emailError)
            return
        }
        requestEmailInvoice(dialog, submitButton, amountOption, email)
    }

    private fun requestEmailInvoice(
        dialog: AlertDialog,
        submitButton: Button,
        amountOption: DonationAmountOption,
        email: String
    ) {
        val monthly = isMonthly
        val useTestMode = BuildConfig.DEBUG && testCheckbox.isChecked
        setButtonLoading(submitButton, true)
        activity.lifecycleScope.launch {
            val raCredentials = if (monthly) viewModel.currentRaCredentials() else null
            val result = withContext(Dispatchers.IO) {
                DonationManager.requestEmailInvoice(
                    amountOption.amountCents,
                    if (monthly) "monthly" else "once",
                    email,
                    raCredentials,
                    useTestMode
                )
            }
            setButtonLoading(submitButton, false)
            result.onSuccess {
                recordStarted()
                viewModel.setHideSupportButtonEnabled(true)
                dialog.dismiss()
                showOutcomeDialog(R.string.donation_email_sent_title, R.string.donation_email_sent_message)
            }.onFailure {
                showOutcomeDialog(R.string.support_dialog_title, R.string.donation_error_message)
            }
        }
    }

    // AlertDialog sizes itself as a percentage of screen width, which is too narrow on the
    // squarish 4:3 handheld screens this app targets. Use most of the width, capped for tablets.
    private fun widenForHandhelds(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val density = activity.resources.displayMetrics.density
        val screenWidthPx = activity.resources.displayMetrics.widthPixels
        val targetWidthPx = maxOf((340 * density).toInt(), (screenWidthPx * 0.92f).toInt())
        val maxWidthPx = minOf((600 * density).toInt(), (screenWidthPx * 0.95f).toInt())
        window.setLayout(minOf(targetWidthPx, maxWidthPx), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setButtonLoading(button: Button, loading: Boolean) {
        button.isEnabled = !loading
        if (loading) {
            val spinner = CircularProgressDrawable(activity).apply {
                setStyle(CircularProgressDrawable.DEFAULT)
                setColorSchemeColors(activity.getColor(R.color.dialog_action))
                val size = (18 * activity.resources.displayMetrics.density).toInt()
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
            button.text = activity.getString(R.string.donation_submit)
        }
    }

    private fun showOutcomeDialog(titleRes: Int, messageRes: Int) {
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
