package com.raofflineproxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.raofflineproxy.R
import kotlinx.coroutines.launch

class AwardsHistoryFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_awards_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val awardsAdapter = PendingAwardsAdapter(onDelete = null)
        val headerAdapter = AwardsHistoryHeaderAdapter(::confirmClear)

        view.findViewById<RecyclerView>(R.id.rv_awards_history).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(headerAdapter, awardsAdapter)
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
            itemAnimator = null
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                awardsAdapter.submitList(state.awardHistory)
                headerAdapter.update(
                    AwardsHistoryHeaderAdapter.HeaderState(
                        awardCount = state.awardHistory.size,
                        clearEnabled = state.awardHistory.isNotEmpty() && state.pendingAwards.isEmpty(),
                        showEmpty = state.awardHistory.isEmpty()
                    )
                )
            }
        }
    }

    private fun confirmClear() {
        if (viewModel.state.value.pendingAwards.isNotEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.awards_history_clear_blocked_title)
                .setMessage(R.string.awards_history_clear_blocked_message)
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.awards_history_clear_title)
            .setMessage(R.string.awards_history_clear_message)
            .setPositiveButton(R.string.clear_action) { _, _ ->
                viewModel.clearAwardHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
