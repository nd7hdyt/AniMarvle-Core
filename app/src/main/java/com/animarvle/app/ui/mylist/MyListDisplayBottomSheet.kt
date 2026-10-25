package com.animarvle.app.ui.mylist

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetMyListDisplayBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MyListDisplayBottomSheet(
    private val onConfigChanged: (DisplayMode, Int) -> Unit,
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMyListDisplayBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: MyListDisplayPreferences
    private var currentMode = DisplayMode.COMFORTABLE_GRID
    private var currentItemsPerRow = 2

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetMyListDisplayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = MyListDisplayPreferences(requireContext())
        currentMode = preferences.displayMode
        currentItemsPerRow = preferences.itemsPerRow

        updateButtonsUi()
        updateSliderUi()

        binding.btnCompactGrid.setOnClickListener {
            setMode(DisplayMode.COMPACT_GRID)
        }
        binding.btnComfortableGrid.setOnClickListener {
            setMode(DisplayMode.COMFORTABLE_GRID)
        }
        binding.btnCoverOnlyGrid.setOnClickListener {
            setMode(DisplayMode.COVER_ONLY_GRID)
        }
        binding.btnList.setOnClickListener {
            setMode(DisplayMode.LIST)
        }

        binding.itemsPerRowSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentItemsPerRow = value.toInt()
                binding.itemsPerRowValue.text = "$currentItemsPerRow"
                preferences.itemsPerRow = currentItemsPerRow
                onConfigChanged(currentMode, currentItemsPerRow)
            }
        }
    }

    private fun setMode(mode: DisplayMode) {
        currentMode = mode
        preferences.displayMode = mode
        updateButtonsUi()
        updateSliderUi()
        onConfigChanged(currentMode, currentItemsPerRow)
    }

    private fun updateButtonsUi() {
        val buttons = mapOf(
            binding.btnCompactGrid to DisplayMode.COMPACT_GRID,
            binding.btnComfortableGrid to DisplayMode.COMFORTABLE_GRID,
            binding.btnCoverOnlyGrid to DisplayMode.COVER_ONLY_GRID,
            binding.btnList to DisplayMode.LIST,
        )

        buttons.forEach { (btn, mode) ->
            val isSelected = (mode == currentMode)
            btn.setBackgroundResource(
                if (isSelected) R.drawable.bg_display_mode_btn_selected
                else R.drawable.bg_display_mode_btn_unselected
            )
            btn.setTextColor(if (isSelected) Color.BLACK else Color.parseColor("#D0D3DE"))
            btn.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        binding.itemsPerRowSection.isVisible = (currentMode != DisplayMode.LIST)
    }

    private fun updateSliderUi() {
        binding.itemsPerRowValue.text = "$currentItemsPerRow"
        binding.itemsPerRowSlider.value = currentItemsPerRow.toFloat()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MyListDisplayBottomSheet"
    }
}
