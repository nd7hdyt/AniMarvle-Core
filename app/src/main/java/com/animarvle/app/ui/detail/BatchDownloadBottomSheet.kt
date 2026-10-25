package com.animarvle.app.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.DialogBatchDownloadBinding
import com.animarvle.app.databinding.ItemEpisodeBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BatchDownloadBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogBatchDownloadBinding? = null
    private val binding get() = _binding!!

    private var totalEpisodes: Int = 1
    private val selectedEpisodes = mutableSetOf<Int>()
    private var onConfirmListener: ((List<Int>) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogBatchDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        totalEpisodes = arguments?.getInt(ARG_TOTAL_EPISODES)?.takeIf { it > 0 } ?: 1

        val adapter = EpisodeSelectAdapter(
            total = totalEpisodes,
            selected = selectedEpisodes,
            onToggle = { epNum ->
                if (selectedEpisodes.contains(epNum)) {
                    selectedEpisodes.remove(epNum)
                } else {
                    selectedEpisodes.add(epNum)
                }
                updateActionButtons()
            },
        )

        val columnWidthDp = 58
        val screenWidthDp = resources.configuration.screenWidthDp
        val spanCount = maxOf(4, screenWidthDp / columnWidthDp)
        binding.episodeGrid.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.episodeGrid.adapter = adapter

        binding.btnToggleSelectAll.setOnClickListener {
            if (selectedEpisodes.size == totalEpisodes) {
                selectedEpisodes.clear()
            } else {
                selectedEpisodes.clear()
                for (i in 1..totalEpisodes) {
                    selectedEpisodes.add(i)
                }
            }
            adapter.notifyDataSetChanged()
            updateActionButtons()
        }

        binding.btnDownloadConfirm.setOnClickListener {
            if (selectedEpisodes.isNotEmpty()) {
                onConfirmListener?.invoke(selectedEpisodes.sorted())
                dismiss()
            }
        }

        updateActionButtons()
    }

    fun setOnConfirmListener(listener: (List<Int>) -> Unit) {
        onConfirmListener = listener
    }

    private fun updateActionButtons() {
        val count = selectedEpisodes.size
        binding.btnDownloadConfirm.isEnabled = count > 0
        binding.btnDownloadConfirm.text = if (count > 0) {
            getString(R.string.download_batch_action, count)
        } else {
            getString(R.string.action_download)
        }

        if (count == totalEpisodes) {
            binding.btnToggleSelectAll.setText(R.string.download_batch_deselect_all)
        } else {
            binding.btnToggleSelectAll.setText(R.string.download_batch_select_all)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class EpisodeSelectAdapter(
        private val total: Int,
        private val selected: Set<Int>,
        private val onToggle: (Int) -> Unit,
    ) : RecyclerView.Adapter<EpisodeSelectAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemEpisodeBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val epNum = position + 1
            holder.binding.epLabel.text = epNum.toString()
            val isSelected = selected.contains(epNum)

            if (isSelected) {
                holder.binding.epLabel.setBackgroundResource(R.drawable.bg_ep_tile_selected)
                holder.binding.epLabel.setTextColor(android.graphics.Color.BLACK)
            } else {
                holder.binding.epLabel.setBackgroundResource(R.drawable.bg_ep_tile)
                holder.binding.epLabel.setTextColor(
                    holder.itemView.context.getColor(R.color.on_bg)
                )
            }

            holder.itemView.setOnClickListener {
                onToggle(epNum)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount(): Int = total
    }

    companion object {
        const val TAG = "BatchDownloadBottomSheet"
        private const val ARG_TOTAL_EPISODES = "total_episodes"

        fun newInstance(totalEpisodes: Int): BatchDownloadBottomSheet {
            return BatchDownloadBottomSheet().apply {
                arguments = Bundle().apply { putInt(ARG_TOTAL_EPISODES, totalEpisodes) }
            }
        }
    }
}
