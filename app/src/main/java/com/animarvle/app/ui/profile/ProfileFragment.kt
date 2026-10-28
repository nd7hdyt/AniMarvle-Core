package com.animarvle.app.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import coil.load
import coil.transform.CircleCropTransformation
import androidx.activity.result.contract.ActivityResultContracts
import com.animarvle.app.BuildConfig
import com.animarvle.app.R
import com.animarvle.app.data.sync.SyncState
import com.animarvle.app.data.updater.AppUpdateRepository
import com.animarvle.app.databinding.FragmentProfileBinding
import com.animarvle.app.ui.updater.UpdateBottomSheet
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val googleDrivePermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        performDriveSync()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
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

        binding.toolbarTitle.setText(R.string.nav_profile)

        val accountRepo = (requireActivity().application as com.animarvle.app.AnimarvleApp).container.account

        binding.continueButton.setOnClickListener {
            findNavController().navigate(R.id.authLetYouInFragment)
        }

        binding.btnEditProfile.setOnClickListener {
            findNavController().navigate(R.id.editProfileFragment)
        }

        binding.btnLogOut.setOnClickListener {
            accountRepo.signOut(requireContext())
            android.widget.Toast.makeText(requireContext(), getString(R.string.auth_signed_out), android.widget.Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                accountRepo.accountFlow.collect { account ->
                    if (account != null && account.isLoggedIn) {
                        binding.accountCard.visibility = View.GONE
                        binding.loggedInLayout.visibility = View.VISIBLE
                        binding.btnLogOut.visibility = View.VISIBLE
                        binding.tvUserName.text = account.username
                        binding.tvUserEmail.text = account.email

                        val avatar = account.avatarUrl
                        if (!avatar.isNullOrBlank()) {
                            binding.userAvatarImage.visibility = View.VISIBLE
                            binding.userAvatarInitial.visibility = View.GONE
                            binding.userAvatarImage.load(avatar) {
                                crossfade(true)
                                transformations(CircleCropTransformation())
                            }
                        } else {
                            binding.userAvatarImage.visibility = View.GONE
                            binding.userAvatarInitial.visibility = View.VISIBLE
                            binding.userAvatarInitial.text = account.username.take(1).uppercase()
                        }

                        if (account.provider == "Google") {
                            binding.cardGoogleDriveSync.visibility = View.VISIBLE
                            updateSyncStatusText()
                        } else {
                            binding.cardGoogleDriveSync.visibility = View.GONE
                        }
                    } else {
                        binding.accountCard.visibility = View.VISIBLE
                        binding.loggedInLayout.visibility = View.GONE
                        binding.btnLogOut.visibility = View.GONE
                        binding.cardGoogleDriveSync.visibility = View.GONE
                    }
                }
            }
        }

        binding.btnSyncNow.setOnClickListener {
            performDriveSync()
        }

        val prefs = requireContext().getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Initialize Switches
        binding.switchAdultMode.isChecked = prefs.getBoolean(PlayerSettingsFragment.KEY_ADULT_MODE, false)
        binding.switchNotifications.isChecked = prefs.getBoolean(PlayerSettingsFragment.KEY_NOTIFICATIONS, true)
        binding.switchAnimeSkipButton.isChecked = prefs.getBoolean(PlayerSettingsFragment.KEY_SHOW_SKIP, true)

        binding.switchAdultMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PlayerSettingsFragment.KEY_ADULT_MODE, isChecked).apply()
        }
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PlayerSettingsFragment.KEY_NOTIFICATIONS, isChecked).apply()
            if (isChecked) {
                val missing = com.animarvle.app.ui.download.DownloadStorageHelper.getMissingPermissions(requireContext())
                if (missing.isNotEmpty()) {
                    requestPermissions(missing, com.animarvle.app.ui.download.DownloadStorageHelper.STORAGE_PERMISSION_REQUEST_CODE)
                }
            }
        }
        binding.switchAnimeSkipButton.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PlayerSettingsFragment.KEY_SHOW_SKIP, isChecked).apply()
        }

        // Check for updates on launch toggle
        binding.switchCheckUpdatesOnLaunch.isChecked = AppUpdateRepository.isCheckOnLaunchEnabled(requireContext())
        binding.switchCheckUpdatesOnLaunch.setOnCheckedChangeListener { _, isChecked ->
            AppUpdateRepository.setCheckOnLaunchEnabled(requireContext(), isChecked)
        }

        // Check for updates row
        binding.tvCurrentVersionBadge.text = "v${BuildConfig.VERSION_NAME} >"
        binding.cardCheckUpdates.setOnClickListener {
            binding.pbCheckingUpdates.visibility = View.VISIBLE
            binding.tvCurrentVersionBadge.visibility = View.GONE
            binding.tvCheckUpdatesSubtitle.text = "Checking for updates…"

            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppUpdateRepository.checkForUpdate(requireContext())
                if (_binding == null) return@launch

                binding.pbCheckingUpdates.visibility = View.GONE
                binding.tvCurrentVersionBadge.visibility = View.VISIBLE

                if (result.isSuccess) {
                    val info = result.getOrNull()
                    if (info != null && info.hasUpdate) {
                        binding.tvCheckUpdatesSubtitle.text = "Version ${info.versionName} available · tap to update"
                        UpdateBottomSheet.newInstance(info).show(childFragmentManager, UpdateBottomSheet.TAG)
                    } else {
                        binding.tvCheckUpdatesSubtitle.text = "You're on the latest version"
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Animarvle is up to date (v${BuildConfig.VERSION_NAME})",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    binding.tvCheckUpdatesSubtitle.text = "Tap to check for latest release"
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Failed to check for updates",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Player & Downloads Navigation
        binding.cardPlayerSettings.setOnClickListener {
            findNavController().navigate(R.id.playerSettingsFragment)
        }

        // 2. Subtitle Settings Navigation
        binding.cardSubtitleSettings.setOnClickListener {
            findNavController().navigate(R.id.subtitleSettingsFragment)
        }

        // 3. Double Tap to Skip Selector (5s, 10s, 15s, 20s, 30s, 45s, 60s, 90s)
        val skipOptions = listOf(5, 10, 15, 20, 30, 45, 60, 90)
        var savedSeconds = prefs.getInt(PlayerSettingsFragment.KEY_SEEK_DURATION, 10)

        fun updateSeekDisplay() {
            binding.tvDoubleTapValue.text = "${savedSeconds}s"
        }
        updateSeekDisplay()

        binding.cardDoubleTapSkip.setOnClickListener {
            val rows = skipOptions.map { sec ->
                com.animarvle.app.ui.player.ChoiceRow("${sec}s", sec == savedSeconds)
            }
            com.animarvle.app.ui.player.ChoiceDialog.show(
                context = requireContext(),
                title = getString(R.string.settings_seek_duration),
                rows = rows,
            ) { index ->
                savedSeconds = skipOptions[index]
                prefs.edit().putInt(PlayerSettingsFragment.KEY_SEEK_DURATION, savedSeconds).apply()
                updateSeekDisplay()
            }
        }

        // 4. Share App Intent
        binding.cardShareApp.setOnClickListener {
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Check out Animarvle")
                    putExtra(
                        android.content.Intent.EXTRA_TEXT,
                        "Hey! Check out this amazing anime streaming app: https://gitlab.com/nd7hdyt/Animarvle",
                    )
                }
                startActivity(android.content.Intent.createChooser(intent, "Share App"))
            }
        }

        // 5. About & Legal
        binding.cardAbout.setOnClickListener {
            findNavController().navigate(R.id.aboutFragment)
        }
    }

    private fun updateSyncStatusText() {
        if (_binding == null) return
        val driveSync = (requireActivity().application as com.animarvle.app.AnimarvleApp).container.driveSync
        val lastSync = driveSync.getLastSyncTime()
        val count = driveSync.getLastSyncCount()
        if (lastSync == 0L) {
            binding.tvSyncStatus.text = "Not synced yet"
        } else {
            val timeAgo = android.text.format.DateUtils.getRelativeTimeSpanString(
                lastSync,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS
            )
            binding.tvSyncStatus.text = "Last synced: $timeAgo ($count items)"
        }
    }

    private fun performDriveSync() {
        val app = (requireActivity().application as com.animarvle.app.AnimarvleApp)
        val driveSync = app.container.driveSync

        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "Syncing..."

        viewLifecycleOwner.lifecycleScope.launch {
            val result = driveSync.sync()
            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = "Sync"

            result.onSuccess { count ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Synced $count items with Google Drive!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                updateSyncStatusText()
            }.onFailure { err ->
                when {
                    err is com.google.android.gms.auth.UserRecoverableAuthException -> {
                        googleDrivePermLauncher.launch(err.intent)
                    }
                    err?.message?.contains("Google Drive API is disabled", ignoreCase = true) == true -> {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                            .setTitle("Enable Google Drive API")
                            .setMessage("Google Drive sync requires the Google Drive API to be enabled in your Google Cloud Console project (547903414124).\n\nTap below to open the console and click 'Enable'.")
                            .setPositiveButton("Open Console") { _, _ ->
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://console.cloud.google.com/apis/library/drive.googleapis.com?project=547903414124")
                                )
                                startActivity(intent)
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                    err?.message?.startsWith("DRIVE_ACCESS_DENIED") == true -> {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                            .setTitle("Google Sign-In Required")
                            .setMessage(
                                "Google Drive sync is unavailable:\n\n" +
                                "Animarvle is in Testing mode — only approved accounts can sync.\n\n" +
                                "Fix options:\n" +
                                "1. Ask the developer to add your email as a test user in Google Cloud Console.\n" +
                                "2. Sign out and sign in again — Google will now ask for Drive access during sign-in."
                            )
                            .setPositiveButton("Sign Out & Retry") { _, _ ->
                                val accountRepo = (requireActivity().application as com.animarvle.app.AnimarvleApp).container.account
                                accountRepo.signOut(requireContext())
                                findNavController().navigate(R.id.loginFragment)
                            }
                            .setNegativeButton("Dismiss", null)
                            .show()
                    }
                    else -> {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Sync failed: ${err?.localizedMessage ?: err?.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            val prefs = requireContext().getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            binding.switchAdultMode.isChecked = prefs.getBoolean(PlayerSettingsFragment.KEY_ADULT_MODE, false)
            binding.switchNotifications.isChecked = prefs.getBoolean(PlayerSettingsFragment.KEY_NOTIFICATIONS, true)
            binding.switchAnimeSkipButton.isChecked = prefs.getBoolean(PlayerSettingsFragment.KEY_SHOW_SKIP, true)
            binding.switchCheckUpdatesOnLaunch.isChecked = AppUpdateRepository.isCheckOnLaunchEnabled(requireContext())
            binding.tvCurrentVersionBadge.text = "v${BuildConfig.VERSION_NAME} >"
            val savedSeconds = prefs.getInt(PlayerSettingsFragment.KEY_SEEK_DURATION, 10)
            binding.tvDoubleTapValue.text = "${savedSeconds}s"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_NAME = PlayerSettingsFragment.PREFS_NAME
        const val KEY_ADULT_MODE = PlayerSettingsFragment.KEY_ADULT_MODE
        const val KEY_NOTIFICATIONS = PlayerSettingsFragment.KEY_NOTIFICATIONS
        const val KEY_SEEK_DURATION = PlayerSettingsFragment.KEY_SEEK_DURATION
        const val KEY_ANIME_SKIP_BUTTON = PlayerSettingsFragment.KEY_SHOW_SKIP
    }
}
