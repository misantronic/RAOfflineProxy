package com.raofflineproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.raofflineproxy.R
import com.raofflineproxy.diagnostics.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewerFragment : Fragment() {
    private var capturedLogs: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_log_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvContent = view.findViewById<TextView>(R.id.tv_log_viewer_content)
        val btnCopy = view.findViewById<Button>(R.id.btn_log_viewer_copy)
        val btnShare = view.findViewById<Button>(R.id.btn_log_viewer_share)

        tvContent.text = getString(R.string.log_viewer_loading)
        btnCopy.isEnabled = false
        btnShare.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            capturedLogs = withContext(Dispatchers.IO) { LogExporter.captureRecentLogs() }
            tvContent.text = capturedLogs.ifBlank { getString(R.string.log_viewer_empty) }
            btnCopy.isEnabled = true
            btnShare.isEnabled = true
        }

        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RAOfflineProxy logs", capturedLogs))
            SnackbarManager.showMessage(getString(R.string.log_viewer_copied), SnackbarDuration.Short)
        }

        btnShare.setOnClickListener {
            val file = LogExporter.writeLogFile(requireContext(), capturedLogs)
            startActivity(LogExporter.shareLogFileIntent(requireContext(), file))
        }
    }
}
