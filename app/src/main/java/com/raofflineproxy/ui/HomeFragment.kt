package com.raofflineproxy.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
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
        val tvDocsLink = view.findViewById<TextView>(R.id.tv_docs_link)

        val fullText = getString(R.string.home_docs_link)
        val linkWord = "documentation"
        val spannable = SpannableString(fullText)
        val start = fullText.indexOf(linkWord)
        val end = start + linkWord.length
        val linkColor = requireContext().getColor(R.color.primary)

        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.home_docs_url))))
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        tvDocsLink.text = spannable
        tvDocsLink.movementMethod = LinkMovementMethod.getInstance()

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
