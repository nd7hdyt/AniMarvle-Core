package com.animarvle.app.ui.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetSelectEpisodesBinding
import com.animarvle.app.databinding.ItemEpisodeRangeBinding
import com.animarvle.app.ui.theme.AppTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

data class EpisodeRange(
    val start: Int,
    val end: Int,
) {
    val label: String get() = "EPS: $start-$end"
}

class SelectEpisodesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSelectEpisodesBinding? = null
    private val binding get() = _binding!!

    private var ranges: List<EpisodeRange> = emptyList()
    private var selectedRange: EpisodeRange? = null
    private var onRangeSelected: ((EpisodeRange) -> Unit)? = null

    fun setup(
        ranges: List<EpisodeRange>,
        selectedRange: EpisodeRange?,
        onSelected: (EpisodeRange) -> Unit,
    ) {
        this.ranges = ranges
        this.selectedRange = selectedRange
        this.onRangeSelected = onSelected
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetSelectEpisodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rangesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.rangesRecyclerView.adapter = RangeAdapter(
            ranges = ranges,
            selectedRange = selectedRange,
            onPick = { range ->
                onRangeSelected?.invoke(range)
                dismiss()
            },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class RangeAdapter(
        private val ranges: List<EpisodeRange>,
        private val selectedRange: EpisodeRange?,
        private val onPick: (EpisodeRange) -> Unit,
    ) : RecyclerView.Adapter<RangeAdapter.Holder>() {

        class Holder(val binding: ItemEpisodeRangeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemEpisodeRangeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = ranges.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val range = ranges[position]
            val isSelected = range == selectedRange
            val context = holder.itemView.context
            val accentColor = AppTheme.accent(context)
            val defaultColor = ContextCompat.getColor(context, R.color.on_bg)

            holder.binding.rangeLabel.text = range.label
            holder.binding.rangeLabel.setTextColor(if (isSelected) accentColor else defaultColor)
            holder.binding.checkIcon.isVisible = isSelected

            holder.itemView.setOnClickListener { onPick(range) }
        }
    }

    companion object {
        const val TAG = "SelectEpisodesBottomSheet"

        fun newInstance(
            ranges: List<EpisodeRange>,
            selectedRange: EpisodeRange?,
            onSelected: (EpisodeRange) -> Unit,
        ): SelectEpisodesBottomSheet {
            return SelectEpisodesBottomSheet().apply {
                setup(ranges, selectedRange, onSelected)
            }
        }
    }
}
