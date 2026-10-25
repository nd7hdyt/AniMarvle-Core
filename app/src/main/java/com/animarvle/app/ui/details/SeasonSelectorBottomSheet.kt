package com.animarvle.app.ui.details

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SeasonSelectorBottomSheet(
    private val seasons: List<String> = emptyList(),
    private val selectedIndex: Int = 0,
    private val onSeasonSelected: (Int) -> Unit = {}
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#121216"))
                cornerRadii = floatArrayOf(
                    20f * resources.displayMetrics.density, 20f * resources.displayMetrics.density,
                    20f * resources.displayMetrics.density, 20f * resources.displayMetrics.density,
                    0f, 0f, 0f, 0f
                )
            }
            background = shape
            setPadding(0, 0, 0, (24 * resources.displayMetrics.density).toInt())
        }

        // Drag handle bar
        val handle = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                (44 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (12 * resources.displayMetrics.density).toInt()
                bottomMargin = (14 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(Color.parseColor("#4DFFFFFF"))
        }
        root.addView(handle)

        val scrollView = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * resources.displayMetrics.density).toInt()
            )
            isVerticalScrollBarEnabled = false
        }

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        seasons.forEachIndexed { index, seasonTitle ->
            val isSelected = (index == selectedIndex)
            val row = TextView(requireContext()).apply {
                text = seasonTitle
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(
                    if (isSelected) Color.parseColor("#00E5B8") else Color.parseColor("#E6FFFFFF")
                )
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (52 * resources.displayMetrics.density).toInt()
                )
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)

                setOnClickListener {
                    onSeasonSelected(index)
                    dismiss()
                }
            }
            listContainer.addView(row)
        }

        scrollView.addView(listContainer)
        root.addView(scrollView)
        return root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.background = null
    }

    companion object {
        fun show(
            fragmentManager: FragmentManager,
            seasons: List<String>,
            selectedIndex: Int,
            onSeasonSelected: (Int) -> Unit
        ) {
            val sheet = SeasonSelectorBottomSheet(seasons, selectedIndex, onSeasonSelected)
            sheet.show(fragmentManager, "SeasonSelectorBottomSheet")
        }
    }
}
