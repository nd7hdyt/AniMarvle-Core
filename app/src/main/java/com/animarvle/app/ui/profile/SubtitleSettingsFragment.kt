package com.animarvle.app.ui.profile

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.animarvle.app.databinding.FragmentSubtitleSettingsBinding

class SubtitleSettingsFragment : Fragment() {

    private var _binding: FragmentSubtitleSettingsBinding? = null
    private val binding get() = _binding!!

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

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        val savedLang = prefs.getString(KEY_SUB_LANG, "English") ?: "English"
        binding.editSubLang.setText(savedLang)
        binding.editSubLang.addTextChangedListener { text ->
            prefs.edit().putString(KEY_SUB_LANG, text.toString().trim()).apply()
        }

        val savedSize = prefs.getInt(KEY_TEXT_SIZE, 15)
        binding.seekTextSize.progress = savedSize
        binding.previewSubtitleText.textSize = savedSize.toFloat()

        binding.seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = maxOf(10, progress)
                binding.previewSubtitleText.textSize = size.toFloat()
                if (fromUser) {
                    prefs.edit().putInt(KEY_TEXT_SIZE, size).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        var styleMode = prefs.getInt(KEY_TEXT_STYLE, STYLE_NORMAL)
        fun applyStyle(mode: Int) {
            styleMode = mode
            when (mode) {
                STYLE_BOLD -> binding.previewSubtitleText.typeface = Typeface.DEFAULT_BOLD
                STYLE_ITALIC -> binding.previewSubtitleText.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
                else -> binding.previewSubtitleText.typeface = Typeface.DEFAULT
            }
            prefs.edit().putInt(KEY_TEXT_STYLE, mode).apply()
        }
        applyStyle(styleMode)

        binding.btnStyleNormal.setOnClickListener { applyStyle(STYLE_NORMAL) }
        binding.btnStyleBold.setOnClickListener { applyStyle(STYLE_BOLD) }
        binding.btnStyleItalic.setOnClickListener { applyStyle(STYLE_ITALIC) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_NAME = "subtitle_settings"
        const val KEY_SUB_LANG = "default_language"
        const val KEY_TEXT_SIZE = "text_size"
        const val KEY_TEXT_STYLE = "text_style"

        const val STYLE_NORMAL = 0
        const val STYLE_BOLD = 1
        const val STYLE_ITALIC = 2
    }
}
