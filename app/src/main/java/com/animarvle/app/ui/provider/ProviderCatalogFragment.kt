package com.animarvle.app.ui.provider

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentProviderCatalogBinding
import com.animarvle.app.ui.home.Media
import com.animarvle.app.ui.player.ChoiceDialog
import com.animarvle.app.ui.player.ChoiceRow
import kotlinx.coroutines.launch

class ProviderCatalogFragment : Fragment() {

    private var _binding: FragmentProviderCatalogBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ProviderCatalogViewModel
    private lateinit var catalogAdapter: ProviderCatalogAdapter
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProviderCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val providerName = arguments?.getString("providerName") ?: "Catalog"
        binding.tvProviderName.text = providerName

        val app = requireActivity().application as AnimarvleApp
        viewModel = ViewModelProvider(
            this,
            ProviderCatalogViewModel.Factory(app.container.metadata, providerName)
        )[ProviderCatalogViewModel::class.java]

        // Initial fallback from system resource identifier so it's never 0dp even before insets dispatch
        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallbackTop = if (statusBarResId > 0) resources.getDimensionPixelSize(statusBarResId) else 0
        if (fallbackTop > 0) {
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = fallbackTop
            }
        }

        // Ensure the header sits below the status bar by resizing the spacer
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (top > 0) {
                binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                    height = top
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Setup 3-Column Grid with dedicated catalog adapter
        catalogAdapter = ProviderCatalogAdapter { media ->
            navigateToDetails(media)
        }
        binding.rvProviderCatalog.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvProviderCatalog.adapter = catalogAdapter

        // Setup Category Pill Listeners
        binding.rgCategories.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.tabMovies.id -> viewModel.setCategory("MOVIE")
                binding.tabSeries.id -> viewModel.setCategory("SERIES")
                binding.tabAnime.id -> viewModel.setCategory("ANIME")
            }
        }

        // Setup Sort Dropdown Dialog using ChoiceDialog with exact VortexBlurHelper
        binding.btnSort.setOnClickListener {
            val currentSort = viewModel.currentSort.value
            val rows = listOf(
                ChoiceRow(
                    title = "Popular",
                    selected = currentSort == CatalogSort.POPULAR
                ),
                ChoiceRow(
                    title = "Newest",
                    selected = currentSort == CatalogSort.NEWEST
                ),
                ChoiceRow(
                    title = "Top Rated",
                    selected = currentSort == CatalogSort.TOP_RATED
                )
            )
            ChoiceDialog.show(
                context = requireContext(),
                title = "",
                rows = rows
            ) { index ->
                val selected = when (index) {
                    0 -> CatalogSort.POPULAR
                    1 -> CatalogSort.NEWEST
                    2 -> CatalogSort.TOP_RATED
                    else -> CatalogSort.POPULAR
                }
                viewModel.setSort(selected)
            }
        }

        // Observe Data & Loading State
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.catalogState.collect { items ->
                        catalogAdapter.submitList(items)
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        if (loading) {
                            startSkeletonAnimation()
                        } else {
                            stopSkeletonAnimation()
                        }
                    }
                }
            }
        }
    }

    private fun navigateToDetails(media: Media) {
        val id = media.originalId ?: media.id.toLongOrNull() ?: -1L
        val isMoviesTv = com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
            .currentMode.value == com.animarvle.app.data.AppMediaMode.MOVIES_TV
        val mediaType = media.mediaType ?: if (media.tags.contains("MOVIE")) "MOVIE" else "ANIME"
        val args = Bundle().apply {
            putLong("mediaId", id)
            putString("mediaType", mediaType)
            putBoolean("preferTmdb", isMoviesTv)
        }
        findNavController().navigate(R.id.detailsFragment, args)
    }

    private fun startSkeletonAnimation() {
        _binding?.let { b ->
            b.skeletonContainer.isVisible = true
            b.rvProviderCatalog.isVisible = false
            if (pulseAnimator == null) {
                pulseAnimator = ObjectAnimator.ofFloat(
                    b.skeletonContentLayout,
                    View.ALPHA,
                    1.0f,
                    0.42f,
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
    }

    private fun stopSkeletonAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        _binding?.let { b ->
            b.skeletonContentLayout.alpha = 1.0f
            b.skeletonContainer.isVisible = false
            b.rvProviderCatalog.isVisible = true
        }
    }

    override fun onDestroyView() {
        stopSkeletonAnimation()
        super.onDestroyView()
        _binding = null
    }
}
