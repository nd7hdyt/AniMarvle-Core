package com.animarvle.app.ui.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.animarvle.app.R
import com.animarvle.app.databinding.DialogExtensionTestingBinding
import com.animarvle.app.extensions.ExtensionPackage
import com.animarvle.app.extensions.ExtensionProbe
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class ExtensionTestingDetailSheet : BottomSheetDialogFragment() {

    private var _binding: DialogExtensionTestingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExtensionTestingViewModel by activityViewModels()

    private var currentPkg: ExtensionPackage? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogExtensionTestingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pkgName = arguments?.getString(ARG_PACKAGE_NAME) ?: return
        val item = viewModel.uiState.value.items.find { it.pkg.packageName == pkgName }
        val pkg = item?.pkg ?: return
        currentPkg = pkg

        val pm = requireContext().packageManager
        binding.dialogExtName.text = pkg.label
        binding.dialogExtPackage.text = pkg.packageName

        val icon = runCatching { pm.getApplicationIcon(pkg.packageName) }.getOrNull()
        if (icon != null) {
            binding.dialogExtIcon.setImageDrawable(icon)
        } else {
            binding.dialogExtIcon.setImageResource(R.drawable.ic_layers)
        }

        binding.dialogQueryInput.setText(viewModel.uiState.value.searchQuery)

        // Bind existing result if available
        if (item.pipelineResult != null) {
            bindPipeline(item.pipelineResult)
        } else if (item.result != null) {
            bindBasicResult(item.result)
        } else {
            bindIdle()
        }

        binding.dialogButtonTest.setOnClickListener {
            runTest()
        }
    }

    private fun runTest() {
        val pkg = currentPkg ?: return
        val query = binding.dialogQueryInput.text.toString().trim().ifBlank { "Chainsaw Man" }

        binding.dialogButtonTest.isEnabled = false
        binding.dialogButtonTest.text = "Testing…"
        binding.dialogExtBadge.visibility = View.GONE

        viewModel.runSingleTest(pkg, query) { stageResult ->
            lifecycleScope.launch {
                bindPipeline(stageResult)
                if (!stageResult.isRunning) {
                    binding.dialogButtonTest.isEnabled = true
                    binding.dialogButtonTest.text = "Test"
                    binding.dialogExtBadge.visibility = View.VISIBLE
                    if (stageResult.isSuccess) {
                        binding.dialogExtBadge.text = "PASS"
                        binding.dialogExtBadge.setBackgroundResource(R.drawable.bg_pill_accent_selected)
                        binding.dialogExtBadge.setTextColor(0xFF10131E.toInt())
                    } else {
                        binding.dialogExtBadge.text = "FAIL"
                        binding.dialogExtBadge.setBackgroundResource(R.drawable.bg_badge_quality)
                        binding.dialogExtBadge.setTextColor(0xFFF2A6AC.toInt())
                    }
                }
            }
        }
    }

    private fun bindPipeline(pipeline: ExtensionProbe.ExtensionTestPipelineResult) {
        bindStage(
            binding.dialogSearchIcon,
            binding.dialogSearchProgress,
            binding.dialogSearchDetail,
            pipeline.search
        )
        bindStage(
            binding.dialogEpisodesIcon,
            binding.dialogEpisodesProgress,
            binding.dialogEpisodesDetail,
            pipeline.episodes
        )
        bindStage(
            binding.dialogServersIcon,
            binding.dialogServersProgress,
            binding.dialogServersDetail,
            pipeline.servers
        )
    }

    private fun bindBasicResult(result: ExtensionProbe.ProbeResult) {
        bindStage(
            binding.dialogSearchIcon,
            binding.dialogSearchProgress,
            binding.dialogSearchDetail,
            ExtensionProbe.StageReport(
                status = if (result.success) ExtensionProbe.StageStatus.SUCCESS else ExtensionProbe.StageStatus.FAILURE,
                summary = result.message,
                durationMs = result.durationMs
            )
        )
        bindStage(
            binding.dialogEpisodesIcon,
            binding.dialogEpisodesProgress,
            binding.dialogEpisodesDetail,
            ExtensionProbe.StageReport(ExtensionProbe.StageStatus.NOT_TESTED, "Not tested")
        )
        bindStage(
            binding.dialogServersIcon,
            binding.dialogServersProgress,
            binding.dialogServersDetail,
            ExtensionProbe.StageReport(ExtensionProbe.StageStatus.NOT_TESTED, "Not tested")
        )
    }

    private fun bindIdle() {
        bindStage(binding.dialogSearchIcon, binding.dialogSearchProgress, binding.dialogSearchDetail, null)
        bindStage(binding.dialogEpisodesIcon, binding.dialogEpisodesProgress, binding.dialogEpisodesDetail, null)
        bindStage(binding.dialogServersIcon, binding.dialogServersProgress, binding.dialogServersDetail, null)
    }

    private fun bindStage(
        iconView: ImageView,
        progressView: ProgressBar,
        detailView: TextView,
        report: ExtensionProbe.StageReport?
    ) {
        if (report == null || report.status == ExtensionProbe.StageStatus.NOT_TESTED) {
            progressView.visibility = View.GONE
            iconView.visibility = View.VISIBLE
            iconView.setImageResource(R.drawable.ic_stage_idle)
            detailView.text = report?.summary ?: "Not tested"
            detailView.setTextColor(0xFF8A92A6.toInt())
        } else if (report.status == ExtensionProbe.StageStatus.RUNNING) {
            progressView.visibility = View.VISIBLE
            iconView.visibility = View.GONE
            detailView.text = report.summary
            detailView.setTextColor(0xFFBAC7FF.toInt())
        } else if (report.status == ExtensionProbe.StageStatus.SUCCESS) {
            progressView.visibility = View.GONE
            iconView.visibility = View.VISIBLE
            iconView.setImageResource(R.drawable.ic_stage_success)
            detailView.text = report.summary
            detailView.setTextColor(0xFFBAC7FF.toInt())
        } else { // FAILURE
            progressView.visibility = View.GONE
            iconView.visibility = View.VISIBLE
            iconView.setImageResource(R.drawable.ic_stage_error)
            detailView.text = report.summary
            detailView.setTextColor(0xFFF2A6AC.toInt())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ExtensionTestingDetailSheet"
        private const val ARG_PACKAGE_NAME = "arg_package_name"

        fun newInstance(packageName: String): ExtensionTestingDetailSheet {
            val sheet = ExtensionTestingDetailSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_PACKAGE_NAME, packageName)
            }
            return sheet
        }
    }
}
