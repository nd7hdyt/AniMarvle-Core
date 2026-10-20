package com.animarvle.app.ui.player

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import androidx.recyclerview.widget.LinearLayoutManager
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetSubtitlesBinding
import com.animarvle.app.databinding.ItemChoiceRowBinding
import com.animarvle.sourceapi.SubtitleTrack
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * AniVortex Subtitles Glassy Bottom Sheet with live offset timing ([-] 0.0s [+])
 * and subtitle appearance settings shortcut [gear].
 */
class SubtitlesBottomSheet(
    private val context: Context,
    private val subtitles: List<SubtitleTrack>,
    private val selectedSubtitle: SubtitleTrack?,
    private val currentDelayMs: Long,
    private val onSelectSubtitle: (SubtitleTrack?) -> Unit,
    private val onDelayChange: (Long) -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    private var delayMs: Long = currentDelayMs

    fun show(): BottomSheetDialog {
        val binding = BottomSheetSubtitlesBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)
        (binding.root.parent as? android.view.View)?.setBackgroundColor(Color.TRANSPARENT)

        updateDelayDisplay(binding)

        binding.btnDelayMinus.setOnClickListener {
            delayMs -= 500L
            updateDelayDisplay(binding)
            onDelayChange(delayMs)
        }

        binding.btnDelayPlus.setOnClickListener {
            delayMs += 500L
            updateDelayDisplay(binding)
            onDelayChange(delayMs)
        }

        binding.btnSubtitleSettings.setOnClickListener {
            dialog.dismiss()
            onOpenSettings()
        }

        val allTracks = listOf<SubtitleTrack?>(null) + subtitles
        val rows = allTracks.map { track ->
            val label = track?.label ?: context.getString(R.string.player_sub_off)
            val isSelected = if (track == null) selectedSubtitle == null else track.url == selectedSubtitle?.url
            ChoiceRow(label, isSelected)
        }

        binding.subtitlesList.layoutManager = LinearLayoutManager(context)
        binding.subtitlesList.adapter = SubtitleAdapter(rows) { index ->
            dialog.dismiss()
            onSelectSubtitle(allTracks[index])
        }

        dialog.show()
        (binding.root.parent as? android.view.View)?.setBackgroundColor(Color.TRANSPARENT)
        return dialog
    }

    private fun updateDelayDisplay(binding: BottomSheetSubtitlesBinding) {
        val seconds = delayMs / 1000.0
        val text = if (seconds > 0) {
            String.format("+%.1fs", seconds)
        } else if (seconds < 0) {
            String.format("-%.1fs", kotlin.math.abs(seconds))
        } else {
            "0.0s"
        }
        binding.delayText.text = text
    }

    private class SubtitleAdapter(
        private val rows: List<ChoiceRow>,
        private val onPick: (Int) -> Unit,
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SubtitleAdapter.Holder>() {

        class Holder(val binding: ItemChoiceRowBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder = Holder(
            ItemChoiceRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            ),
        )

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.binding.rowLabel.text = row.label
            holder.binding.rowRadio.setImageResource(
                if (row.selected) R.drawable.ic_vortex_radio_checked else R.drawable.ic_vortex_radio_unchecked,
            )
            holder.binding.root.setOnClickListener { onPick(position) }
        }
    }
}
