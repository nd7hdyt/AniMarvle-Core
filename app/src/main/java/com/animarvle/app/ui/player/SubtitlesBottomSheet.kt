package com.animarvle.app.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
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

/**
 * AniVortex Subtitles Glassy Bottom Sheet / Floating Dialog.
 * Supports country flag emojis, language grouping with track counts, and drill-down
 * selection for multiple releases / duplicates per language.
 */
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

    data class LanguageGroup(
        val code: String,
        val displayName: String,
        val flag: String,
        val tracks: List<SubtitleTrack>,
    )

    private sealed interface DisplayRow {
        data class Off(val isSelected: Boolean) : DisplayRow
        data class Language(val group: LanguageGroup, val isSelected: Boolean) : DisplayRow
        data class Track(val track: SubtitleTrack, val isSelected: Boolean, val index: Int) : DisplayRow
    }

    private var activeDrilledGroup: LanguageGroup? = null
    private var currentSearchQuery: String = ""

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
                        WindowManager.LayoutParams.WRAP_CONTENT,
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
                        com.google.android.material.R.id.design_bottom_sheet,
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

        // Group subtitles by resolved language display name
        val allGroups = subtitles.groupBy { track ->
            val disp = track.display?.takeIf { it.isNotBlank() }
                ?: SpokenLanguage.fromRawTag(track.language)?.displayName
                ?: track.language.uppercase()
            disp.trim()
        }.map { (displayName, tracksForLang) ->
            val first = tracksForLang.first()
            val code = first.language.lowercase().trim()
            val flag = LanguageFlagHelper.getFlag(code, displayName)
            LanguageGroup(
                code = code,
                displayName = displayName,
                flag = flag,
                tracks = tracksForLang.sortedByDescending { it.matchScore ?: 0 },
            )
        }.sortedWith(
            compareBy<LanguageGroup> { group ->
                // Put English or selected language group towards top if desired
                when {
                    group.tracks.any { it.url == selectedSubtitle?.url } -> 0
                    group.code == "en" -> 1
                    else -> 2
                }
            }.thenBy { it.displayName }
        )

        // Show search bar if there are more than 5 language groups
        binding.searchContainer.isVisible = allGroups.size > 5

        lateinit var adapter: UnifiedSubtitleAdapter

        fun buildRows(): List<DisplayRow> {
            val drilled = activeDrilledGroup
            if (drilled != null) {
                return drilled.tracks.mapIndexed { idx, track ->
                    DisplayRow.Track(
                        track = track,
                        isSelected = track.url == selectedSubtitle?.url,
                        index = idx + 1,
                    )
                }
            }

            val filtered = if (currentSearchQuery.isBlank()) {
                allGroups
            } else {
                val q = currentSearchQuery.lowercase()
                allGroups.filter {
                    it.displayName.lowercase().contains(q) || it.code.lowercase().contains(q)
                }
            }

            val list = mutableListOf<DisplayRow>()
            list.add(DisplayRow.Off(isSelected = selectedSubtitle == null))
            filtered.forEach { group ->
                val isSelected = group.tracks.any { it.url == selectedSubtitle?.url }
                list.add(DisplayRow.Language(group, isSelected))
            }
            return list
        }

        fun updateViewMode() {
            val drilled = activeDrilledGroup
            if (drilled != null) {
                binding.btnBackToLanguages.isVisible = true
                binding.subtitlesTitle.text = "${drilled.flag}  ${drilled.displayName}"
                binding.searchContainer.isVisible = false
            } else {
                binding.btnBackToLanguages.isVisible = false
                binding.subtitlesTitle.setText(R.string.player_subtitles)
                binding.searchContainer.isVisible = allGroups.size > 5
            }
            adapter.submitList(buildRows())
            if (isLandscape) {
                binding.cardRoot.post {
                    VortexBlurHelper.applyCardBackdropBlur(context, binding.cardRoot)
                }
            }
        }

        adapter = UnifiedSubtitleAdapter(buildRows()) { row ->
            when (row) {
                is DisplayRow.Off -> {
                    dialog.dismiss()
                    onSelectSubtitle(null)
                }
                is DisplayRow.Language -> {
                    if (row.group.tracks.size == 1) {
                        dialog.dismiss()
                        onSelectSubtitle(row.group.tracks.first())
                    } else {
                        activeDrilledGroup = row.group
                        updateViewMode()
                    }
                }
                is DisplayRow.Track -> {
                    dialog.dismiss()
                    onSelectSubtitle(row.track)
                }
            }
        }

        binding.btnBackToLanguages.setOnClickListener {
            activeDrilledGroup = null
            updateViewMode()
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.isVisible = currentSearchQuery.isNotEmpty()
                if (activeDrilledGroup == null) {
                    adapter.submitList(buildRows())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.searchEditText.text?.clear()
        }

        // Handle Back button to return from drilled language view to languages list
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (activeDrilledGroup != null) {
                    activeDrilledGroup = null
                    updateViewMode()
                    return@setOnKeyListener true
                }
            }
            false
        }

        val scrollbar = VortexScrollbarDecoration(context)
        val itemHeightDp = 52f
        binding.subtitlesList.apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            maxHeightPx = ((if (isLandscape) 4.2f else 5.2f) * itemHeightDp * density).toInt()
            addItemDecoration(scrollbar)
            addOnItemTouchListener(scrollbar)
            this.adapter = adapter
            post {
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

    private class UnifiedSubtitleAdapter(
        private var rows: List<DisplayRow>,
        private val onPick: (DisplayRow) -> Unit,
    ) : RecyclerView.Adapter<UnifiedSubtitleAdapter.Holder>() {

        fun submitList(newRows: List<DisplayRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemChoiceRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemChoiceRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            ),
        )

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = rows[position]
            val b = holder.binding

            when (item) {
                is DisplayRow.Off -> {
                    b.rowRadio.isVisible = true
                    b.rowRadio.setImageResource(
                        if (item.isSelected) R.drawable.ic_vortex_radio_checked
                        else R.drawable.ic_vortex_radio_unchecked,
                    )
                    b.rowLabel.text = holder.itemView.context.getString(R.string.player_sub_off)
                    b.rowLabel.setTypeface(null, if (item.isSelected) Typeface.BOLD else Typeface.NORMAL)
                    b.rowLabel.setTextColor(if (item.isSelected) Color.WHITE else Color.parseColor("#99FFFFFF"))
                    b.rowValue.isVisible = false
                    b.rowChevron.isVisible = false
                }

                is DisplayRow.Language -> {
                    val group = item.group
                    val hasMultiple = group.tracks.size > 1

                    b.rowRadio.isVisible = true
                    b.rowRadio.setImageResource(
                        if (item.isSelected) R.drawable.ic_vortex_radio_checked
                        else R.drawable.ic_vortex_radio_unchecked,
                    )
                    b.rowLabel.text = "${group.flag}  ${group.displayName}"
                    b.rowLabel.setTypeface(null, if (item.isSelected) Typeface.BOLD else Typeface.NORMAL)
                    b.rowLabel.setTextColor(if (item.isSelected) Color.WHITE else Color.parseColor("#E6FFFFFF"))

                    if (hasMultiple) {
                        b.rowValue.isVisible = true
                        b.rowValue.text = group.tracks.size.toString()
                        b.rowChevron.isVisible = true
                    } else {
                        b.rowValue.isVisible = false
                        b.rowChevron.isVisible = false
                    }
                }

                is DisplayRow.Track -> {
                    val track = item.track
                    b.rowRadio.isVisible = true
                    b.rowRadio.setImageResource(
                        if (item.isSelected) R.drawable.ic_vortex_radio_checked
                        else R.drawable.ic_vortex_radio_unchecked,
                    )

                    val releaseName = track.release?.takeIf { it.isNotBlank() }
                        ?: track.label.takeIf { it.isNotBlank() }
                        ?: "Track ${item.index}"

                    val ccTag = if (track.isHearingImpaired && !releaseName.contains("CC", ignoreCase = true) && !releaseName.contains("HI", ignoreCase = true)) {
                        " [CC]"
                    } else {
                        ""
                    }

                    b.rowLabel.text = "$releaseName$ccTag"
                    b.rowLabel.setTypeface(null, if (item.isSelected) Typeface.BOLD else Typeface.NORMAL)
                    b.rowLabel.setTextColor(if (item.isSelected) Color.WHITE else Color.parseColor("#E6FFFFFF"))

                    val score = track.matchScore
                    if (score != null && score > 0) {
                        b.rowValue.isVisible = true
                        b.rowValue.text = "$score%"
                    } else {
                        b.rowValue.isVisible = false
                    }
                    b.rowChevron.isVisible = false
                }
            }

            b.root.setOnClickListener { onPick(item) }
        }
    }
}
