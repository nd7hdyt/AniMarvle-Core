package com.animarvle.app.ui.profile

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.animarvle.app.databinding.FragmentInterfaceBinding
import com.animarvle.app.ui.theme.AppTheme

class InterfaceFragment : Fragment() {

    private var _binding: FragmentInterfaceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentInterfaceBinding.inflate(inflater, container, false)
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

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        updateUi()

        binding.rowTheme.setOnClickListener {
            showThemePickerDialog()
        }

        binding.rowTitleLanguage.setOnClickListener {
            showTitleLanguageDialog()
        }

        binding.rowAppLanguage.setOnClickListener {
            showAppLanguageDialog()
        }
    }

    private fun updateUi() {
        val currentPalette = AppTheme.palette(requireContext())
        binding.themeCurrentLabel.text = AppTheme.nameFor(currentPalette)

        val accentColor = AppTheme.colorFor(currentPalette)
        val circleDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
        }
        binding.themeColorCircle.background = circleDrawable

        val titleLang = requireContext().getSharedPreferences(PREFS_INTERFACE, android.content.Context.MODE_PRIVATE)
            .getString(KEY_TITLE_LANG, "Romaji") ?: "Romaji"
        binding.titleLanguageLabel.text = titleLang

        val appLang = requireContext().getSharedPreferences(PREFS_INTERFACE, android.content.Context.MODE_PRIVATE)
            .getString(KEY_APP_LANG, "System (English)") ?: "System (English)"
        binding.appLanguageLabel.text = appLang
    }

    private fun showThemePickerDialog() {
        val currentPalette = AppTheme.palette(requireContext())
        val palettes = listOf(
            AppTheme.PALETTE_CYAN to "Zangetsu Cyan",
            AppTheme.PALETTE_RED to "AniLab Red",
            AppTheme.PALETTE_GREEN to "Th3-Anime Green",
        )

        val items = palettes.map { it.second }.toTypedArray()
        val checkedIndex = palettes.indexOfFirst { it.first == currentPalette }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Theme & colour")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val selected = palettes[which].first
                if (selected != currentPalette) {
                    AppTheme.setPalette(requireContext(), selected)
                    dialog.dismiss()
                    requireActivity().recreate()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTitleLanguageDialog() {
        val prefs = requireContext().getSharedPreferences(PREFS_INTERFACE, android.content.Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_TITLE_LANG, "Romaji") ?: "Romaji"
        val options = arrayOf("Romaji", "English", "Native")
        val checkedIndex = options.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Title language")
            .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                val selected = options[which]
                prefs.edit().putString(KEY_TITLE_LANG, selected).apply()
                binding.titleLanguageLabel.text = selected
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppLanguageDialog() {
        val prefs = requireContext().getSharedPreferences(PREFS_INTERFACE, android.content.Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_APP_LANG, "System (English)") ?: "System (English)"
        val options = arrayOf("System (English)", "English (US)", "Japanese (日本語)")
        val checkedIndex = options.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("App language")
            .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                val selected = options[which]
                prefs.edit().putString(KEY_APP_LANG, selected).apply()
                binding.appLanguageLabel.text = selected
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_INTERFACE = "animarvel_interface_prefs"
        const val KEY_TITLE_LANG = "key_title_language"
        const val KEY_APP_LANG = "key_app_language"
    }
}
