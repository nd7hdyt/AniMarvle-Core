package com.animarvle.app.ui.mylist

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        binding.btnSearch.setOnClickListener {
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

        // Setup stacked filter button (Display mode & items per row sheet)
        binding.btnStackedFilter.setOnClickListener {
            val sheet = MyListDisplayBottomSheet { mode, itemsPerRow ->
                adapter.setDisplayMode(mode)
                val span = if (mode == DisplayMode.LIST) 1 else itemsPerRow
                gridManager.spanCount = span
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
            sheet.show(childFragmentManager, MyListDisplayBottomSheet.TAG)
        }

        // Setup Grid RecyclerView with saved preferences (3-column grid default)
        val initialMode = displayPreferences.displayMode
        val initialSpan = if (initialMode == DisplayMode.LIST) 1 else displayPreferences.itemsPerRow
        gridManager = GridLayoutManager(requireContext(), initialSpan)

        adapter = MyListAdapter(
            onItemClick = { item ->
                val isMovie = item.format?.equals("MOVIE", ignoreCase = true) == true ||
                    item.format?.equals("Movie", ignoreCase = true) == true
                val isTv = item.format?.equals("TV", ignoreCase = true) == true ||
                    item.format?.contains("SERIES", ignoreCase = true) == true
                val mediaType = if (isMovie) "MOVIE" else if (isTv) "TV" else "ANIME"
                val isMoviesTv = com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
                    .currentMode.value == com.animarvle.app.data.AppMediaMode.MOVIES_TV
                val args = Bundle().apply {
                    putLong("mediaId", item.mediaId)
                    putString("mediaType", mediaType)
                    putBoolean("preferTmdb", isMoviesTv)
                }
                findNavController().navigate(R.id.detailsFragment, args)
            },
            onItemLongClick = { item ->
                showRemoveDialog(item)
            },
            onMarkCompleteClick = { item ->
                val newStatus = if (item.status == MyListStatus.COMPLETED) {
                    MyListStatus.WATCHING
                } else {
                    MyListStatus.COMPLETED
                }
                viewModel.updateItemStatus(item, newStatus)
                val msg = if (newStatus == MyListStatus.COMPLETED) {
                    "Marked as Completed"
                } else {
                    "Moved to Pending"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            },
            onRemoveClick = { item ->
                viewModel.removeItem(item.mediaId)
                Toast.makeText(requireContext(), getString(R.string.my_list_removed_toast), Toast.LENGTH_SHORT).show()
            },
        )
        adapter.setDisplayMode(initialMode)

        binding.myListRecyclerView.layoutManager = gridManager
        binding.myListRecyclerView.adapter = adapter

        // Setup Dual-Tier Pills with smooth transition animations
        setupTier1Pills()
        setupTier2Pills()

        // Observe items & update empty state dynamically
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredItems.collectLatest { items ->
                        adapter.submitList(items)
                        val isEmpty = items.isEmpty()
                        binding.emptyStateLayout.isVisible = isEmpty
                        binding.myListRecyclerView.isVisible = !isEmpty

                        if (isEmpty) {
                            val currentType = viewModel.selectedType.value
                            val currentStatus = viewModel.selectedStatus.value
                            val query = viewModel.searchQuery.value

                            if (query.isNotBlank()) {
                                binding.emptyTitle.text = "No results found"
                                binding.emptySubtitle.text = "No titles match \"$query\""
                            } else {
                                val statusWord = when {
                                    currentStatus.equals("Completed", ignoreCase = true) -> "completed"
                                    currentStatus.equals("Watching", ignoreCase = true) -> "watching"
                                    else -> "pending"
                                }
                                val categoryWord = when (currentType) {
                                    "Movies" -> "movies"
                                    "Series" -> "series"
                                    else -> "anime"
                                }
                                binding.emptyTitle.text = if (statusWord == "watching") {
                                    "No $categoryWord in progress"
                                } else {
                                    "No $statusWord $categoryWord"
                                }
                                binding.emptySubtitle.text = when (statusWord) {
                                    "watching" -> "Titles you are currently watching will appear here."
                                    "completed" -> "Titles you finish can be kept here for later."
                                    else -> "Save titles from their details page to find them here."
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updatePillRow(
        container: ViewGroup,
        pills: List<TextView>,
        selectedView: TextView,
        animate: Boolean = true,
    ) {
        if (animate) {
            val transition = android.transition.TransitionSet().apply {
                ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                addTransition(android.transition.ChangeBounds())
                addTransition(android.transition.Fade(android.transition.Fade.IN))
                duration = 180L
                interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
            }
            android.transition.TransitionManager.beginDelayedTransition(container, transition)
        }

        pills.forEach { pill ->
            val isSelected = (pill == selectedView)
            if (isSelected) {
                pill.setBackgroundResource(R.drawable.bg_mylist_pill_selected)
                pill.setTextColor(Color.BLACK)
                pill.setTypeface(null, Typeface.BOLD)
                pill.setPadding(dp(22), 0, dp(22), 0)
            } else {
                pill.background = null
                pill.setTextColor(Color.parseColor("#8E8E93"))
                pill.setTypeface(null, Typeface.BOLD)
                pill.setPadding(dp(16), 0, dp(16), 0)
            }
        }
    }

    private fun setupTier1Pills() {
        val pills = listOf(binding.tabMovies, binding.tabSeries, binding.tabAnime)
        val typeToPill = mapOf(
            "Movies" to binding.tabMovies,
            "Series" to binding.tabSeries,
            "Anime" to binding.tabAnime,
        )

        val currentSelected = typeToPill[viewModel.selectedType.value] ?: binding.tabMovies
        updatePillRow(binding.categoryTabRow, pills, currentSelected, animate = false)

        typeToPill.forEach { (type, textView) ->
            textView.setOnClickListener {
                viewModel.selectType(type)
                updatePillRow(binding.categoryTabRow, pills, textView, animate = true)
            }
        }
    }

    private fun setupTier2Pills() {
        val pills = listOf(binding.tabPending, binding.tabWatching, binding.tabCompleted)
        val statusToPill = mapOf(
            "Pending" to binding.tabPending,
            "Watching" to binding.tabWatching,
            "Completed" to binding.tabCompleted,
        )

        val currentSelected = statusToPill[viewModel.selectedStatus.value] ?: binding.tabPending
        updatePillRow(binding.statusTabRow, pills, currentSelected, animate = false)

        statusToPill.forEach { (status, textView) ->
            textView.setOnClickListener {
                viewModel.selectStatus(status)
                updatePillRow(binding.statusTabRow, pills, textView, animate = true)
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
                            format = item.format,
                            score = item.score,
                            totalEpisodes = item.totalEpisodes,
                            year = item.year,
                        )
                        sheet.show(childFragmentManager, "AddToMyList")
                    }
                    1 -> {
                        viewModel.removeItem(item.mediaId)
                        Toast.makeText(requireContext(), getString(R.string.my_list_removed_toast), Toast.LENGTH_SHORT).show()
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
