package com.animarvle.app.ui.extensions

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentExtensionManagerBinding
import com.animarvle.app.extensions.ExtensionEngine
import com.animarvle.app.extensions.MediaCategory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExtensionManagerFragment : Fragment() {

    private var _binding: FragmentExtensionManagerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExtensionManagerViewModel by viewModels()
    private lateinit var adapter: ExtensionAdapter
    private lateinit var updatesAdapter: ExtensionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentExtensionManagerBinding.inflate(inflater, container, false)
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

        setupToolbar()
        setupCategoryTabs()
        setupInstalledToggle()
        setupEngineChips()
        setupSearch()
        setupRecyclerViews()
        observeState()
    }

    private fun setupToolbar() {
        binding.buttonBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.buttonTesting.setOnClickListener {
            findNavController().navigate(R.id.extensionTestingFragment)
        }

        binding.buttonManageRepos.setOnClickListener {
            findNavController().navigate(R.id.extensionReposFragment)
        }

        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadExtensions()
        }

        binding.buttonUpdateAll.setOnClickListener {
            viewModel.updateAll()
        }
    }

    private fun setupCategoryTabs() {
        binding.tabAnime.setOnClickListener {
            updateCategoryUi(MediaCategory.ANIME)
            viewModel.setMediaCategory(MediaCategory.ANIME)
        }

        binding.tabMovie.setOnClickListener {
            updateCategoryUi(MediaCategory.MOVIES)
            viewModel.setMediaCategory(MediaCategory.MOVIES)
        }
    }

    private fun updateCategoryUi(selected: MediaCategory) {
        if (selected == MediaCategory.ANIME) {
            binding.tabAnime.setBackgroundResource(R.drawable.bg_pill_accent_selected)
            binding.tabAnime.setTextColor(0xFF10131E.toInt())
            binding.tabMovie.setBackgroundResource(android.R.color.transparent)
            binding.tabMovie.setTextColor(resources.getColor(R.color.on_bg_muted, null))
        } else {
            binding.tabMovie.setBackgroundResource(R.drawable.bg_pill_accent_selected)
            binding.tabMovie.setTextColor(0xFF10131E.toInt())
            binding.tabAnime.setBackgroundResource(android.R.color.transparent)
            binding.tabAnime.setTextColor(resources.getColor(R.color.on_bg_muted, null))
        }
    }

    private fun setupInstalledToggle() {
        binding.toggleInstalled.setOnClickListener {
            updateToggleSelection(showInstalled = true)
            viewModel.setShowInstalled(true)
        }

        binding.toggleAvailable.setOnClickListener {
            updateToggleSelection(showInstalled = false)
            viewModel.setShowInstalled(false)
        }
    }

    private fun updateToggleSelection(showInstalled: Boolean) {
        if (showInstalled) {
            binding.toggleInstalled.setBackgroundResource(R.drawable.bg_pill_accent_selected)
            binding.toggleInstalled.setTextColor(0xFF10131E.toInt())
            binding.toggleAvailable.setBackgroundResource(android.R.color.transparent)
            binding.toggleAvailable.setTextColor(resources.getColor(R.color.on_bg_muted, null))
        } else {
            binding.toggleAvailable.setBackgroundResource(R.drawable.bg_pill_accent_selected)
            binding.toggleAvailable.setTextColor(0xFF10131E.toInt())
            binding.toggleInstalled.setBackgroundResource(android.R.color.transparent)
            binding.toggleInstalled.setTextColor(resources.getColor(R.color.on_bg_muted, null))
        }
    }

    private fun setupEngineChips() {
        val chips = listOf(
            binding.chipEngineAll to ExtensionEngine.ALL,
            binding.chipEngineMangayomi to ExtensionEngine.MANGAYOMI,
            binding.chipEngineAniyomi to ExtensionEngine.ANIYOMI,
            binding.chipEngineCloudstream to ExtensionEngine.CLOUDSTREAM,
            binding.chipEngineSora to ExtensionEngine.SORA,
        )

        fun updateChips(activeEngine: ExtensionEngine) {
            chips.forEach { (view, engine) ->
                if (engine == activeEngine) {
                    view.setBackgroundResource(R.drawable.bg_pill_accent_selected)
                    view.setTextColor(0xFF10131E.toInt())
                } else {
                    view.setBackgroundResource(R.drawable.bg_capsule_bar)
                    view.setTextColor(resources.getColor(R.color.on_bg_muted, null))
                }
            }
        }

        chips.forEach { (view, engine) ->
            view.setOnClickListener {
                updateChips(engine)
                viewModel.setEngineFilter(engine)
            }
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty().trim())
        }

        binding.buttonLangFilter.setOnClickListener {
            val languages = viewModel.getAvailableLanguages()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.ext_filter_by_language)
                .setItems(languages.toTypedArray()) { _, which ->
                    val chosen = languages[which]
                    viewModel.setLanguageFilter(chosen)
                    Toast.makeText(requireContext(), getString(R.string.ext_filtering_by, chosen), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun setupRecyclerViews() {
        val onDownload: (com.animarvle.app.extensions.AvailableExtension) -> Unit = { ext ->
            Toast.makeText(
                requireContext(),
                getString(R.string.extensions_install_started, ext.name),
                Toast.LENGTH_SHORT
            ).show()
            viewModel.installExtension(ext)
        }

        val onUninstall: (com.animarvle.app.extensions.AvailableExtension) -> Unit = { ext ->
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.ext_uninstall_title)
                .setMessage(getString(R.string.ext_uninstall_confirm, ext.name))
                .setPositiveButton(R.string.ext_desc_uninstall) { _, _ ->
                    viewModel.uninstallExtension(ext)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        val onSettings: (com.animarvle.app.extensions.AvailableExtension) -> Unit = { ext ->
            viewModel.openAppSettings(ext.pkg)
        }

        adapter = ExtensionAdapter(
            onDownloadClick = onDownload,
            onUpdateClick = onDownload,
            onUninstallClick = onUninstall,
            onSettingsClick = onSettings,
        )

        updatesAdapter = ExtensionAdapter(
            onDownloadClick = onDownload,
            onUpdateClick = onDownload,
            onUninstallClick = onUninstall,
            onSettingsClick = onSettings,
        )

        binding.recyclerViewExtensions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewExtensions.adapter = adapter

        binding.recyclerViewUpdates.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewUpdates.adapter = updatesAdapter
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        binding.swipeRefreshLayout.isRefreshing = state.isLoading
                        adapter.submitList(state.items)
                        updatesAdapter.submitList(state.pendingUpdateItems)

                        // Updates Pending section visibility
                        if (state.isShowingInstalled && state.pendingUpdateItems.isNotEmpty()) {
                            binding.updatesPendingContainer.visibility = View.VISIBLE
                        } else {
                            binding.updatesPendingContainer.visibility = View.GONE
                        }

                        if (!state.isLoading && state.items.isEmpty()) {
                            binding.emptyView.visibility = View.VISIBLE
                            binding.recyclerViewExtensions.visibility = View.GONE
                        } else {
                            binding.emptyView.visibility = View.GONE
                            binding.recyclerViewExtensions.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.eventFlow.collectLatest { event ->
                        when (event) {
                            is ExtensionManagerViewModel.ExtensionEvent.ShowToast -> {
                                Toast.makeText(requireContext(), event.text, Toast.LENGTH_SHORT).show()
                            }
                            is ExtensionManagerViewModel.ExtensionEvent.RequestInstallPermission -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.extensions_install_permission_needed,
                                    Toast.LENGTH_LONG
                                ).show()
                                viewModel.installer.openUnknownSourcesSettings()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
