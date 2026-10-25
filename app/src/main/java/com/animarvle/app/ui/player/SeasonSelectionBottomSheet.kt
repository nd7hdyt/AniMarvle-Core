package com.animarvle.app.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetSelectSeasonBinding
import com.animarvle.app.databinding.ItemSeasonChoiceBinding
import com.animarvle.app.domain.SeasonUi
import com.animarvle.app.ui.theme.AppTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SeasonSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSelectSeasonBinding? = null
    private val binding get() = _binding!!

    private var seasons: List<SeasonUi> = emptyList()
    private var currentSeasonNumber: Int = 1
    private var onSeasonSelected: ((Int) -> Unit)? = null

    fun setup(
        seasons: List<SeasonUi>,
        currentSeasonNumber: Int,
        onSelected: (Int) -> Unit,
    ) {
        this.seasons = seasons
        this.currentSeasonNumber = currentSeasonNumber
        this.onSeasonSelected = onSelected
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetSelectSeasonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener { dismiss() }

        binding.seasonsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.seasonsRecyclerView.adapter = SeasonAdapter(
            seasons = seasons,
            currentSeasonNumber = currentSeasonNumber,
            onPick = { seasonNum ->
                onSeasonSelected?.invoke(seasonNum)
                dismiss()
            },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class SeasonAdapter(
        private val seasons: List<SeasonUi>,
        private val currentSeasonNumber: Int,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<SeasonAdapter.Holder>() {

        class Holder(val binding: ItemSeasonChoiceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSeasonChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = seasons.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val season = seasons[position]
            val sNum = season.seasonNumber ?: (position + 1)
            val isSelected = sNum == currentSeasonNumber
            val context = holder.itemView.context
            val accentColor = AppTheme.accent(context)
            val defaultColor = ContextCompat.getColor(context, R.color.on_bg)

            val titleText = if (season.title.isNotBlank()) {
                if (season.title.startsWith("Season", ignoreCase = true)) season.title
                else "Season $sNum: ${season.title}"
            } else {
                "Season $sNum"
            }
            holder.binding.tvSeasonTitle.text = titleText
            holder.binding.tvSeasonTitle.setTextColor(if (isSelected) accentColor else defaultColor)

            val epCount = season.episodeCount
            if (epCount != null && epCount > 0) {
                holder.binding.tvSeasonEpisodeCount.isVisible = true
                holder.binding.tvSeasonEpisodeCount.text = "$epCount Episodes"
            } else {
                holder.binding.tvSeasonEpisodeCount.isVisible = false
            }

            holder.binding.checkIcon.isVisible = isSelected

            holder.itemView.setOnClickListener { onPick(sNum) }
        }
    }

    companion object {
        const val TAG = "SeasonSelectionBottomSheet"

        fun newInstance(
            seasons: List<SeasonUi>,
            currentSeasonNumber: Int,
            onSelected: (Int) -> Unit,
        ): SeasonSelectionBottomSheet = SeasonSelectionBottomSheet().apply {
            setup(seasons, currentSeasonNumber, onSelected)
        }
    }
}
