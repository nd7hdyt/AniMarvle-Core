package com.animarvle.app.ui.extensions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemTestingExtensionBinding
import com.animarvle.app.extensions.ExtensionPackage
import com.animarvle.app.extensions.ExtensionProbe

data class TestingExtensionUiModel(
    val pkg: ExtensionPackage,
    val isSelected: Boolean = false,
    val isRunning: Boolean = false,
    val result: ExtensionProbe.ProbeResult? = null,
    val pipelineResult: ExtensionProbe.ExtensionTestPipelineResult? = null,
)

class ExtensionTestingAdapter(
    private val onItemClick: (TestingExtensionUiModel) -> Unit,
    private val onToggleSelect: (TestingExtensionUiModel) -> Unit,
) : ListAdapter<TestingExtensionUiModel, ExtensionTestingAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTestingExtensionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTestingExtensionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(model: TestingExtensionUiModel) {
            val pm = itemView.context.packageManager
            val ext = model.pkg

            binding.extensionName.text = ext.label

            val appIcon = runCatching { pm.getApplicationIcon(ext.packageName) }.getOrNull()
            if (appIcon != null) {
                binding.extensionIcon.setImageDrawable(appIcon)
            } else {
                binding.extensionIcon.setImageResource(R.drawable.ic_layers)
            }

            // Selection Circle
            if (model.isSelected) {
                binding.checkboxSelect.setImageResource(R.drawable.ic_circle_checked)
                binding.checkboxSelect.setColorFilter(0xFFBAC7FF.toInt())
            } else {
                binding.checkboxSelect.setImageResource(R.drawable.ic_circle_unchecked)
                binding.checkboxSelect.setColorFilter(0xFF6E7487.toInt())
            }

            binding.checkboxSelect.setOnClickListener {
                onToggleSelect(model)
            }

            itemView.setOnClickListener {
                onItemClick(model)
            }

            // Pipeline 3-stage reporting (Search, Episodes, Servers)
            val pipeline = model.pipelineResult
            if (pipeline != null) {
                bindStage(
                    binding.searchStageIcon,
                    binding.searchStageProgress,
                    binding.searchStageDetail,
                    pipeline.search
                )
                bindStage(
                    binding.episodesStageIcon,
                    binding.episodesStageProgress,
                    binding.episodesStageDetail,
                    pipeline.episodes
                )
                bindStage(
                    binding.serversStageIcon,
                    binding.serversStageProgress,
                    binding.serversStageDetail,
                    pipeline.servers
                )
            } else if (model.result != null) {
                // Synthesize from basic/ping probe result
                val isSuccess = model.result.success
                bindStage(
                    binding.searchStageIcon,
                    binding.searchStageProgress,
                    binding.searchStageDetail,
                    ExtensionProbe.StageReport(
                        status = if (isSuccess) ExtensionProbe.StageStatus.SUCCESS else ExtensionProbe.StageStatus.FAILURE,
                        summary = model.result.message,
                        durationMs = model.result.durationMs
                    )
                )
                bindStage(
                    binding.episodesStageIcon,
                    binding.episodesStageProgress,
                    binding.episodesStageDetail,
                    ExtensionProbe.StageReport(
                        status = ExtensionProbe.StageStatus.NOT_TESTED,
                        summary = "Not tested"
                    )
                )
                bindStage(
                    binding.serversStageIcon,
                    binding.serversStageProgress,
                    binding.serversStageDetail,
                    ExtensionProbe.StageReport(
                        status = ExtensionProbe.StageStatus.NOT_TESTED,
                        summary = "Not tested"
                    )
                )
            } else if (model.isRunning) {
                bindStage(
                    binding.searchStageIcon,
                    binding.searchStageProgress,
                    binding.searchStageDetail,
                    ExtensionProbe.StageReport(
                        status = ExtensionProbe.StageStatus.RUNNING,
                        summary = "Testing in progress..."
                    )
                )
                bindStage(
                    binding.episodesStageIcon,
                    binding.episodesStageProgress,
                    binding.episodesStageDetail,
                    ExtensionProbe.StageReport(
                        status = ExtensionProbe.StageStatus.NOT_TESTED,
                        summary = "Pending"
                    )
                )
                bindStage(
                    binding.serversStageIcon,
                    binding.serversStageProgress,
                    binding.serversStageDetail,
                    ExtensionProbe.StageReport(
                        status = ExtensionProbe.StageStatus.NOT_TESTED,
                        summary = "Pending"
                    )
                )
            } else {
                // Idle / unrun
                bindStage(
                    binding.searchStageIcon,
                    binding.searchStageProgress,
                    binding.searchStageDetail,
                    null
                )
                bindStage(
                    binding.episodesStageIcon,
                    binding.episodesStageProgress,
                    binding.episodesStageDetail,
                    null
                )
                bindStage(
                    binding.serversStageIcon,
                    binding.serversStageProgress,
                    binding.serversStageDetail,
                    null
                )
            }
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
    }

    companion object DiffCallback : DiffUtil.ItemCallback<TestingExtensionUiModel>() {
        override fun areItemsTheSame(
            oldItem: TestingExtensionUiModel,
            newItem: TestingExtensionUiModel,
        ): Boolean = oldItem.pkg.packageName == newItem.pkg.packageName

        override fun areContentsTheSame(
            oldItem: TestingExtensionUiModel,
            newItem: TestingExtensionUiModel,
        ): Boolean = oldItem == newItem
    }
}
