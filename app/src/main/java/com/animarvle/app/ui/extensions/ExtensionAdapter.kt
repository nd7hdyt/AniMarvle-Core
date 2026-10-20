package com.animarvle.app.ui.extensions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemExtensionBinding
import com.animarvle.app.extensions.AvailableExtension
import com.animarvle.app.extensions.ExtensionEngine

data class ExtensionItemUiModel(
    val item: AvailableExtension,
    val isInstalled: Boolean,
    val isDownloading: Boolean = false,
    val hasUpdate: Boolean = false,
    val updateVersion: String = "",
)

class ExtensionAdapter(
    private val onDownloadClick: (AvailableExtension) -> Unit,
    private val onUpdateClick: (AvailableExtension) -> Unit,
    private val onUninstallClick: (AvailableExtension) -> Unit,
    private val onSettingsClick: (AvailableExtension) -> Unit,
) : ListAdapter<ExtensionItemUiModel, ExtensionAdapter.ExtensionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExtensionViewHolder {
        val binding = ItemExtensionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ExtensionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExtensionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ExtensionViewHolder(private val binding: ItemExtensionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(model: ExtensionItemUiModel) {
            val ext = model.item
            val context = itemView.context
            val pm = context.packageManager

            binding.extensionName.text = ext.name
            binding.langBadge.text = ext.lang.uppercase()
            binding.versionBadge.text = if (ext.versionName.isNotBlank()) "V${ext.versionName}" else "V1.0"
            binding.engineLabelBadge.text = ext.engine.label

            binding.nsfwBadge.visibility = if (ext.isNsfw) View.VISIBLE else View.GONE

            // Icon loading: URL -> Installed App Icon -> Fallback layers icon
            if (!ext.iconUrl.isNullOrBlank()) {
                binding.extensionIcon.load(ext.iconUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_layers)
                    error(R.drawable.ic_layers)
                    transformations(RoundedCornersTransformation(16f))
                }
            } else if (model.isInstalled) {
                val appIcon = runCatching { pm.getApplicationIcon(ext.pkg) }.getOrNull()
                if (appIcon != null) {
                    binding.extensionIcon.setImageDrawable(appIcon)
                } else {
                    binding.extensionIcon.setImageResource(R.drawable.ic_layers)
                }
            } else {
                binding.extensionIcon.setImageResource(R.drawable.ic_layers)
            }

            // Engine badge icon overlay
            when (ext.engine) {
                ExtensionEngine.MANGAYOMI -> {
                    binding.engineBadge.setImageResource(R.drawable.ic_bookmark)
                }
                ExtensionEngine.CLOUDSTREAM -> {
                    binding.engineBadge.setImageResource(R.drawable.ic_cloud)
                }
                ExtensionEngine.SORA -> {
                    binding.engineBadge.setImageResource(R.drawable.ic_star)
                }
                else -> {
                    binding.engineBadge.setImageResource(R.drawable.ic_play_filled)
                }
            }

            // State handling: Installed vs Available vs Downloading
            if (model.isDownloading) {
                binding.actionDownload.visibility = View.GONE
                binding.installedActions.visibility = View.GONE
                binding.downloadProgress.visibility = View.VISIBLE
            } else if (model.isInstalled) {
                binding.actionDownload.visibility = View.GONE
                binding.downloadProgress.visibility = View.GONE
                binding.installedActions.visibility = View.VISIBLE

                if (model.hasUpdate) {
                    binding.actionUpdate.visibility = View.VISIBLE
                    binding.actionUpdate.setOnClickListener { onUpdateClick(ext) }
                } else {
                    binding.actionUpdate.visibility = View.GONE
                }

                binding.actionUninstall.setOnClickListener { onUninstallClick(ext) }
                binding.actionSettings.setOnClickListener { onSettingsClick(ext) }
            } else {
                binding.installedActions.visibility = View.GONE
                binding.downloadProgress.visibility = View.GONE
                binding.actionDownload.visibility = View.VISIBLE
                binding.actionDownload.setOnClickListener { onDownloadClick(ext) }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ExtensionItemUiModel>() {
        override fun areItemsTheSame(
            oldItem: ExtensionItemUiModel,
            newItem: ExtensionItemUiModel,
        ): Boolean = oldItem.item.id == newItem.item.id && oldItem.item.pkg == newItem.item.pkg

        override fun areContentsTheSame(
            oldItem: ExtensionItemUiModel,
            newItem: ExtensionItemUiModel,
        ): Boolean = oldItem == newItem
    }
}
