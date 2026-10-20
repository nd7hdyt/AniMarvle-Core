package com.animarvle.app.ui.mylist

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentMyListBinding
import com.animarvle.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MyListFragment : Fragment() {

    private var _binding: FragmentMyListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MyListViewModel by viewModels()
    private lateinit var adapter: MyListAdapter
    private lateinit var gridManager: GridLayoutManager
    private lateinit var displayPreferences: MyListDisplayPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMyListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        displayPreferences = MyListDisplayPreferences(requireContext())

        ViewCompat.setOnApplyWindowInsetsListener(binding.myListRoot) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        binding.toolbarTitle.setText(R.string.nav_my_list)

        // Setup search toggle
        binding.searchButton.setOnClickListener {
            binding.searchEditText.isVisible = !binding.searchEditText.isVisible
            if (binding.searchEditText.isVisible) {
                binding.searchEditText.requestFocus()
            } else {
                binding.searchEditText.setText("")
                viewModel.setQuery("")
            }
        }

        binding.searchEditText.addTextChangedListener { text ->
            viewModel.setQuery(text.toString().trim())
        }

        // Setup filterMenuButton (Display mode & items per row sheet)
        binding.filterMenuButton.setOnClickListener {
            val sheet = MyListDisplayBottomSheet { mode, itemsPerRow ->
                adapter.setDisplayMode(mode)
                val span = if (mode == DisplayMode.LIST) 1 else itemsPerRow
                gridManager.spanCount = span
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
            sheet.show(childFragmentManager, MyListDisplayBottomSheet.TAG)
        }

        // Setup Grid RecyclerView with saved preferences
        val initialMode = displayPreferences.displayMode
        val initialSpan = if (initialMode == DisplayMode.LIST) 1 else displayPreferences.itemsPerRow
        gridManager = GridLayoutManager(requireContext(), initialSpan)

        adapter = MyListAdapter(
            onItemClick = { item ->
                val isMovie = item.format?.equals("MOVIE", ignoreCase = true) == true
                findNavController().navigate(
                    R.id.detailFragment,
                    bundleOf(
                        "mediaId" to item.mediaId,
                        "mediaType" to if (isMovie) "movie" else "tv",
                    ),
                )
            },
            onItemLongClick = { item ->
                showRemoveDialog(item)
            },
        )
        adapter.setDisplayMode(initialMode)

        binding.recyclerView.layoutManager = gridManager
        binding.recyclerView.adapter = adapter

        // Setup Chip clicks
        setupStatusChips()

        // Observe items
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredItems.collectLatest { items ->
                        adapter.submitList(items)
                        binding.emptyContainer.isVisible = items.isEmpty()
                        binding.recyclerView.isVisible = items.isNotEmpty()
                    }
                }
            }
        }
    }

    private fun setupStatusChips() {
        val chips = listOf(
            binding.chipAll to null,
            binding.chipWatching to MyListStatus.WATCHING,
            binding.chipPlanToWatch to MyListStatus.PLAN_TO_WATCH,
            binding.chipCompleted to MyListStatus.COMPLETED,
            binding.chipOnHold to MyListStatus.ON_HOLD,
            binding.chipDropped to MyListStatus.DROPPED,
        )

        val accentColor = AppTheme.accent(requireContext())
        val unselectedBg = Color.parseColor("#1A1D26")
        val unselectedText = Color.parseColor("#8E92A0")

        fun updateChipStyles(activeStatus: MyListStatus?) {
            chips.forEach { (button, status) ->
                val isSelected = (status == activeStatus)
                button.strokeWidth = 0
                if (isSelected) {
                    button.backgroundTintList = ColorStateList.valueOf(accentColor)
                    button.setTextColor(Color.WHITE)
                } else {
                    button.backgroundTintList = ColorStateList.valueOf(unselectedBg)
                    button.setTextColor(unselectedText)
                }
            }
        }

        updateChipStyles(null)

        chips.forEach { (button, status) ->
            button.setOnClickListener {
                viewModel.selectStatus(status)
                updateChipStyles(status)
            }
        }
    }

    private fun showRemoveDialog(item: MyListItem) {
        val options = arrayOf(
            getString(R.string.my_list_add_title),
            getString(R.string.my_list_remove),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val sheet = AddToMyListBottomSheet.newInstance(
                            mediaId = item.mediaId,
                            title = item.title,
                            posterUrl = item.posterUrl,
                            totalEpisodes = item.totalEpisodes,
                        )
                        sheet.show(childFragmentManager, "AddToMyList")
                    }
                    1 -> {
                        viewModel.removeItem(item.mediaId)
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
