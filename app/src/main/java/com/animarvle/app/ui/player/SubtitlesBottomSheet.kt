package com.animarvle.app.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetSubtitlesBinding
import com.animarvle.app.databinding.ItemChoiceRowBinding
import com.animarvle.sourceapi.SubtitleTrack
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class SubtitlesBottomSheet(
    private val context: Context,
    private val subtitles: List<SubtitleTrack>,
    private val selectedSubtitle: SubtitleTrack?,
    private val currentDelayMs: Long,
    private val onDismiss: (() -> Unit)? = null,
    private val onSelectSubtitle: (SubtitleTrack?) -> Unit,
    private val onDelayChange: (Long) -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    private var delayMs: Long = currentDelayMs

    fun show(): Dialog {
        val binding = BottomSheetSubtitlesBinding.inflate(LayoutInflater.from(context))
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density

        binding.dragHandle.isVisible = !isLandscape

        val dialog: Dialog = if (isLandscape) {
            ComponentDialog(context, R.style.Theme_Animarvle_FloatingDialog).apply {
                setContentView(binding.root)
                setCanceledOnTouchOutside(true)
                binding.cardRoot.setBackgroundResource(R.drawable.bg_vortex_glass_card)
                window?.apply {
                    setBackgroundDrawableResource(R.drawable.bg_vortex_glass_card)
                    setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    setWindowAnimations(R.style.Animation_Animarvle_FloatingCard)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setBackgroundBlurRadius((55 * density).toInt())
                    }
                    setDimAmount(0.10f)

                    setLayout(
                        (displayMetrics.widthPixels * 0.70f).toInt(),
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                    attributes = attributes.apply {
                        y = (6 * density).toInt()
                    }
                }
                setOnShowListener {
                    binding.cardRoot.post {
                        VortexBlurHelper.applyCardBackdropBlur(context, binding.cardRoot)
                    }
                }
            }
        } else {
            BottomSheetDialog(context).apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window?.setBackgroundBlurRadius((55 * density).toInt())
                }
                window?.setDimAmount(0.12f)
                binding.cardRoot.setBackgroundResource(R.drawable.bg_vortex_glass_card)
                setContentView(binding.root)
                setOnShowListener {
                    val bottomSheet = findViewById<FrameLayout>(
                        com.google.android.material.R.id.design_bottom_sheet
                    ) ?: return@setOnShowListener
                    bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                    BottomSheetBehavior.from(bottomSheet).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                    }
                    binding.cardRoot.post {
                        VortexBlurHelper.applyCardBackdropBlur(context, binding.cardRoot)
                    }
                }
            }
        }

        updateDelayDisplay(binding)

        binding.btnDelayMinus.setOnClickListener {
            delayMs -= 500L
            updateDelayDisplay(binding)
            onDelayChange(delayMs)
            if (isLandscape) {
                binding.cardRoot.post {
                    VortexBlurHelper.applyCardBackdropBlur(context, binding.cardRoot)
                }
            }
        }

        binding.btnDelayPlus.setOnClickListener {
            delayMs += 500L
            updateDelayDisplay(binding)
            onDelayChange(delayMs)
            if (isLandscape) {
                binding.cardRoot.post {
                    VortexBlurHelper.applyCardBackdropBlur(context, binding.cardRoot)
                }
            }
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

        val scrollbar = VortexScrollbarDecoration(context)
        val itemHeightDp = 52f
        binding.subtitlesList.apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            maxHeightPx = (3.5f * itemHeightDp * density).toInt()
            addItemDecoration(scrollbar)
            addOnItemTouchListener(scrollbar)
            adapter = SubtitleAdapter(rows) { index ->
                dialog.dismiss()
                onSelectSubtitle(allTracks[index])
            }
            post {
                val selectedIdx = rows.indexOfFirst { it.selected }
                if (selectedIdx >= 3) {
                    (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(selectedIdx - 2, 0)
                }
                invalidateItemDecorations()
                if (isLandscape) {
                    VortexBlurHelper.applyCardBackdropBlur(context, binding.cardRoot)
                }
            }
        }

        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
        return dialog
    }

    private fun updateDelayDisplay(binding: BottomSheetSubtitlesBinding) {
        val seconds = delayMs / 1000.0
        val text = when {
            seconds > 0 -> String.format("+%.1fs", seconds)
            seconds < 0 -> String.format("-%.1fs", kotlin.math.abs(seconds))
            else -> "0.0s"
        }
        binding.delayText.text = text
    }

    private class SubtitleAdapter(
        private val rows: List<ChoiceRow>,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<SubtitleAdapter.Holder>() {

        class Holder(val binding: ItemChoiceRowBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemChoiceRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            ),
        )

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.binding.rowLabel.text = row.label
            if (row.selected) {
                holder.binding.rowLabel.setTextColor(Color.WHITE)
                holder.binding.rowLabel.setTypeface(null, Typeface.BOLD)
                holder.binding.rowRadio.setImageResource(R.drawable.ic_vortex_radio_checked)
            } else {
                holder.binding.rowLabel.setTextColor(Color.parseColor("#99FFFFFF"))
                holder.binding.rowLabel.setTypeface(null, Typeface.NORMAL)
                holder.binding.rowRadio.setImageResource(R.drawable.ic_vortex_radio_unchecked)
            }
            holder.binding.root.setOnClickListener { onPick(position) }
        }
    }
}
