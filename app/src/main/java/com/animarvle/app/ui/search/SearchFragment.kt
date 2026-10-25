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
import androidx.recyclerview.widget.LinearLayoutManager
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentSearchBinding
import com.animarvle.app.databinding.ItemSearchRecentRowBinding
import com.animarvle.app.databinding.PopupSearchCategoryMenuBinding
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
    private var categoryPopup: PopupWindow? = null

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

        binding.btnCategory.setOnClickListener {
            showCategoryDropdown()
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
        binding.resultsList.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsList.adapter = adapter

        // Clear all recents
        binding.clearRecentButton.setOnClickListener {
            viewModel.clearRecentQueries()
        }

        binding.retryButton.setOnClickListener {
            viewModel.retry()
        }

        val categoryArg = arguments?.getString("category")
        if (!categoryArg.isNullOrBlank()) {
            val matchingType = SearchType.entries.firstOrNull {
                it.displayName.equals(categoryArg, ignoreCase = true)
            }
            if (matchingType != null) {
                viewModel.onTypeChanged(matchingType)
            } else {
                viewModel.onFiltersApplied(SearchFilterOptions(category = categoryArg))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun showCategoryDropdown() {
        val context = requireContext()
        val popupBinding = PopupSearchCategoryMenuBinding.inflate(layoutInflater)
        val currentType = viewModel.state.value.type

        fun highlight(item: View, icon: ImageView, text: TextView, isSelected: Boolean) {
            if (isSelected) {
                item.setBackgroundResource(R.drawable.bg_search_dropdown_item_selected)
                val white = ContextCompat.getColor(context, android.R.color.white)
                icon.imageTintList = ColorStateList.valueOf(white)
                text.setTextColor(white)
            } else {
                item.setBackgroundResource(android.R.color.transparent)
                val muted = ContextCompat.getColor(context, R.color.on_bg_muted)
                icon.imageTintList = ColorStateList.valueOf(muted)
                text.setTextColor(muted)
            }
        }

        highlight(popupBinding.menuItemAll, popupBinding.iconAll, popupBinding.textAll, currentType == SearchType.ALL)
        highlight(popupBinding.menuItemMovie, popupBinding.iconMovie, popupBinding.textMovie, currentType == SearchType.MOVIE)
        highlight(popupBinding.menuItemTv, popupBinding.iconTv, popupBinding.textTv, currentType == SearchType.TV_SHOW)
        highlight(popupBinding.menuItemAnime, popupBinding.iconAnime, popupBinding.textAnime, currentType == SearchType.ANIME)
        highlight(popupBinding.menuItemAsian, popupBinding.iconAsian, popupBinding.textAsian, currentType == SearchType.ASIAN)

        fun select(type: SearchType) {
            viewModel.onTypeChanged(type)
            binding.categoryText.text = type.displayName
            binding.searchField.hint = type.hintText
            categoryPopup?.dismiss()
        }

        popupBinding.menuItemAll.setOnClickListener { select(SearchType.ALL) }
        popupBinding.menuItemMovie.setOnClickListener { select(SearchType.MOVIE) }
        popupBinding.menuItemTv.setOnClickListener { select(SearchType.TV_SHOW) }
        popupBinding.menuItemAnime.setOnClickListener { select(SearchType.ANIME) }
        popupBinding.menuItemAsian.setOnClickListener { select(SearchType.ASIAN) }

        categoryPopup?.dismiss()
        categoryPopup = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            showAsDropDown(binding.btnCategory, -dp(60), dp(8))
        }
    }

    private fun render(state: SearchState) {
        binding.categoryText.text = state.type.displayName
        if (binding.searchField.hint != state.type.hintText && binding.searchField.text.isNullOrBlank()) {
            binding.searchField.hint = state.type.hintText
        }

        val hasQuery = state.query.isNotBlank()

        if (hasQuery) {
            // In search results mode
            binding.preSearchContainer.isVisible = false

            val showSkeleton = state.loading && state.results.isEmpty()
            if (showSkeleton) {
                startSkeletonAnimation()
            } else {
                stopSkeletonAnimation()
            }

            val showError = state.error != null
            val showEmpty = !showError && state.searched && state.results.isEmpty()

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
                binding.emptyBody.text = "No results found for \"${state.query}\""
                binding.retryButton.isVisible = false
            } else {
                adapter.submit(state.results)
            }
        } else {
            // In pre-search mode (Recent searches + Everyone searching)
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
        val curSearchType = viewModel.state.value.type
        val isMovieTvMode = com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
            .currentMode.value == com.animarvle.app.data.AppMediaMode.MOVIES_TV
        val preferTmdb = media.isTmdb || when (curSearchType) {
            SearchType.MOVIE, SearchType.TV_SHOW -> true
            SearchType.ANIME -> false
            SearchType.ASIAN -> isMovieTvMode || media.isTmdb
            SearchType.ALL -> isMovieTvMode || media.isTmdb
        }
        openDetails(media.id, if (isMovie) "movie" else "tv", preferTmdb)
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
        categoryPopup?.dismiss()
        categoryPopup = null
        stopSkeletonAnimation()
        _binding = null
    }
}