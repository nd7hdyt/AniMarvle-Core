package com.animarvle.app.ui.updater

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.animarvle.app.BuildConfig
import com.animarvle.app.R
import com.animarvle.app.data.updater.AppUpdateInfo
import com.animarvle.app.data.updater.AppUpdateRepository
import com.animarvle.app.databinding.BottomSheetUpdateBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import okhttp3.Call
import java.io.File

class UpdateBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetUpdateBinding? = null
    private val binding get() = _binding!!

    private var updateInfo: AppUpdateInfo? = null
    private var downloadCall: Call? = null
    private var downloadedApkFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        updateInfo = arguments?.getSerializable(ARG_UPDATE_INFO) as? AppUpdateInfo
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Make background transparent for rounded corners
        dialog?.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.setBackgroundResource(android.R.color.transparent)
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }

        val info = updateInfo ?: run {
            dismiss()
            return
        }

        val rawVersion = info.versionName.removePrefix("v").removePrefix("Version ").trim()
        binding.tvNewVersion.text = "Version $rawVersion"
        binding.tvCurrentVersion.text = "You have v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})."

        val notes = info.releaseNotes.trim()
        binding.tvReleaseNotes.text = if (notes.isNotBlank()) notes else "• Performance optimizations & general improvements"

        binding.btnSkip.setOnClickListener {
            if (info.versionCode > 0) {
                AppUpdateRepository.skipVersion(requireContext(), info.versionCode)
            }
            dismiss()
        }

        binding.btnLater.setOnClickListener {
            downloadCall?.cancel()
            dismiss()
        }

        binding.btnUpdate.setOnClickListener {
            val apk = downloadedApkFile
            if (apk != null && apk.exists()) {
                // If already downloaded, launch install directly
                AppUpdateRepository.installApk(requireContext(), apk)
                return@setOnClickListener
            }

            if (info.apkUrl.isNotBlank()) {
                startDownload(info)
            } else {
                openBrowser(info.githubUrl)
                dismiss()
            }
        }
    }

    private fun startDownload(info: AppUpdateInfo) {
        binding.llDownloadProgress.visibility = View.VISIBLE
        binding.scrollReleaseNotes.visibility = View.GONE
        binding.tvWhatsNewHeader.visibility = View.GONE
        binding.btnSkip.visibility = View.GONE
        binding.btnUpdate.visibility = View.GONE
        binding.btnLater.text = "Cancel"

        downloadCall = AppUpdateRepository.downloadApk(
            context = requireContext(),
            apkUrl = info.apkUrl,
            versionName = info.versionName,
            onProgress = { percent, _, _ ->
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.pbDownload.progress = percent
                        binding.tvDownloadPercent.text = "Downloading the latest version: $percent%"
                    }
                }
            },
            onComplete = { file ->
                downloadedApkFile = file
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.tvDownloadTitle.text = "Download Complete!"
                        binding.tvDownloadPercent.text = "Ready to install"
                        binding.pbDownload.progress = 100
                        binding.btnUpdate.visibility = View.VISIBLE
                        binding.btnUpdate.text = "Install Now"
                        binding.btnLater.text = "Close"
                        AppUpdateRepository.installApk(requireContext(), file)
                    }
                }
            },
            onError = { throwable ->
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.tvDownloadTitle.text = "Download failed"
                        binding.tvDownloadPercent.text = throwable.localizedMessage ?: "Network error"
                        binding.btnUpdate.isEnabled = true
                        binding.btnUpdate.text = "Open Browser"
                        binding.btnUpdate.setOnClickListener {
                            openBrowser(info.githubUrl)
                            dismiss()
                        }
                        Toast.makeText(requireContext(), "Download failed. Redirecting to release page.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun openBrowser(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadCall?.cancel()
        _binding = null
    }

    companion object {
        const val TAG = "UpdateBottomSheet"
        private const val ARG_UPDATE_INFO = "arg_update_info"

        fun newInstance(info: AppUpdateInfo): UpdateBottomSheet {
            return UpdateBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_UPDATE_INFO, info)
                }
            }
        }
    }
}
