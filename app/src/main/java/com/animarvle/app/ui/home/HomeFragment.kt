package com.animarvle.app.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager
import com.animarvle.app.databinding.FragmentHomeBinding
import com.animarvle.app.ui.mylist.AddToMyListBottomSheet
import com.animarvle.app.ui.player.HistoryRepository
import com.animarvle.app.ui.player.VortexBlurHelper
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: HomeViewModel
    private lateinit var feedAdapter: HomeFeedAdapter

    private var isAnimeSelected = true
    private var totalScrollY = 0
    private var pullStartY = 0f
    private var isPulling = false
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as AnimarvleApp
        val mediaTypeManager = MediaTypeManager.getInstance(requireContext())
        viewModel = ViewModelProvider(
            this,
            HomeViewModel.Factory(app.container.metadata, app.container.history, mediaTypeManager)
        )[HomeViewModel::class.java]

        isAnimeSelected = viewModel.currentDomain.value == HomeDomain.ANIME
        syncCapsuleStyle(animate = false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        setupScrollAndFrostedHeader()
        setupPullToReloadWithMorph()
        setupCapsuleToggle()

        binding.btnSearch.setOnClickListener {
            findNavController().navigate(R.id.searchFragment)
        }

        // Tapping logo also scrolls to top and reloads
        binding.brandLogo.setOnClickListener {
            binding.feedRecyclerView.smoothScrollToPosition(0)
            viewModel.refresh()
        }

        feedAdapter = HomeFeedAdapter(
            onMediaClick = { media -> openDetails(media) },
            onResumeClick = { media -> resumePlayback(media) },
            onPlayClick = { media -> openDetails(media) },
            onMyListClick = { media -> openAddToMyList(media) },
            onSeeAllClick = { category -> openCategoryInSearch(category) },
            onProviderClick = { provider -> openProviderCatalog(provider) },
        )

        binding.feedRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.feedRecyclerView.adapter = feedAdapter
        startSkeletonAnimation()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.feedState.collect { rails ->
                        feedAdapter.submitList(rails)
                        if (rails.isNotEmpty()) {
                            stopSkeletonAnimation()
                        }
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        if (loading && feedAdapter.currentList.isEmpty()) {
                            startSkeletonAnimation()
                        } else {
                            binding.morphLoaderContainer.isVisible = loading
                            if (loading) {
                                binding.morphLoaderContainer.translationY = 0f
                                binding.morphLoaderContainer.alpha = 1f
                            }
                        }
                    }
                }
                launch {
                    val appContainer = (requireActivity().application as com.animarvle.app.AnimarvleApp).container
                    appContainer.myList.itemsFlow.collect { items ->
                        val savedIds = items.map { it.mediaId }.toSet()
                        feedAdapter.setSavedIds(savedIds)
                    }
                }
            }
        }
    }

    /**
     * Pull-down gesture at the top of feedRecyclerView reveals VortexMorphLoadingView
     * and triggers viewModel.refresh().
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPullToReloadWithMorph() {
        val triggerThresholdPx = 110f * resources.displayMetrics.density

        binding.feedRecyclerView.setOnTouchListener { _, event ->
            if (!binding.feedRecyclerView.canScrollVertically(-1) && !viewModel.isLoading.value) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pullStartY = event.rawY
                        isPulling = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (pullStartY == 0f) pullStartY = event.rawY
                        val dy = event.rawY - pullStartY
                        if (dy > 16f) {
                            isPulling = true
                            val drag = (dy * 0.45f).coerceAtMost(triggerThresholdPx)
                            val progress = (drag / triggerThresholdPx).coerceIn(0f, 1f)
                            binding.morphLoaderContainer.isVisible = true
                            binding.morphLoaderContainer.alpha = progress
                            binding.morphLoaderContainer.translationY = drag * 0.5f
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isPulling) {
                            val dy = event.rawY - pullStartY
                            isPulling = false
                            pullStartY = 0f
                            if (dy * 0.45f >= triggerThresholdPx * 0.75f) {
                                viewModel.refresh()
                            } else {
                                binding.morphLoaderContainer.animate()
                                    .alpha(0f)
                                    .translationY(0f)
                                    .setDuration(180L)
                                    .withEndAction {
                                        if (!viewModel.isLoading.value) {
                                            binding.morphLoaderContainer.isVisible = false
                                        }
                                    }
                                    .start()
                            }
                        }
                        pullStartY = 0f
                    }
                }
            }
            false
        }
    }

    /**
     * Interpolates real Frosted-Glass Blur on headerContainer using VortexBlurHelper as the user scrolls.
     */
    private fun setupScrollAndFrostedHeader() {
        val maxScrollPx = (140 * resources.displayMetrics.density).toInt()

        // Render the curved pre-scroll vignette immediately once laid out
        binding.headerContainer.post {
            if (_binding != null) {
                VortexBlurHelper.applyHeaderScrollBlur(
                    sourceView = binding.feedRecyclerView,
                    headerBar = binding.headerContainer,
                    scrollFraction = (totalScrollY.toFloat() / maxScrollPx).coerceIn(0f, 1f)
                )
            }
        }

        binding.feedRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                totalScrollY = (totalScrollY + dy).coerceAtLeast(0)
                if (!recyclerView.canScrollVertically(-1)) {
                    totalScrollY = 0
                }

                val fraction = (totalScrollY.toFloat() / maxScrollPx).coerceIn(0f, 1f)
                VortexBlurHelper.applyHeaderScrollBlur(
                    sourceView = binding.feedRecyclerView,
                    headerBar = binding.headerContainer,
                    scrollFraction = fraction
                )
            }
        })
    }

    private fun setupCapsuleToggle() {
        binding.toggleAnime.setOnClickListener {
            if (!isAnimeSelected) {
                isAnimeSelected = true
                syncCapsuleStyle(animate = true)
                binding.feedRecyclerView.scrollToPosition(0)
                totalScrollY = 0
                VortexBlurHelper.applyHeaderScrollBlur(binding.feedRecyclerView, binding.headerContainer, 0f)
                startSkeletonAnimation()
                viewModel.setDomain(HomeDomain.ANIME)
            }
        }

        binding.toggleMovie.setOnClickListener {
            if (isAnimeSelected) {
                isAnimeSelected = false
                syncCapsuleStyle(animate = true)
                binding.feedRecyclerView.scrollToPosition(0)
                totalScrollY = 0
                VortexBlurHelper.applyHeaderScrollBlur(binding.feedRecyclerView, binding.headerContainer, 0f)
                startSkeletonAnimation()
                viewModel.setDomain(HomeDomain.MOVIE)
            }
        }
    }

    private fun syncCapsuleStyle(animate: Boolean) {
        if (animate && binding.toggleAnime.parent is ViewGroup) {
            val transition = ChangeBounds().apply {
                duration = 200L
                interpolator = FastOutSlowInInterpolator()
            }
            TransitionManager.beginDelayedTransition(binding.toggleAnime.parent as ViewGroup, transition)
        }

        if (isAnimeSelected) {
            binding.toggleAnime.setBackgroundResource(R.drawable.bg_capsule_active)
            binding.toggleAnime.setTextColor(Color.parseColor("#000000"))
            binding.toggleMovie.setBackgroundResource(android.R.color.transparent)
            binding.toggleMovie.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            binding.toggleMovie.setBackgroundResource(R.drawable.bg_capsule_active)
            binding.toggleMovie.setTextColor(Color.parseColor("#000000"))
            binding.toggleAnime.setBackgroundResource(android.R.color.transparent)
            binding.toggleAnime.setTextColor(Color.parseColor("#FFFFFF"))
        }
    }

    private fun openDetails(media: Media) {
        val id = media.originalId ?: media.id.toLongOrNull() ?: -1L
        val isMoviesTv = MediaTypeManager.getInstance(requireContext()).currentMode.value == AppMediaMode.MOVIES_TV
        val mediaType = media.mediaType ?: if (media.tags.contains("MOVIE")) "MOVIE" else "ANIME"
        val isExplicitAnime = mediaType.equals("ANIME", ignoreCase = true)
        val preferTmdb = if (isExplicitAnime) false else isMoviesTv
        val args = Bundle().apply {
            putLong("mediaId", id)
            putString("mediaType", mediaType)
            putBoolean("preferTmdb", preferTmdb)
        }
        findNavController().navigate(R.id.detailsFragment, args)
    }

    private fun resumePlayback(item: Media) {
        val isDonghua = AnimarvleApp.isDonghua(item.title)
        val effectiveType = if (isDonghua) "ANIME" else HistoryRepository.sanitizeMediaType(
            item.mediaType ?: "ANIME",
            item.title,
            item.mediaUrl.orEmpty()
        )
        val isExplicitAnime = effectiveType.equals("ANIME", ignoreCase = true)
        val isMoviesTv = MediaTypeManager.getInstance(requireContext()).currentMode.value == AppMediaMode.MOVIES_TV
        val isTmdb = if (isExplicitAnime) false else (isMoviesTv || effectiveType.equals("MOVIE", ignoreCase = true) || effectiveType.equals("TV", ignoreCase = true))
        val id = item.originalId ?: item.mediaUrl?.toLongOrNull() ?: item.id.toLongOrNull() ?: 0L
        val args = Bundle().apply {
            putLong("sourceId", 0L)
            if (id > 0L) {
                putLong("mediaId", id)
            }
            val url = item.mediaUrl.orEmpty()
            val isBadDonghuaUrl = isDonghua && !url.contains("anichi", ignoreCase = true)
            if (!isBadDonghuaUrl && url.isNotBlank()) {
                putString("mediaUrl", url)
                putString("animeUrl", url)
            }
            putString("mediaTitle", item.title)
            putString("mediaType", effectiveType)
            putInt("episodeCount", 0)
            putInt("episodeNumber", item.episodeNumber ?: 1)
            putInt("seasonNumber", item.seasonNumber ?: 1)
            putString("audio", item.audio ?: "SUB")
            putString("poster", item.posterUrl)
            putString("animeTitle", item.title)
            putBoolean("preferTmdb", isTmdb)
        }
        findNavController().navigate(R.id.playerFragment, args)
    }

    private fun openAddToMyList(media: Media) {
        val id = media.originalId ?: media.id.toLongOrNull() ?: -1L
        val sheet = AddToMyListBottomSheet.newInstance(
            mediaId = id,
            title = media.title,
            posterUrl = media.posterUrl,
            format = media.tags.firstOrNull() ?: "ANIME",
            score = media.score,
            totalEpisodes = null,
            year = media.year?.toIntOrNull(),
        )
        sheet.show(childFragmentManager, AddToMyListBottomSheet.TAG)
    }

    private fun openCategoryInSearch(category: String) {
        val bundle = Bundle().apply {
            putString("category", category)
        }
        findNavController().navigate(R.id.searchFragment, bundle)
    }

    private fun openProviderCatalog(provider: ProviderLogo) {
        val bundle = Bundle().apply {
            putString("providerName", provider.name)
        }
        findNavController().navigate(R.id.providerCatalogFragment, bundle)
    }

    private fun startSkeletonAnimation() {
        _binding?.let { b ->
            b.skeletonContainer.isVisible = true
            b.feedRecyclerView.isVisible = false
            b.morphLoaderContainer.isVisible = false
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
            b.feedRecyclerView.isVisible = true
        }
    }

    override fun onDestroyView() {
        stopSkeletonAnimation()
        super.onDestroyView()
        _binding = null
    }
}