package com.animarvle.app.ui.download

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentDownloadBinding
import kotlinx.coroutines.launch
import java.util.Locale

class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!
    private lateinit var downloadRepo: DownloadRepository

    private lateinit var inProgressAdapter: DownloadInProgressAdapter
    private lateinit var completedSeriesAdapter: DownloadSeriesAdapter
    private lateinit var folderEpisodeAdapter: DownloadFolderEpisodeAdapter

    private var selectedSeriesTitle: String? = null
    private var allDownloadItems: List<DownloadItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            binding.folderStatusBarSpacer.layoutParams = binding.folderStatusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        downloadRepo = (requireActivity().application as AnimarvleApp).container.downloads

        // 1. In-Progress Adapter (Screenshot 2: "In Progress 1")
        inProgressAdapter = DownloadInProgressAdapter(
            onItemClick = { item ->
                if (item.status == DownloadStatus.COMPLETED) {
                    playDownload(item)
                }
            },
            onPauseResumeClick = { item ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (item.status == DownloadStatus.DOWNLOADING) {
                        downloadRepo.pauseDownload(item.id)
                    } else if (item.status == DownloadStatus.PAUSED) {
                        downloadRepo.resumeDownload(item.id)
                    }
                }
            },
            onCancelClick = { item ->
                viewLifecycleOwner.lifecycleScope.launch {
                    downloadRepo.remove(item.id)
                }
            },
        )
        binding.inProgressList.layoutManager = LinearLayoutManager(requireContext())
        binding.inProgressList.adapter = inProgressAdapter

        // 2. Completed Series Adapter (Screenshot 2: "Completed 1")
        completedSeriesAdapter = DownloadSeriesAdapter(
            onSeriesClick = { group ->
                selectedSeriesTitle = group.mediaTitle
                updateViewMode()
            },
        )
        binding.completedList.layoutManager = LinearLayoutManager(requireContext())
        binding.completedList.adapter = completedSeriesAdapter

        // 3. Folder Episodes Adapter (Screenshot 3: Inner folder view)
        folderEpisodeAdapter = DownloadFolderEpisodeAdapter(
            onItemClick = { item ->
                playDownload(item)
            },
            onDeleteClick = { item ->
                confirmDeleteEpisode(item)
            },
            onSwitchClick = { item ->
                Toast.makeText(requireContext(), "Quality: ${item.quality} (${item.serverName ?: "Turbo"})", Toast.LENGTH_SHORT).show()
            },
        )
        binding.folderEpisodeList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderEpisodeList.adapter = folderEpisodeAdapter

        // Back button from folder view
        binding.btnFolderBack.setOnClickListener {
            selectedSeriesTitle = null
            updateViewMode()
        }

        // Intercept back press when inside a folder
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectedSeriesTitle != null) {
                        selectedSeriesTitle = null
                        updateViewMode()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        // Clear all completed button
        binding.btnClearAll.setOnClickListener {
            confirmClearCompleted()
        }

        updateStorageSubtitle()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadRepo.downloadsFlow.collect(::render)
            }
        }
    }

    private fun render(items: List<DownloadItem>) {
        allDownloadItems = items
        val inProgressItems = items.filter {
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.PAUSED
        }
        val completedItems = items.filter { it.status == DownloadStatus.COMPLETED }

        val hasAny = items.isNotEmpty()
        binding.emptyContainer.isVisible = !hasAny

        // In Progress section
        if (inProgressItems.isNotEmpty()) {
            binding.inProgressSection.isVisible = true
            binding.inProgressHeader.text = "In Progress ${inProgressItems.size}"
            inProgressAdapter.submitList(inProgressItems)
        } else {
            binding.inProgressSection.isVisible = false
        }

        // Completed section
        if (completedItems.isNotEmpty()) {
            binding.completedSection.isVisible = true
            val seriesGroups = completedItems
                .groupBy { it.mediaTitle }
                .map { (title, epList) ->
                    DownloadSeriesGroup(
                        mediaTitle = title,
                        posterUrl = epList.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl,
                        episodeCount = epList.size,
                        downloadedCount = epList.count { it.status == DownloadStatus.COMPLETED },
                        episodes = epList.sortedBy { it.episodeNumber },
                    )
                }
            binding.completedHeader.text = "Completed ${seriesGroups.size}"
            completedSeriesAdapter.submitList(seriesGroups)
        } else {
            binding.completedSection.isVisible = false
        }

        updateViewMode()
        updateStorageSubtitle()
    }

    private fun updateViewMode() {
        val seriesTitle = selectedSeriesTitle
        if (seriesTitle == null) {
            // Root View (Screenshot 2)
            binding.rootContainer.isVisible = true
            binding.folderContainer.isVisible = false
        } else {
            // Folder View for selected anime / movie (Screenshot 3)
            val episodes = allDownloadItems
                .filter { it.mediaTitle.equals(seriesTitle, ignoreCase = true) }
                .sortedBy { it.episodeNumber }

            if (episodes.isEmpty()) {
                selectedSeriesTitle = null
                binding.rootContainer.isVisible = true
                binding.folderContainer.isVisible = false
            } else {
                binding.rootContainer.isVisible = false
                binding.folderContainer.isVisible = true
                binding.folderTitle.text = seriesTitle
                val count = episodes.size
                binding.folderSubtitle.text = "$count episode${if (count == 1) "" else "s"} on this device"
                folderEpisodeAdapter.submitList(episodes)
            }
        }
    }

    private fun playDownload(item: DownloadItem) {
        val playUri = if (!item.filePath.isNullOrBlank() && java.io.File(item.filePath).exists()) {
            android.net.Uri.fromFile(java.io.File(item.filePath)).toString()
        } else {
            item.streamUrl
        }
        val args = Bundle().apply {
            putString("mediaUrl", playUri)
            putString("title", item.mediaTitle)
            putString("mediaTitle", item.mediaTitle)
            putInt("episodeNumber", item.episodeNumber)
            putBoolean("isOfflineDownload", true)
            item.posterUrl?.let { putString("posterUrl", it) }
        }
        findNavController().navigate(R.id.playerFragment, args)
    }

    private fun confirmClearCompleted() {
        val completed = allDownloadItems.filter { it.status == DownloadStatus.COMPLETED }
        if (completed.isEmpty()) {
            Toast.makeText(requireContext(), "No completed downloads to clean", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_delete_title)
            .setMessage("Delete all ${completed.size} completed download(s)?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    downloadRepo.removeAllCompleted()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDeleteEpisode(item: DownloadItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_delete_title)
            .setMessage(getString(R.string.download_delete_message, "${item.mediaTitle} - ${item.episodeTitle}"))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    downloadRepo.remove(item.id)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateStorageSubtitle() {
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val availableBytes = availableBlocks * blockSize
            val availableGb = availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

            binding.storageAvailableSubtitle.text = String.format(Locale.US, "%.1f GB available on this device", availableGb)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
