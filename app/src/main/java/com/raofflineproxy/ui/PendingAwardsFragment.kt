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
import com.raofflineproxy.data.PendingAwardUi
import kotlinx.coroutines.launch

class PendingAwardsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_pending_awards, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val awardsAdapter = PendingAwardsAdapter(::confirmDelete)
        val headerAdapter = PendingAwardsHeaderAdapter()

        view.findViewById<RecyclerView>(R.id.rv_pending_awards).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(headerAdapter, awardsAdapter)
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                awardsAdapter.submitList(state.pendingAwards)
                headerAdapter.update(state.pendingAwards.isEmpty())
            }
        }
    }

    private fun confirmDelete(award: PendingAwardUi) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pending_award_delete_title)
            .setMessage(
                getString(
                    R.string.pending_award_delete_message,
                    award.achievementTitle,
                    award.gameTitle
                )
            )
            .setPositiveButton(R.string.pending_award_delete_action) { _, _ ->
                viewModel.deletePendingAward(award)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
