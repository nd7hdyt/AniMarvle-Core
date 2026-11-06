package com.animarvle.app.ui.search

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentSearchBinding
import com.animarvle.app.databinding.ItemSearchRecentRowBinding
import com.animarvle.app.domain.MediaUi
import kotlinx.coroutines.launch

/**
 * Modern Glassy Search UI styled to Animarvle branding.
 * Features:
 * - Dynamic category dropdown menu (All, Movie, TV Show, Anime, Asian)
 * - Modern search pill bar with cyan focused glow and clear button
 * - Recent searches list with individual delete and clear-all
 * - Trending "Everyone searching" flow tags
 * - Glassy card container with portrait poster results and format/rating badges
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SearchViewModel
    private var pulseAnimator: ObjectAnimator? = null

    private val adapter = SearchAdapter(
        onOpen = { media -> openDetails(media) },
        onPlay = { media -> openDetails(media) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.navigationBarSpacer.layoutParams = binding.navigationBarSpacer.layoutParams.apply {
                height = bottom
            }
            insets
        }

        val container = (requireActivity().application as AnimarvleApp).container
        val repo = container.metadata
        val recentRepo = container.recentSearches
        val mediaTypeManager = com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
        viewModel = ViewModelProvider(this, SearchViewModel.Factory(repo, recentRepo, mediaTypeManager))[SearchViewModel::class.java]

        // Top controls
        binding.btnClose.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnFilter.setOnClickListener {
            val bottomSheet = FilterCatalogBottomSheet.newInstance(viewModel.state.value.filterOptions)
            bottomSheet.onFilterListener = { newFilters ->
                viewModel.onFiltersApplied(newFilters)
            }
            bottomSheet.show(childFragmentManager, FilterCatalogBottomSheet.TAG)
        }

        // Search bar
        binding.searchField.hint = viewModel.state.value.type.hintText
        binding.searchField.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                val q = binding.searchField.text?.toString().orEmpty().trim()
                if (q.isNotBlank()) {
                    viewModel.commitSearchQuery(q)
                    viewModel.onQueryChanged(q)
                    hideKeyboard()
                }
                true
            } else {
                false
            }
        }

        binding.searchField.addTextChangedListener { text ->
            val q = text?.toString().orEmpty()
            binding.btnClearSearch.isVisible = q.isNotBlank()
            viewModel.onQueryChanged(q)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.searchField.setText("")
        }

        // Results list
        val gridLayoutManager = GridLayoutManager(requireContext(), 3)
        binding.resultsList.layoutManager = gridLayoutManager
        binding.resultsList.adapter = adapter
        binding.resultsList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val totalItemCount = gridLayoutManager.itemCount
                val lastVisibleItem = gridLayoutManager.findLastVisibleItemPosition()
                if (totalItemCount > 0 && lastVisibleItem >= totalItemCount - 6) {
                    viewModel.loadMore()
                }
            }
        })

        // Clear all recents
        binding.clearRecentButton.setOnClickListener {
            viewModel.clearRecentQueries()
        }

        binding.retryButton.setOnClickListener {
            viewModel.retry()
        }

        val args = arguments
        if (args != null) {
            val filterType = args.getString("filterType")?.lowercase()
            val filterValue = args.getString("filterValue") ?: args.getString("filterName")
            val directorArg = args.getString("director") ?: if (filterType == "director" || filterType == "directors") filterValue else null
            val maleActorArg = args.getString("maleActor") ?: if (filterType == "male_actor" || filterType == "male actor" || filterType == "male actors") filterValue else null
            val femaleActressArg = args.getString("femaleActress") ?: if (filterType == "female_actress" || filterType == "female actress" || filterType == "female actresses") filterValue else null
            val studioArg = args.getString("studio") ?: if (filterType == "studio" || filterType == "studios") filterValue else null
            val genreArg = args.getString("genre") ?: if (filterType == "genre" || filterType == "genres") filterValue else null
            val categoryArg = args.getString("category")

            var options = viewModel.state.value.filterOptions
            if (!directorArg.isNullOrBlank()) options = options.copy(director = directorArg)
            if (!maleActorArg.isNullOrBlank()) options = options.copy(maleActor = maleActorArg)
            if (!femaleActressArg.isNullOrBlank()) options = options.copy(femaleActress = femaleActressArg)
            if (!studioArg.isNullOrBlank()) options = options.copy(studio = studioArg)
            if (!genreArg.isNullOrBlank()) options = options.copy(genres = setOf(genreArg))
            if (!categoryArg.isNullOrBlank()) {
                val matchingType = SearchType.entries.firstOrNull {
                    it.displayName.equals(categoryArg, ignoreCase = true) ||
                    (categoryArg.equals("series", ignoreCase = true) && it == SearchType.TV_SHOW) ||
                    (categoryArg.equals("movies", ignoreCase = true) && it == SearchType.MOVIE)
                }
                if (matchingType != null) {
                    viewModel.onTypeChanged(matchingType)
                } else {
                    options = options.copy(category = categoryArg)
                }
            }

            if (!options.isDefault) {
                viewModel.onFiltersApplied(options)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }


    private fun render(state: SearchState) {
        binding.filterActiveBadge.isVisible = state.filterOptions.activeFilterCount > 0
        if (binding.searchField.hint != state.type.hintText && binding.searchField.text.isNullOrBlank()) {
            binding.searchField.hint = state.type.hintText
        }

        val hasQuery = state.query.isNotBlank()
        val showResults = hasQuery || !state.filterOptions.isDefault

        if (showResults) {
            // In search results mode
            binding.glassCard.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.preSearchContainer.isVisible = false

            val showSkeleton = state.loading && state.results.isEmpty()
            if (showSkeleton) {
                startSkeletonAnimation()
            } else {
                stopSkeletonAnimation()
            }

            val showError = state.error != null
            val showEmpty = !showError && !state.loading && state.results.isEmpty()

            binding.resultsList.isVisible = !showError && !showEmpty && !showSkeleton
            binding.emptyBlock.isVisible = (showError || showEmpty) && !showSkeleton

            if (showError) {
                val raw = state.error.orEmpty()
                val isNetwork = raw.contains("Unable to resolve host", ignoreCase = true) ||
                    raw.contains("No address", ignoreCase = true) ||
                    raw.contains("Failed to connect", ignoreCase = true) ||
                    raw.contains("timeout", ignoreCase = true) ||
                    raw.contains("hostname", ignoreCase = true)
                binding.emptyTitle.setText(R.string.state_offline_title)
                binding.emptyBody.text = if (isNetwork) getString(R.string.state_offline_body) else raw
                binding.retryButton.isVisible = true
            } else if (showEmpty) {
                binding.emptyTitle.setText(R.string.search_empty_title)
                binding.emptyBody.text = if (state.query.isNotBlank()) {
                    "No results found for \"${state.query}\""
                } else {
                    "No titles found matching your active filters"
                }
                binding.retryButton.isVisible = false
            } else {
                adapter.submit(state.results)
            }
        } else {
            // In pre-search mode (Recent searches + Everyone searching)
            binding.glassCard.setCardBackgroundColor(0xFF181818.toInt())
            stopSkeletonAnimation()
            binding.resultsList.isVisible = false
            binding.emptyBlock.isVisible = false
            binding.preSearchContainer.isVisible = true

            // Render Recent Searches
            val hasRecents = state.recentQueries.isNotEmpty()
            binding.recentSearchesBlock.isVisible = hasRecents
            if (hasRecents) {
                renderRecentList(state.recentQueries)
            }

            // Render "Everyone searching" Trending Tags
            renderTrendingChips(state.trendingTags)
        }
    }

    private fun renderRecentList(queries: List<String>) {
        binding.recentListContainer.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (q in queries) {
            val rowBinding = ItemSearchRecentRowBinding.inflate(inflater, binding.recentListContainer, false)
            rowBinding.recentQueryText.text = q
            rowBinding.root.setOnClickListener {
                binding.searchField.setText(q)
                binding.searchField.setSelection(q.length)
                viewModel.commitSearchQuery(q)
                viewModel.onQueryChanged(q)
                hideKeyboard()
            }
            rowBinding.recentDeleteButton.setOnClickListener {
                viewModel.removeRecentQuery(q)
            }
            binding.recentListContainer.addView(rowBinding.root)
        }
    }

    private fun renderTrendingChips(tags: List<String>) {
        binding.everyoneChips.removeAllViews()
        val context = requireContext()
        for (tag in tags) {
            val chip = TextView(context).apply {
                text = tag
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT
                setTextColor(ContextCompat.getColor(context, R.color.on_bg))
                setBackgroundResource(R.drawable.bg_search_tag_pill)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    binding.searchField.setText(tag)
                    binding.searchField.setSelection(tag.length)
                    viewModel.commitSearchQuery(tag)
                    viewModel.onQueryChanged(tag)
                    hideKeyboard()
                }
            }
            binding.everyoneChips.addView(chip)
        }
    }

    private fun startSkeletonAnimation() {
        binding.searchSkeleton.isVisible = true
        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(
                binding.searchSkeleton,
                View.ALPHA,
                1.0f,
                0.35f,
            ).apply {
                duration = 850L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (pulseAnimator?.isRunning != true) {
            pulseAnimator?.start()
        }
    }

    private fun stopSkeletonAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        _binding?.let { b ->
            b.searchSkeleton.alpha = 1.0f
            b.searchSkeleton.isVisible = false
        }
    }

    private fun openDetails(media: MediaUi) {
        val currentQuery = binding.searchField.text?.toString().orEmpty().trim()
        if (currentQuery.length >= 2) {
            viewModel.commitSearchQuery(currentQuery)
        }
        val isMovie = media.format.equals("MOVIE", ignoreCase = true)
        val isAnimeMedia = !media.isTmdb ||
            com.animarvle.app.ui.player.HistoryRepository.isAnimeTitle(media.title) ||
            media.coverUrl?.contains("anilist.co") == true ||
            media.coverUrl?.contains("myanimelist.net") == true
        val preferTmdb = if (isAnimeMedia) false else media.isTmdb
        val mediaType = if (preferTmdb) {
            if (isMovie) "movie" else "tv"
        } else {
            if (isMovie) "movie" else "anime"
        }
        openDetails(media.id, mediaType, preferTmdb)
    }

    private fun openDetails(id: Long, mediaType: String? = null, preferTmdb: Boolean = false) {
        val args = Bundle().apply {
            putLong("mediaId", id)
            if (mediaType != null) putString("mediaType", mediaType)
            putBoolean("preferTmdb", preferTmdb)
        }
        findNavController().navigate(com.animarvle.app.R.id.detailsFragment, args)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val view = binding.searchField
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSkeletonAnimation()
        _binding = null
    }
}