package com.animarvle.app.ui.profile

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentPlayerSettingsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerSettingsFragment : Fragment() {

    private var _binding: FragmentPlayerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerSettingsBinding.inflate(inflater, container, false)
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

        // Initialize switches with saved values (or defaults)
        binding.switchAutoplay.isChecked = prefs.getBoolean(KEY_AUTOPLAY, true)
        binding.switchAutoSkip.isChecked = prefs.getBoolean(KEY_AUTO_SKIP, false)
        binding.switchShowSkip.isChecked = prefs.getBoolean(KEY_SHOW_SKIP, true)
        binding.switchHaptic.isChecked = prefs.getBoolean(KEY_HAPTIC, true)
        binding.switchBackgroundPlay.isChecked = prefs.getBoolean(KEY_BACKGROUND_PLAY, false)
        binding.switchPip.isChecked = prefs.getBoolean(KEY_PIP, true)
        binding.switchWifiOnly.isChecked = prefs.getBoolean(KEY_WIFI_ONLY, true)

        binding.switchAutoplay.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTOPLAY, isChecked).apply()
        }
        binding.switchAutoSkip.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_SKIP, isChecked).apply()
        }
        binding.switchShowSkip.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_SKIP, isChecked).apply()
        }
        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HAPTIC, isChecked).apply()
        }
        binding.switchBackgroundPlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_BACKGROUND_PLAY, isChecked).apply()
        }
        binding.switchPip.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_PIP, isChecked).apply()
        }
        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_WIFI_ONLY, isChecked).apply()
            val app = requireActivity().application as? com.animarvle.app.AnimarvleApp
            app?.container?.updateDownloadRequirements(isChecked)
        }

        // Audio preference (SUB vs DUB)
        var isDub = prefs.getBoolean(KEY_DEFAULT_DUB, false)
        val activeBg = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        val inactiveBg = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2C2C2E"))
        val activeTextColor = android.graphics.Color.BLACK
        val inactiveTextColor = android.graphics.Color.WHITE

        fun updateAudioButtons() {
            if (isDub) {
                binding.btnAudioDub.backgroundTintList = activeBg
                binding.btnAudioDub.setTextColor(activeTextColor)
                binding.btnAudioSub.backgroundTintList = inactiveBg
                binding.btnAudioSub.setTextColor(inactiveTextColor)
            } else {
                binding.btnAudioSub.backgroundTintList = activeBg
                binding.btnAudioSub.setTextColor(activeTextColor)
                binding.btnAudioDub.backgroundTintList = inactiveBg
                binding.btnAudioDub.setTextColor(inactiveTextColor)
            }
        }
        updateAudioButtons()

        binding.btnAudioSub.setOnClickListener {
            if (isDub) {
                isDub = false
                prefs.edit().putBoolean(KEY_DEFAULT_DUB, false).apply()
                updateAudioButtons()
            }
        }
        binding.btnAudioDub.setOnClickListener {
            if (!isDub) {
                isDub = true
                prefs.edit().putBoolean(KEY_DEFAULT_DUB, true).apply()
                updateAudioButtons()
            }
        }

        // Default Quality chooser (Auto, 4K, 1080p, 720p, 480p)
        val qualities = listOf(
            "auto" to getString(R.string.settings_default_quality_auto),
            "4k" to getString(R.string.settings_default_quality_4k),
            "1080p" to getString(R.string.settings_default_quality_1080p),
            "720p" to getString(R.string.settings_default_quality_720p),
            "480p" to getString(R.string.settings_default_quality_480p),
        )
        var savedQuality = prefs.getString(KEY_DEFAULT_QUALITY, "auto") ?: "auto"
        fun updateQualityDisplay() {
            val label = qualities.firstOrNull { it.first == savedQuality }?.second
                ?: getString(R.string.settings_default_quality_auto)
            binding.tvDefaultQualityValue.text = label
        }
        updateQualityDisplay()

        binding.cardDefaultQuality.setOnClickListener {
            val rows = qualities.map {
                com.animarvle.app.ui.player.ChoiceRow(it.second, it.first == savedQuality)
            }
            com.animarvle.app.ui.player.ChoiceDialog.show(
                requireContext(),
                getString(R.string.settings_default_quality),
                rows,
            ) { index ->
                savedQuality = qualities[index].first
                prefs.edit().putString(KEY_DEFAULT_QUALITY, savedQuality).apply()
                updateQualityDisplay()
            }
        }

        // Double-Tap Seek Duration chooser (5s, 10s, 15s, 20s, 30s, 45s, 60s, 90s)
        val seekDurations = listOf(
            5 to "5s",
            10 to "10s",
            15 to "15s",
            20 to "20s",
            30 to "30s",
            45 to "45s",
            60 to "60s",
            90 to "90s",
        )
        var savedSeekSeconds = prefs.getInt(KEY_SEEK_DURATION, 10)
        fun updateSeekDisplay() {
            val label = seekDurations.firstOrNull { it.first == savedSeekSeconds }?.second
                ?: getString(R.string.settings_seek_10s)
            binding.tvSeekDurationValue.text = label
        }
        updateSeekDisplay()

        binding.cardSeekDuration.setOnClickListener {
            val rows = seekDurations.map {
                com.animarvle.app.ui.player.ChoiceRow(it.second, it.first == savedSeekSeconds)
            }
            com.animarvle.app.ui.player.ChoiceDialog.show(
                requireContext(),
                getString(R.string.settings_seek_duration),
                rows,
            ) { index ->
                savedSeekSeconds = seekDurations[index].first
                prefs.edit().putInt(KEY_SEEK_DURATION, savedSeekSeconds).apply()
                updateSeekDisplay()
            }
        }

        // Download Folder Location (SAF)
        fun updateDownloadFolderDisplay() {
            val uriStr = prefs.getString(KEY_DOWNLOAD_DIR_URI, null)
            if (uriStr.isNullOrBlank()) {
                binding.tvDownloadFolderDesc.setText(R.string.settings_download_folder_internal)
            } else {
                val decoded = android.net.Uri.decode(uriStr)
                binding.tvDownloadFolderDesc.text = decoded
            }
        }
        updateDownloadFolderDisplay()

        val pickFolderLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                // Persist permissions across reboots
                runCatching {
                    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                }
                prefs.edit().putString(KEY_DOWNLOAD_DIR_URI, uri.toString()).apply()
                updateDownloadFolderDisplay()
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.settings_download_folder_changed,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

        binding.cardDownloadFolder.setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        // Concurrent Downloads chooser (1, 2, 3)
        val concurrentOptions = listOf(1, 2, 3)
        var savedConcurrent = prefs.getInt(KEY_CONCURRENT_DOWNLOADS, 1)
        fun updateConcurrentDisplay() {
            binding.tvConcurrentDownloadsValue.text = savedConcurrent.toString()
        }
        updateConcurrentDisplay()

        binding.cardConcurrentDownloads.setOnClickListener {
            val rows = concurrentOptions.map {
                com.animarvle.app.ui.player.ChoiceRow(
                    "$it " + if (it == 1) "download" else "downloads",
                    it == savedConcurrent,
                )
            }
            com.animarvle.app.ui.player.ChoiceDialog.show(
                requireContext(),
                getString(R.string.settings_concurrent_downloads),
                rows,
            ) { index ->
                savedConcurrent = concurrentOptions[index]
                prefs.edit().putInt(KEY_CONCURRENT_DOWNLOADS, savedConcurrent).apply()
                updateConcurrentDisplay()
            }
        }

        // Cloudflare Worker URL
        fun updateWorkerUrlDisplay() {
            val savedUrl = prefs.getString(KEY_WORKER_URL, null)
                ?: com.animarvle.app.data.source.builtin.AnimarvleStreamSource.DEFAULT_WORKER_URL
            binding.tvWorkerUrlDesc.text = savedUrl
        }
        updateWorkerUrlDisplay()

        binding.cardWorkerUrl.setOnClickListener {
            val currentUrl = prefs.getString(KEY_WORKER_URL, null)
                ?: com.animarvle.app.data.source.builtin.AnimarvleStreamSource.DEFAULT_WORKER_URL

            val input = android.widget.EditText(requireContext()).apply {
                setText(currentUrl)
                setSingleLine()
                hint = getString(R.string.settings_worker_url_hint)
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_worker_url)
                .setMessage(R.string.settings_worker_url_desc)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newUrl = input.text.toString().trim()
                    if (newUrl.isNotBlank()) {
                        prefs.edit().putString(KEY_WORKER_URL, newUrl).apply()
                        updateWorkerUrlDisplay()

                        // Test connection in background
                        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val ok = runCatching {
                                val req = okhttp3.Request.Builder()
                                    .url("$newUrl/health")
                                    .build()
                                val client = okhttp3.OkHttpClient.Builder()
                                    .callTimeout(java.time.Duration.ofSeconds(3))
                                    .build()
                                client.newCall(req).execute().use { it.isSuccessful }
                            }.getOrDefault(false)

                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (ok) {
                                    binding.tvWorkerUrlStatus.text = "ONLINE"
                                    binding.tvWorkerUrlStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                                    android.widget.Toast.makeText(
                                        requireContext(),
                                        R.string.settings_worker_connected,
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    binding.tvWorkerUrlStatus.text = "ACTIVE"
                                    binding.tvWorkerUrlStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                                }
                            }
                        }
                    }
                }
                .setNeutralButton("Reset") { _, _ ->
                    prefs.edit().remove(KEY_WORKER_URL).apply()
                    updateWorkerUrlDisplay()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Clear Watch History
        binding.cardClearHistory.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_clear_history_confirm_title)
                .setMessage(R.string.settings_clear_history_confirm_message)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    val app = requireActivity().application as com.animarvle.app.AnimarvleApp
                    viewLifecycleOwner.lifecycleScope.launch {
                        val previousHistory = app.container.history.historyFlow.value
                        app.container.history.clearAll()
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root,
                            R.string.settings_clear_history_done,
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
                        ).setAction(R.string.action_undo) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                app.container.history.restoreHistory(previousHistory)
                                android.widget.Toast.makeText(
                                    requireContext(),
                                    R.string.settings_clear_history_restored,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }.show()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_NAME = "player_settings"
        const val KEY_ADULT_MODE = "adult_mode_enabled"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_AUTOPLAY = "autoplay_next"
        const val KEY_AUTO_SKIP = "auto_skip_intro"
        const val KEY_SHOW_SKIP = "show_skip_buttons"
        const val KEY_DEFAULT_DUB = "default_dub"
        const val KEY_DEFAULT_QUALITY = "default_quality"
        const val KEY_SEEK_DURATION = "seek_duration_seconds"
        const val KEY_DOWNLOAD_DIR_URI = "download_dir_uri"
        const val KEY_HAPTIC = "haptic_feedback"
        const val KEY_PIP = "pip_enabled"
        const val KEY_BACKGROUND_PLAY = "background_play"
        const val KEY_WIFI_ONLY = "wifi_only_downloads"
        const val KEY_CONCURRENT_DOWNLOADS = "concurrent_downloads_limit"
        const val KEY_WORKER_URL = com.animarvle.app.data.source.builtin.AnimarvleStreamSource.KEY_WORKER_URL
    }
}
