package com.animarvle.app.ui.profile

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.animarvle.app.databinding.FragmentSubtitleSettingsBinding
import java.util.Locale

class SubtitleSettingsFragment : Fragment() {

    private var _binding: FragmentSubtitleSettingsBinding? = null
    private val binding get() = _binding!!

    private var currentSize: Int = 16
    private var currentColor: Int = COLOR_WHITE
    private var currentOutlineEnabled: Boolean = true
    private var currentOutlineThickness: Float = 2.0f
    private var currentBottomPadding: Int = 20

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSubtitleSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentSize = prefs.getInt(KEY_TEXT_SIZE, 16).coerceIn(12, 30)
        currentColor = prefs.getInt(KEY_TEXT_COLOR, COLOR_WHITE)
        currentOutlineEnabled = prefs.getBoolean(KEY_OUTLINE_ENABLED, true)
        currentOutlineThickness = prefs.getFloat(KEY_OUTLINE_THICKNESS, 2.0f).coerceIn(0.5f, 5.0f)
        currentBottomPadding = prefs.getInt(KEY_BOTTOM_PADDING, 20).coerceIn(0, 60)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Initialize UI controls
        binding.sliderSize.value = currentSize.toFloat()
        binding.tvSizeValue.text = currentSize.toString()

        binding.switchTextOutline.isChecked = currentOutlineEnabled

        binding.sliderOutlineThickness.value = currentOutlineThickness
        binding.tvOutlineThicknessValue.text = String.format(Locale.US, "%.1f", currentOutlineThickness)
        binding.sliderOutlineThickness.isEnabled = currentOutlineEnabled
        binding.sliderOutlineThickness.alpha = if (currentOutlineEnabled) 1f else 0.4f

        binding.sliderBottomPadding.value = currentBottomPadding.toFloat()
        binding.tvBottomPaddingValue.text = currentBottomPadding.toString()

        setupColorSwatches()
        updatePreview()

        // Live Slider & Switch Listeners
        binding.sliderSize.addOnChangeListener { _, value, _ ->
            currentSize = value.toInt()
            binding.tvSizeValue.text = currentSize.toString()
            updatePreview()
        }

        binding.switchTextOutline.setOnCheckedChangeListener { _, isChecked ->
            currentOutlineEnabled = isChecked
            binding.sliderOutlineThickness.isEnabled = isChecked
            binding.sliderOutlineThickness.alpha = if (isChecked) 1f else 0.4f
            updatePreview()
        }

        binding.sliderOutlineThickness.addOnChangeListener { _, value, _ ->
            currentOutlineThickness = value
            binding.tvOutlineThicknessValue.text = String.format(Locale.US, "%.1f", currentOutlineThickness)
            updatePreview()
        }

        binding.sliderBottomPadding.addOnChangeListener { _, value, _ ->
            currentBottomPadding = value.toInt()
            binding.tvBottomPaddingValue.text = currentBottomPadding.toString()
            updatePreview()
        }

        // Save on "Apply settings" click
        binding.btnApplySettings.setOnClickListener {
            prefs.edit()
                .putInt(KEY_TEXT_SIZE, currentSize)
                .putInt(KEY_TEXT_COLOR, currentColor)
                .putBoolean(KEY_OUTLINE_ENABLED, currentOutlineEnabled)
                .putFloat(KEY_OUTLINE_THICKNESS, currentOutlineThickness)
                .putInt(KEY_BOTTOM_PADDING, currentBottomPadding)
                .apply()

            Toast.makeText(requireContext(), "Subtitle settings applied", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    private fun setupColorSwatches() {
        val swatches = listOf(
            binding.swatchWhite to COLOR_WHITE,
            binding.swatchYellow to COLOR_YELLOW,
            binding.swatchGreen to COLOR_GREEN,
            binding.swatchCyan to COLOR_CYAN,
            binding.swatchPink to COLOR_PINK,
        )

        fun renderSwatches() {
            val density = resources.displayMetrics.density
            swatches.forEach { (view, colorInt) ->
                val isSelected = (colorInt == currentColor)
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    if (isSelected) {
                        val strokeColor = if (colorInt == COLOR_WHITE) Color.parseColor("#555555") else Color.WHITE
                        setStroke((2.5f * density).toInt(), strokeColor)
                    }
                }
                view.scaleX = if (isSelected) 1.12f else 1.0f
                view.scaleY = if (isSelected) 1.12f else 1.0f
            }
        }

        swatches.forEach { (view, colorInt) ->
            view.setOnClickListener {
                currentColor = colorInt
                renderSwatches()
                updatePreview()
            }
        }

        renderSwatches()
    }

    private fun updatePreview() {
        val density = resources.displayMetrics.density
        binding.previewSubtitleText.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.toFloat())
            setTextColor(currentColor)
            outlineEnabled = currentOutlineEnabled
            outlineThicknessDp = currentOutlineThickness
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (currentBottomPadding * density).toInt()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_NAME = "subtitle_settings"
        const val KEY_TEXT_SIZE = "subtitle_text_size"
        const val KEY_TEXT_STYLE = "subtitle_text_style"
        const val KEY_TEXT_COLOR = "subtitle_text_color"
        const val KEY_OUTLINE_ENABLED = "subtitle_outline_enabled"
        const val KEY_OUTLINE_THICKNESS = "subtitle_outline_thickness"
        const val KEY_BOTTOM_PADDING = "subtitle_bottom_padding"

        val COLOR_WHITE = Color.parseColor("#FFFFFF")
        val COLOR_YELLOW = Color.parseColor("#FFD54F")
        val COLOR_GREEN = Color.parseColor("#69F0AE")
        val COLOR_CYAN = Color.parseColor("#18FFFF")
        val COLOR_PINK = Color.parseColor("#FF4081")

        const val STYLE_NORMAL = 0
        const val STYLE_BOLD = 1
        const val STYLE_ITALIC = 2
    }
}
