package com.animarvle.app.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentHomeBinding
import com.animarvle.app.databinding.ViewRailBinding
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager
import com.animarvle.app.ui.mylist.AddToMyListBottomSheet
import com.animarvle.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel
    private val posterAdapters = mutableListOf<PosterAdapter>()
    private var hasContent = false
    private var pulseAnimator: ObjectAnimator? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = (requireActivity().application as AnimarvleApp).container
        val mediaTypeManager = MediaTypeManager.getInstance(requireContext())
        viewModel = ViewModelProvider(
            this,
            HomeViewModel.Factory(container.metadata, container.history, mediaTypeManager),
        )[HomeViewModel::class.java]

        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        binding.swipe.setColorSchemeColors(AppTheme.accent(requireContext()))
        binding.swipe.setOnRefreshListener { viewModel.load() }
        binding.stateRetry.setOnClickListener { viewModel.load() }
        binding.mediaToggleButton.setOnClickListener {
            hasContent = false
            viewModel.toggleMode()
        }
        binding.searchButton.setOnClickListener {
            findNavController().navigate(R.id.searchFragment)
        }

        // Dynamic header background on scroll: seamlessly transparent over hero, dark solid when scrolled into rails
        binding.homeScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val maxScroll = dp(120).toFloat()
            val fraction = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
            val alphaInt = (fraction * 255).toInt()
            val bgColor = android.graphics.Color.argb(alphaInt, 15, 15, 20)
            binding.headerContainer.setBackgroundColor(bgColor)
        }

        val appContainer = (requireActivity().application as AnimarvleApp).container
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect(::render)
                }
                launch {
                    appContainer.myList.itemsFlow.collect { items ->
                        val savedIds = items.map { it.mediaId }.toSet()
                        posterAdapters.forEach { it.setSavedIds(savedIds) }
                        updateHeroMyListButton(items)
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun render(state: HomeState) {
        val mode = (state as? HomeState.Content)?.mode ?: viewModel.currentMode
        binding.mediaToggleButton.setImageResource(
            if (mode == AppMediaMode.ANIME) R.drawable.ic_mode_movie else R.drawable.ic_mode_anime
        )
        binding.mediaToggleButton.contentDescription = getString(
            if (mode == AppMediaMode.ANIME) R.string.tab_movies_tv else R.string.tab_anime
        )

        when (state) {
            is HomeState.Loading -> {
                binding.offlineContainer.isVisible = false
                binding.headerContainer.isVisible = true
                binding.stateBlock.isVisible = false
                if (!hasContent) {
                    binding.swipe.isVisible = false
                    binding.swipe.isRefreshing = false
                    startSkeletonAnimation()
                } else {
                    binding.skeletonContainer.isVisible = false
                    binding.swipe.isVisible = true
                    binding.swipe.isRefreshing = true
                }
            }

            is HomeState.Error -> {
                binding.swipe.isRefreshing = false
                stopSkeletonAnimation()
                if (!hasContent) {
                    binding.skeletonContainer.isVisible = false
                    binding.swipe.isVisible = false
                    binding.headerContainer.isVisible = false
                    binding.offlineContainer.isVisible = true
                    (activity as? com.animarvle.app.MainActivity)?.setBottomNavVisible(false)

                    val rawMsg = state.message.orEmpty()
                    val isNetworkError = rawMsg.contains("Unable to resolve host", ignoreCase = true) ||
                        rawMsg.contains("No address", ignoreCase = true) ||
                        rawMsg.contains("Failed to connect", ignoreCase = true) ||
                        rawMsg.contains("timeout", ignoreCase = true) ||
                        rawMsg.contains("hostname", ignoreCase = true) ||
                        rawMsg.isBlank()

                    binding.offlineTitle.setText(R.string.state_offline_title)
                    binding.offlineBody.text = if (isNetworkError) {
                        getString(R.string.state_offline_body)
                    } else {
                        rawMsg
                    }

                    binding.btnOfflineRetry.setOnClickListener {
                        binding.offlineContainer.isVisible = false
                        binding.headerContainer.isVisible = true
                        (activity as? com.animarvle.app.MainActivity)?.setBottomNavVisible(true)
                        viewModel.load()
                    }
                    binding.btnOfflineDownloads.setOnClickListener {
                        (activity as? com.animarvle.app.MainActivity)?.setBottomNavVisible(true)
                        (activity as? com.animarvle.app.MainActivity)?.navigateToTab(R.id.downloadFragment)
                    }
                    binding.rails.removeAllViews()
                } else {
                    Toast.makeText(
                        requireContext(),
                        state.message ?: getString(R.string.state_offline_body),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            is HomeState.Content -> {
                hasContent = true
                stopSkeletonAnimation()
                binding.offlineContainer.isVisible = false
                (activity as? com.animarvle.app.MainActivity)?.setBottomNavVisible(true)
                binding.headerContainer.isVisible = true
                binding.skeletonContainer.isVisible = false
                binding.swipe.isVisible = true
                binding.swipe.isRefreshing = false
                binding.stateBlock.isVisible = false
                binding.hero.isVisible = true
                val heroMedia = state.trending.firstOrNull()
                renderHero(heroMedia)
                renderRails(state.rails, state.continueWatching)
                binding.heroPlay.setOnClickListener { heroMedia?.let { openDetails(it) } }
                binding.hero.isClickable = true
                binding.hero.setOnClickListener { heroMedia?.let { openDetails(it) } }
                binding.heroMyList.setOnClickListener {
                    if (heroMedia != null) {
                        val sheet = AddToMyListBottomSheet.newInstance(
                            mediaId = heroMedia.id,
                            title = heroMedia.title,
                            posterUrl = heroMedia.coverUrl,
                            format = heroMedia.format,
                            score = heroMedia.score,
                            totalEpisodes = heroMedia.episodes,
                        )
                        sheet.show(childFragmentManager, AddToMyListBottomSheet.TAG)
                    }
                }
            }
        }
    }

    private fun startSkeletonAnimation() {
        binding.skeletonContainer.isVisible = true
        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(
                binding.skeletonContentLayout,
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

    private fun stopSkeletonAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        _binding?.let { b ->
            b.skeletonContentLayout.alpha = 1.0f
            b.skeletonContainer.isVisible = false
        }
    }

    private fun renderHero(media: MediaUi?) {
        binding.hero.isVisible = media != null
        if (media == null) return
        binding.heroImage.load(media.bannerUrl ?: media.coverUrl)
        binding.heroTitle.text = media.title
        binding.heroGenres.text = media.genres.take(6).joinToString(", ")
        val appContainer = (requireActivity().application as AnimarvleApp).container
        updateHeroMyListButton(appContainer.myList.itemsFlow.value)
    }

    private fun updateHeroMyListButton(items: List<com.animarvle.app.ui.mylist.MyListItem>) {
        val state = viewModel.state.value
        val heroMedia = (state as? HomeState.Content)?.trending?.firstOrNull() ?: return
        val saved = items.firstOrNull { it.mediaId == heroMedia.id }
        val accentColor = com.animarvle.app.ui.theme.AppTheme.accent(requireContext())
        binding.heroPlay.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        binding.heroPlay.setIconResource(R.drawable.ic_play_circle)
        binding.heroPlay.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        if (saved != null) {
            binding.heroMyList.text = "In List"
            binding.heroMyList.setTextColor(accentColor)
            binding.heroMyList.setIconResource(R.drawable.ic_check)
            binding.heroMyList.iconTint = android.content.res.ColorStateList.valueOf(accentColor)
        } else {
            binding.heroMyList.setText(R.string.schedule_add_my_list)
            binding.heroMyList.setTextColor(accentColor)
            binding.heroMyList.setIconResource(R.drawable.ic_add)
            binding.heroMyList.iconTint = android.content.res.ColorStateList.valueOf(accentColor)
        }
    }

    private fun renderRails(
        rails: List<HomeRail>,
        continueWatching: List<com.animarvle.app.ui.player.HistoryItem>,
    ) {
        binding.rails.removeAllViews()
        posterAdapters.clear()
        val appContainer = (requireActivity().application as AnimarvleApp).container
        val savedIds = appContainer.myList.itemsFlow.value.map { it.mediaId }.toSet()
        val inflater = LayoutInflater.from(requireContext())

        // Continue Watching rail (PROMPT §7.2)
        if (continueWatching.isNotEmpty()) {
            val railBinding = ViewRailBinding.inflate(inflater, binding.rails, false)
            railBinding.rowTitle.setText(R.string.row_continue_watching)
            railBinding.seeAll.isVisible = false
            railBinding.rail.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            val adapter = ContinueWatchingAdapter { item -> resumePlayback(item) }
            railBinding.rail.adapter = adapter
            adapter.submit(continueWatching)
            binding.rails.addView(railBinding.root)
        }

        rails.filter { it.items.isNotEmpty() }.forEach { rail ->
            val railBinding = ViewRailBinding.inflate(inflater, binding.rails, false)
            railBinding.rowTitle.setText(rail.titleRes)
            railBinding.seeAll.isVisible = true
            railBinding.seeAll.setOnClickListener {
                val bundle = Bundle().apply {
                    when (rail.titleRes) {
                        R.string.row_donghua -> putString("category", "Donghua")
                        R.string.row_cartoons -> putString("category", "Cartoons")
                        R.string.row_kdrama -> putString("category", "K-Drama")
                        R.string.row_cdrama -> putString("category", "C-Drama")
                        R.string.row_trending_movies, R.string.row_popular_movies, R.string.row_top_rated, R.string.row_now_playing -> putString("category", "Movies")
                        R.string.row_trending_tv, R.string.row_popular_tv -> putString("category", "TV")
                        else -> {}
                    }
                }
                findNavController().navigate(R.id.searchFragment, bundle)
            }
            railBinding.rail.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            val posterAdapter = PosterAdapter { openDetails(it) }
            posterAdapter.setSavedIds(savedIds)
            posterAdapter.submit(rail.items)
            posterAdapters.add(posterAdapter)
            railBinding.rail.adapter = posterAdapter
            binding.rails.addView(railBinding.root)
        }
    }

    private fun resumePlayback(item: com.animarvle.app.ui.player.HistoryItem) {
        val isDonghua = AnimarvleApp.isDonghua(item.title)
        val effectiveType = if (isDonghua) "ANIME" else item.mediaType
        val args = Bundle().apply {
            putLong("sourceId", 0L)
            val isBadDonghuaUrl = isDonghua && !item.mediaUrl.contains("anichi", ignoreCase = true)
            if (!isBadDonghuaUrl && item.mediaUrl.isNotBlank()) {
                putString("mediaUrl", item.mediaUrl)
                putString("animeUrl", item.mediaUrl)
            }
            putString("mediaTitle", item.title)
            putString("mediaType", effectiveType)
            putInt("episodeCount", 0)
            putInt("episodeNumber", item.episodeNumber)
            putString("audio", item.audio)
            putString("poster", item.posterUrl)
            putString("animeTitle", item.title)
        }
        findNavController().navigate(R.id.playerFragment, args)
    }

    private fun openDetails(media: MediaUi) {
        val isMovie = media.format.equals("MOVIE", ignoreCase = true)
        openDetails(media.id, if (isMovie) "movie" else "tv")
    }

    private fun openDetails(id: Long, mediaType: String? = null) {
        val args = Bundle().apply {
            putLong("mediaId", id)
            if (mediaType != null) putString("mediaType", mediaType)
        }
        findNavController().navigate(R.id.detailFragment, args)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null && binding.offlineContainer.isVisible) {
            (activity as? com.animarvle.app.MainActivity)?.setBottomNavVisible(false)
        }
    }

    override fun onDestroyView() {
        (activity as? com.animarvle.app.MainActivity)?.setBottomNavVisible(true)
        super.onDestroyView()
        stopSkeletonAnimation()
        _binding = null
    }
}