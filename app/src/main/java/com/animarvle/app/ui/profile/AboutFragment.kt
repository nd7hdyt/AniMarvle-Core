package com.animarvle.app.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentAboutBinding
import com.animarvle.app.ui.theme.AppTheme

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Status bar inset protection
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        binding.buttonBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Dynamic theme accent for the dot
        val accent = AppTheme.accent(requireContext())
        binding.aboutDot.setTextColor(accent)

        val version = runCatching {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            "v${pInfo.versionName} (build ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode})"
        }.getOrDefault("v1.0.0 (build 1)")
        binding.aboutVersionText.text = version

        // Community & Support Links
        binding.rowTelegram.setOnClickListener {
            openUrl("https://t.me/animarvle")
        }

        binding.rowDiscord.setOnClickListener {
            openUrl("https://discord.gg/hXgebEStFA")
        }

        binding.rowGithub.setOnClickListener {
            openUrl("https://github.com/nd7hdyt/AniMarvle")
        }

        binding.rowGuide.setOnClickListener {
            openUrl("https://wotaku.wiki/ext/")
        }

        binding.rowDataSource.setOnClickListener {
            openUrl("https://anilist.co")
        }

        // Diagnostics
        binding.rowDiagnostics.setOnClickListener {
            copyDiagnostics(version)
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }.onFailure {
            Toast.makeText(requireContext(), getString(R.string.about_link_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyDiagnostics(version: String) {
        val diagnostics = buildString {
            appendLine("animarvle $version")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Build Brand: ${Build.BRAND} / ${Build.DEVICE}")
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Animarvle Diagnostics", diagnostics))
        Toast.makeText(requireContext(), getString(R.string.about_copy_diagnostics_success), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
