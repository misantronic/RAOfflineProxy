package com.raofflineproxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class PendingAwardsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_pending_awards, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val awardsAdapter = PendingAwardsAdapter()
        val headerAdapter = PendingAwardsHeaderAdapter()

        view.findViewById<RecyclerView>(R.id.rv_pending_awards).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(headerAdapter, awardsAdapter)
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        }

        var snackbar: Snackbar? = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                awardsAdapter.submitList(state.pendingAwards)
                headerAdapter.update(state.pendingAwards.isEmpty())

                val msg = state.flushProgress
                if (state.flushInProgress || msg == null) {
                    snackbar = updateSnackbar(view, msg, state.flushInProgress, snackbar)
                    return@collect
                }

                snackbar?.dismiss()
                snackbar = null
                Snackbar.make(view, msg, Snackbar.LENGTH_LONG).show()
                viewModel.clearFlushProgress()
            }
        }
    }
}
