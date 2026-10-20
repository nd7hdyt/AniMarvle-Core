package com.animarvle.app.ui.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentExtensionTestingBinding
import com.animarvle.app.extensions.MediaCategory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExtensionTestingFragment : Fragment() {

    private var _binding: FragmentExtensionTestingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExtensionTestingViewModel by activityViewModels()
    private lateinit var adapter: ExtensionTestingAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentExtensionTestingBinding.inflate(inflater, container, false)
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
        setupTypeTabs()
        setupTestTypeTabs()
        setupSearchQuery()
        setupRecyclerView()
        setupStartButton()
        observeState()
    }

    private fun setupToolbar() {
        binding.buttonBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupTypeTabs() {
        binding.tabTypeAnime.setOnClickListener {
            updateTypeUi(MediaCategory.ANIME)
            viewModel.setExtensionType(MediaCategory.ANIME)
        }

        binding.tabTypeMovie.setOnClickListener {
            updateTypeUi(MediaCategory.MOVIES)
            viewModel.setExtensionType(MediaCategory.MOVIES)
        }
    }

    private fun updateTypeUi(selected: MediaCategory) {
        if (selected == MediaCategory.ANIME) {
            binding.tabTypeAnime.setBackgroundResource(R.drawable.bg_pill_accent_selected)
            binding.tabTypeAnime.setTextColor(0xFF10131E.toInt())
            binding.tabTypeMovie.setBackgroundResource(android.R.color.transparent)
            binding.tabTypeMovie.setTextColor(resources.getColor(R.color.on_bg_muted, null))
        } else {
            binding.tabTypeMovie.setBackgroundResource(R.drawable.bg_pill_accent_selected)
            binding.tabTypeMovie.setTextColor(0xFF10131E.toInt())
            binding.tabTypeAnime.setBackgroundResource(android.R.color.transparent)
            binding.tabTypeAnime.setTextColor(resources.getColor(R.color.on_bg_muted, null))
        }
    }

    private fun setupTestTypeTabs() {
        binding.tabTestPing.setOnClickListener {
            updateTestTypeUi(ExtensionTestType.PING)
            viewModel.setTestType(ExtensionTestType.PING)
        }

        binding.tabTestBasic.setOnClickListener {
            updateTestTypeUi(ExtensionTestType.BASIC)
            viewModel.setTestType(ExtensionTestType.BASIC)
        }

        binding.tabTestFull.setOnClickListener {
            updateTestTypeUi(ExtensionTestType.FULL)
            viewModel.setTestType(ExtensionTestType.FULL)
        }
    }

    private fun updateTestTypeUi(selected: ExtensionTestType) {
        val tabs = listOf(
            binding.tabTestPing to ExtensionTestType.PING,
            binding.tabTestBasic to ExtensionTestType.BASIC,
            binding.tabTestFull to ExtensionTestType.FULL,
        )

        tabs.forEach { (view, type) ->
            if (type == selected) {
                view.setBackgroundResource(R.drawable.bg_pill_accent_selected)
                view.setTextColor(0xFF10131E.toInt())
            } else {
                view.setBackgroundResource(android.R.color.transparent)
                view.setTextColor(resources.getColor(R.color.on_bg_muted, null))
            }
        }
    }

    private fun setupSearchQuery() {
        binding.queryEditText.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty().trim())
        }
    }

    private fun setupRecyclerView() {
        adapter = ExtensionTestingAdapter(
            onItemClick = { item ->
                // Open testing detail sheet for single extension
                ExtensionTestingDetailSheet.newInstance(item.pkg.packageName)
                    .show(parentFragmentManager, ExtensionTestingDetailSheet.TAG)
            },
            onToggleSelect = { item ->
                viewModel.toggleSelection(item.pkg.packageName)
            }
        )

        binding.recyclerViewExtensions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewExtensions.adapter = adapter

        binding.buttonSelectAll.setOnClickListener {
            viewModel.selectAll()
        }
    }

    private fun setupStartButton() {
        binding.buttonStartTest.setOnClickListener {
            viewModel.runTests()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    adapter.submitList(state.items)

                    if (state.items.isEmpty()) {
                        binding.testingEmptyView.visibility = View.VISIBLE
                        binding.recyclerViewExtensions.visibility = View.GONE
                    } else {
                        binding.testingEmptyView.visibility = View.GONE
                        binding.recyclerViewExtensions.visibility = View.VISIBLE
                    }

                    val selectedCount = state.items.count { it.isSelected }
                    if (state.isTestingRunning) {
                        binding.buttonStartTest.isEnabled = false
                        binding.buttonStartTest.text = "Running tests…"
                    } else {
                        binding.buttonStartTest.isEnabled = selectedCount > 0
                        binding.buttonStartTest.text = if (selectedCount > 0) "Start Test ($selectedCount)" else "Select Extensions to Test"
                    }

                    val allSelected = state.items.isNotEmpty() && state.items.all { it.isSelected }
                    binding.buttonSelectAll.text = if (allSelected) "Deselect all" else "Select all"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
