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
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        }.getOrDefault("v0.1.0 (build 1)")
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

        // Legal Documents
        binding.rowPrivacyPolicy.setOnClickListener {
            openLegalDocument(
                title = getString(R.string.about_privacy_policy),
                content = """
                    How information is handled when using the app and services:

                    • Information on Device: Downloaded files, watch history, My List, search history, settings, and playback preferences are stored locally on your device.
                    • Account & Profile: For optional sign-in, profile details, avatar, and watch progress are processed to sync across devices.
                    • Cloud Storage (Google Drive AppData): If you choose to enable cloud synchronization, your My List watchlist is saved to a private, hidden appDataFolder in your personal Google Drive account. We do not access, view, or store any of your other Google Drive files.
                    • Diagnostics: Anonymous crash reports and error logs are used solely to fix bugs and measure reliability.
                    • Third-Party Media: Media streams are linked from independent third-party services. Your device connects directly with those providers.
                    • No Data Selling: We do not sell personal data or use it for targeted advertising.
                """.trimIndent()
            )
        }

        binding.rowTermsOfService.setOnClickListener {
            openLegalDocument(
                title = getString(R.string.about_terms_of_service),
                content = """
                    The rules that apply when you use the app:

                    • Media Discovery & Aggregation: This app is a media discovery and aggregation service. We do NOT host third-party video files on our servers.
                    • Independent Providers: Third-party streaming sources are independently operated. We do not control or endorse their content or availability.
                    • Your Responsibilities: You must be at least 18 years old. The app is for personal, non-commercial use. You are responsible for ensuring your use complies with local laws.
                    • Service 'As Is': The service is provided on an "as is" and "as available" basis without warranties.
                """.trimIndent()
            )
        }

        binding.rowCopyrightTakedown.setOnClickListener {
            openLegalDocument(
                title = getString(R.string.about_copyright_takedown),
                content = """
                    How to report a copyright concern:

                    • What We Can Remove: We do not store third-party video files on our servers. We can disable the identified source, link, or access path within the app, stopping access through the app.
                    • Original File Deletion: Disabling a source in the app does not delete the file from the third-party service that stores or serves it. To delete the original file, contact that service or its host.
                    • Submitting a Notice: To report an infringing access path, provide the copyrighted work, exact title/season/episode, infringement explanation, and authorization.
                    • Prompt Assessment: Valid complaints are assessed promptly to permanently disable the identified access path.
                """.trimIndent()
            )
        }

        // Diagnostics
        binding.rowDiagnostics.setOnClickListener {
            copyDiagnostics(version)
        }
    }

    private fun openLegalDocument(title: String, content: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
