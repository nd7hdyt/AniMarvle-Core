package com.animarvle.app.ui.details

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager
import com.animarvle.app.databinding.FragmentDetailsBinding
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.ui.detail.BatchDownloadBottomSheet
import com.animarvle.app.ui.detail.ChooseQualityBottomSheet
import com.animarvle.app.ui.detail.DetailEpisodeAdapter
import com.animarvle.app.ui.detail.DetailEpisodeUi
import com.animarvle.app.ui.detail.DetailState
import com.animarvle.app.ui.detail.DetailViewModel
import com.animarvle.app.ui.detail.QualityOption
import com.animarvle.app.ui.home.HorizontalMediaAdapter
import com.animarvle.app.ui.home.Media
import com.animarvle.app.ui.home.toMedia
import com.animarvle.app.ui.mylist.AddToMyListBottomSheet
import com.animarvle.app.ui.player.HistoryItem
import com.animarvle.app.ui.player.VortexBlurHelper
import com.animarvle.app.ui.theme.AppTheme
import com.animarvle.domain.MatchResult
import com.animarvle.domain.MetaQuery
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.Titles
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class DetailsFragment : Fragment() {

    private var _binding: FragmentDetailsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DetailViewModel
    private var currentMedia: MediaUi? = null
    private var selectedSeasonNumber: Int = 1
    private var selectedSeasonIndex: Int = 0
    private var synopsisExpanded = false
    private var pulseAnimator: ObjectAnimator? = null

    private val episodeAdapter = DetailEpisodeAdapter { epNum -> playCurrent(epNum) }
    private val recommendationsAdapter = HorizontalMediaAdapter { media -> openMedia(media) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mediaId = arguments?.getLong("mediaId") ?: -1L
        val mediaTypeArg = arguments?.getString("mediaType")
        val app = requireActivity().application as AnimarvleApp
        val isExplicitAnime = mediaTypeArg?.equals("anime", ignoreCase = true) == true
        val preferTmdb = if (arguments?.containsKey("preferTmdb") == true) {
            arguments?.getBoolean("preferTmdb", false) ?: false
        } else if (isExplicitAnime) {
            false
        } else {
            MediaTypeManager.getInstance(requireContext()).currentMode.value == AppMediaMode.MOVIES_TV
        }

        viewModel = ViewModelProvider(
            this,
            DetailViewModel.Factory(app.container.metadata, mediaId, preferTmdb, mediaTypeArg)
        )[DetailViewModel::class.java]

        // 1. Edge-to-Edge: Apply status bar insets to statusBarSpacer
        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallbackTop = if (statusBarResId > 0) resources.getDimensionPixelSize(statusBarResId) else 0
        if (fallbackTop > 0) {
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = fallbackTop
            }
        }
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

        setupRecyclerViews()
        setupFloatingHeader()
        setupActionButtons()
        startSkeletonAnimation()

        fun updateHeaderBackground(fraction: Float) {
            val f = fraction.coerceIn(0f, 1f)
            val topAlpha = (110 + (255 - 110) * f).toInt().coerceIn(0, 255)
            val bottomAlpha = (255 * f).toInt().coerceIn(0, 255)
            val gradient = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(topAlpha, 0, 0, 0),
                    Color.argb(bottomAlpha, 0, 0, 0)
                )
            )
            binding.headerContainer.background = gradient
        }

        // Initial header and poster scrim render
        binding.headerContainer.post {
            if (_binding != null) {
                binding.heroContainer.translationY = 0f
                binding.tvHeaderTitle.alpha = 0f
                binding.heroDimOverlay.alpha = 0f
                updateHeaderBackground(0f)
            }
        }

        // Scroll listener:
        // 1. Poster Parallax: moves upwards way slower than the content scroll (~35% speed)
        // 2. Dims poster as user scrolls down into dark shadows
        // 3. Fades header from subtle linear pre-scrim into solid black bar (#000000)
        // 4. Fades in header title as main title scrolls under the toolbar
        binding.detailsScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // 1. Poster Parallax Movement (way slower than scroll movement)
            val parallaxFactor = 0.35f
            binding.heroContainer.translationY = -(scrollY.coerceAtLeast(0) * parallaxFactor)

            // 2. Poster Dimming on scroll
            val maxDimPx = 280 * resources.displayMetrics.density
            val dimFraction = (scrollY.toFloat() / maxDimPx).coerceIn(0f, 1f)
            binding.heroDimOverlay.alpha = dimFraction * 0.75f

            // 3. Header Bar Background: transitions from subtle linear pre-scrim to solid black bar
            // Extended threshold (220dp) makes the transition to solid black way slower and smoother
            val headerThreshold = (220 * resources.displayMetrics.density).coerceAtLeast(1f)
            val headerFraction = (scrollY.toFloat() / headerThreshold).coerceIn(0f, 1f)
            updateHeaderBackground(headerFraction)

            // 4. Header Title Fade: appears when in-page title moves under header
            val headerHeight = binding.headerContainer.height
            if (headerHeight > 0) {
                val titleLoc = IntArray(2)
                val headerLoc = IntArray(2)
                binding.tvTitle.getLocationOnScreen(titleLoc)
                binding.headerContainer.getLocationOnScreen(headerLoc)
                val headerBottom = headerLoc[1] + headerHeight

                val titleHeight = binding.tvTitle.height.coerceAtLeast(1)
                if (titleLoc[1] >= headerBottom) {
                    binding.tvHeaderTitle.alpha = 0f
                } else if (titleLoc[1] <= headerLoc[1]) {
                    binding.tvHeaderTitle.alpha = 1f
                } else {
                    val progress = (headerBottom - titleLoc[1]).toFloat() / titleHeight.toFloat()
                    binding.tvHeaderTitle.alpha = progress.coerceIn(0f, 1f)
                }
            }
        }

        // Observe ViewModel state and repositories
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect(::renderState)
                }
                launch {
                    app.container.history.historyFlow.collect { historyList ->
                        updateEpisodesAndPlayButton(historyList)
                    }
                }
                launch {
                    app.container.myList.itemsFlow.collect { mylistItems ->
                        updateBookmarkState(mylistItems)
                    }
                }
            }
        }
    }

    private fun setupRecyclerViews() {
        binding.rvEpisodes.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvEpisodes.adapter = episodeAdapter

        binding.rvTrailers.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvTrailers.adapter = recommendationsAdapter
    }

    private fun setupFloatingHeader() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnDownload.setOnClickListener {
            downloadCurrent()
        }

        binding.btnMyList.setOnClickListener {
            val media = currentMedia ?: return@setOnClickListener
            val sheet = AddToMyListBottomSheet.newInstance(
                mediaId = media.id,
                title = media.title,
                posterUrl = media.coverUrl,
                format = media.format,
                score = media.score,
                totalEpisodes = media.episodes,
                year = media.year,
            )
            sheet.show(childFragmentManager, AddToMyListBottomSheet.TAG)
        }

        binding.btnShare.setOnClickListener {
            val media = currentMedia ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                val text = getString(R.string.detail_share_text, media.title)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, media.title)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.details_share)))
        }
    }

    private fun setupActionButtons() {
        binding.btnPlay.setOnClickListener {
            val media = currentMedia ?: return@setOnClickListener
            val appContainer = (requireActivity().application as AnimarvleApp).container
            val matchingHistory = appContainer.history.historyFlow.value
                .filter { it.title.equals(media.title, ignoreCase = true) }
            val latest = matchingHistory.maxByOrNull { it.updatedAtMs }
            val resumeEp = latest?.episodeNumber ?: 1
            if (latest?.seasonNumber != null && latest.seasonNumber > 0) {
                selectedSeasonNumber = latest.seasonNumber
            }
            playCurrent(resumeEp)
        }

        // Expand synopsis on click
        binding.tvSynopsis.setOnClickListener {
            synopsisExpanded = !synopsisExpanded
            binding.tvSynopsis.maxLines = if (synopsisExpanded) Integer.MAX_VALUE else 4
        }
    }

    private fun renderState(state: DetailState) {
        when (state) {
            is DetailState.Loading -> {
                startSkeletonAnimation()
            }
            is DetailState.Error -> {
                stopSkeletonAnimation()
                Toast.makeText(
                    requireContext(),
                    state.message ?: getString(R.string.state_offline_body),
                    Toast.LENGTH_SHORT
                ).show()
            }
            is DetailState.Content -> {
                stopSkeletonAnimation()
                bindMedia(state.media, state.recommendations)
            }
        }
    }

    private fun startSkeletonAnimation() {
        _binding?.let { b ->
            b.skeletonContainer.isVisible = true
            b.detailsScrollView.isVisible = false
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
            b.detailsScrollView.isVisible = true
        }
    }

    private fun bindMedia(media: MediaUi, recommendations: List<MediaUi>) {
        currentMedia = media

        // Hero backdrop
        val heroUrl = media.bannerUrl ?: media.coverUrl
        if (!heroUrl.isNullOrBlank()) {
            binding.heroBackdrop.load(heroUrl)
        }

        // Title
        binding.tvTitle.text = media.title
        binding.tvHeaderTitle.text = media.title

        // Metadata row (Matches Image 1 & 2: Series: Drama • 2026 • 23 Seasons • ★ 8.2 / Movie: 2026 • 145 min • Action • ★ 8.2)
        val metaParts = mutableListOf<String>()
        val (_, isMovie) = resolveMediaType(media)
        if (!isMovie) {
            media.genres.firstOrNull()?.let { metaParts.add(it) }
            media.year?.let { metaParts.add(it.toString()) }
            val count = media.seasons.size
            if (count > 1) {
                metaParts.add("$count Seasons")
            } else if (count == 1) {
                metaParts.add("1 Season")
            }
            media.score?.let { metaParts.add(String.format(Locale.US, "★ %.1f", it)) }
        } else {
            media.year?.let { metaParts.add(it.toString()) }
            runtimeLabel(media.durationMinutes)?.let { metaParts.add(it) }
            media.genres.firstOrNull()?.let { metaParts.add(it) }
            media.score?.let { metaParts.add(String.format(Locale.US, "★ %.1f", it)) }
        }
        binding.tvMetadata.text = metaParts.joinToString(" • ")

        // Dynamic Genre Chips
        binding.chipGroupGenres.removeAllViews()
        for (genre in media.genres) {
            val chip = Chip(requireContext()).apply {
                text = genre
                setTextColor(Color.WHITE)
                chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#2C2C2E"))
                chipStrokeWidth = 0f
                isClickable = false
            }
            binding.chipGroupGenres.addView(chip)
        }

        // Synopsis
        binding.tvSynopsis.text = media.synopsis ?: ""

        // Recommendations
        if (recommendations.isNotEmpty()) {
            recommendationsAdapter.submitList(recommendations.map { it.toMedia() })
            binding.rvTrailers.isVisible = true
        } else {
            binding.rvTrailers.isVisible = false
        }

        // Series vs Movie Setup
        if (!isMovie) {
            binding.seriesBlock.isVisible = true
            setupSeasons(media)
        } else {
            binding.seriesBlock.isVisible = false
        }

        val appContainer = (requireActivity().application as AnimarvleApp).container
        updateEpisodesAndPlayButton(appContainer.history.historyFlow.value)
        updateBookmarkState(appContainer.myList.itemsFlow.value)
    }

    private fun setupSeasons(media: MediaUi) {
        val seasons = media.seasons
        if (seasons.isNotEmpty()) {
            val seasonTitles = seasons.mapIndexed { idx, s ->
                val sNum = s.seasonNumber ?: (idx + 1)
                if (s.title.isNotBlank()) {
                    if (s.title.startsWith("Season", ignoreCase = true)) s.title
                    else "Season $sNum: ${s.title}"
                } else {
                    "Season $sNum"
                }
            }
            val initialIdx = seasons.indexOfFirst { it.isCurrent || it.id == media.id }.coerceAtLeast(0)
            selectedSeasonIndex = initialIdx
            val currentSeasonObj = seasons.getOrNull(selectedSeasonIndex)
            val sNum = currentSeasonObj?.seasonNumber ?: (selectedSeasonIndex + 1)
            selectedSeasonNumber = sNum
            binding.tvCurrentSeason.text = seasonTitles.getOrElse(selectedSeasonIndex) { "Season $sNum" }

            binding.btnSeasonSelector.setOnClickListener {
                SeasonSelectorBottomSheet.show(childFragmentManager, seasonTitles, selectedSeasonIndex) { selectedIdx ->
                    if (selectedIdx == selectedSeasonIndex) return@show
                    selectedSeasonIndex = selectedIdx
                    val chosen = seasons.getOrNull(selectedIdx)
                    val newSeasonNum = chosen?.seasonNumber ?: (selectedIdx + 1)
                    selectedSeasonNumber = newSeasonNum
                    binding.tvCurrentSeason.text = seasonTitles.getOrElse(selectedIdx) { "Season $newSeasonNum" }
                    val appContainer = (requireActivity().application as AnimarvleApp).container
                    updateEpisodesAndPlayButton(appContainer.history.historyFlow.value)
                }
            }
        } else {
            binding.tvCurrentSeason.text = "Season 1"
            selectedSeasonNumber = 1
            selectedSeasonIndex = 0
        }
    }

    private fun updateEpisodesAndPlayButton(historyList: List<HistoryItem>) {
        val media = currentMedia ?: return
        val (_, isMovie) = resolveMediaType(media)
        val matchingHistory = historyList.filter { it.title.equals(media.title, ignoreCase = true) }
        val latestHistory = matchingHistory.maxByOrNull { it.updatedAtMs }

        // Update CTA Play Button
        if (latestHistory != null) {
            if (isMovie) {
                binding.btnPlay.text = "Continue"
            } else if (latestHistory.episodeNumber > 0) {
                binding.btnPlay.text = "Resume Ep ${latestHistory.episodeNumber}"
            } else {
                binding.btnPlay.text = "Play"
            }
        } else {
            val defaultText = if (isMovie) "Play" else "Play First Episode"
            binding.btnPlay.text = defaultText
        }

        if (isMovie) {
            binding.seriesBlock.isVisible = false
            return
        }

        binding.seriesBlock.isVisible = true

        val seasonObj = media.seasons.getOrNull(selectedSeasonIndex)
        val targetMediaId = if (seasonObj != null && seasonObj.id > 0 && media.seasons.any { it.id != media.id }) {
            seasonObj.id
        } else {
            media.id
        }
        val targetTitle = if (seasonObj != null && seasonObj.id != media.id && seasonObj.title.isNotBlank()) {
            seasonObj.title
        } else {
            media.title
        }
        val targetSeasonNumber = seasonObj?.seasonNumber ?: (selectedSeasonIndex + 1)
        val seasonEpCount = seasonObj?.episodeCount
        val totalEps = seasonEpCount
            ?: (if (selectedSeasonIndex == 0) media.episodes else null)
            ?: media.nextAiringEpisode?.let { (it - 1).coerceAtLeast(1) }
            ?: 12

        val episodeItems = (1..totalEps).map { epNum ->
            val hist = matchingHistory.firstOrNull { it.seasonNumber == targetSeasonNumber && it.episodeNumber == epNum }
                ?: matchingHistory.firstOrNull { it.episodeNumber == epNum }
            DetailEpisodeUi(
                episodeNumber = epNum,
                title = "Episode $epNum",
                thumbnailUrl = seasonObj?.posterUrl ?: media.bannerUrl ?: media.coverUrl,
                history = hist,
                durationMinutes = media.durationMinutes,
            )
        }
        episodeAdapter.submitList(episodeItems)

        // Enrich with real episode titles/metadata asynchronously
        val appContainer = (requireActivity().application as AnimarvleApp).container
        viewLifecycleOwner.lifecycleScope.launch {
            val preferTmdb = media.isTmdb
            val isAdult = media.genres.any { it.equals("Hentai", ignoreCase = true) } ||
                com.animarvle.app.data.MetadataRepository.isAdultTitle(targetTitle) ||
                com.animarvle.app.data.MetadataRepository.isAdultTitle(media.romajiTitle)
            val metaMap = runCatching {
                appContainer.metadata.episodeMetadata(
                    mediaId = targetMediaId,
                    title = targetTitle,
                    seasonNumber = targetSeasonNumber,
                    isTmdb = preferTmdb,
                    isAdult = isAdult,
                    romaji = media.romajiTitle,
                )
            }.getOrDefault(emptyMap())

            if (metaMap.isNotEmpty() && currentMedia?.id == media.id) {
                val maxEp = maxOf(totalEps, metaMap.keys.maxOrNull() ?: totalEps)
                val updatedItems = (1..maxEp).map { epNum ->
                    val hist = matchingHistory.firstOrNull { it.seasonNumber == targetSeasonNumber && it.episodeNumber == epNum }
                        ?: matchingHistory.firstOrNull { it.episodeNumber == epNum }
                    val meta = metaMap[epNum]
                    DetailEpisodeUi(
                        episodeNumber = epNum,
                        title = meta?.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum",
                        thumbnailUrl = meta?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: (seasonObj?.posterUrl ?: media.bannerUrl ?: media.coverUrl),
                        history = hist,
                        durationMinutes = media.durationMinutes,
                    )
                }
                episodeAdapter.submitList(updatedItems)
            }
        }
    }

    private fun updateBookmarkState(items: List<com.animarvle.app.ui.mylist.MyListItem>) {
        val media = currentMedia ?: return
        val saved = items.firstOrNull { it.mediaId == media.id }
        val accentColor = AppTheme.accent(requireContext())
        if (saved != null) {
            binding.btnMyList.setImageResource(R.drawable.ic_bookmark_filled)
            binding.btnMyList.imageTintList = ColorStateList.valueOf(accentColor)
        } else {
            binding.btnMyList.setImageResource(R.drawable.ic_bookmark_outline)
            binding.btnMyList.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
    }

    /**
     * Resolves sources and launches PlayerFragment with real scraping engine arguments.
     */
    private fun playCurrent(targetEpisodeNumber: Int = 1) {
        val media = currentMedia ?: return
        val (type, isMovie) = resolveMediaType(media)
        val seasonObj = media.seasons.getOrNull(selectedSeasonIndex)
        val targetMediaId = if (seasonObj != null && seasonObj.id > 0 && media.seasons.any { it.id != media.id }) {
            seasonObj.id
        } else {
            media.id
        }
        val targetTitle = if (seasonObj != null && seasonObj.id != media.id && seasonObj.title.isNotBlank()) {
            seasonObj.title
        } else {
            media.title
        }
        val targetSeasonNumber = seasonObj?.seasonNumber ?: (selectedSeasonIndex + 1)

        val appContainer = (requireActivity().application as AnimarvleApp).container
        val isDonghua = AnimarvleApp.isDonghua(targetTitle)
        val candidateBound = appContainer.sourceBindings.getBoundSourceId(targetMediaId)
        val sources = appContainer.sourcesFor(type, targetTitle)
        val boundSourceId: Long? = if (isDonghua) {
            val isCandidateValid = sources.any { s -> s.id == candidateBound && (s.name.contains("anichi", ignoreCase = true) || s.name.contains("kisskh", ignoreCase = true)) }
            if (isCandidateValid) candidateBound else sources.firstOrNull()?.id
        } else {
            candidateBound ?: sources.firstOrNull()?.id
        }

        val args = Bundle().apply {
            boundSourceId?.let { putLong("sourceId", it) }
            putLong("mediaId", targetMediaId)
            putString("mediaTitle", targetTitle)
            putString("romajiTitle", media.romajiTitle)
            putString("nativeTitle", media.nativeTitle)
            putStringArrayList("synonyms", ArrayList(media.synonyms))
            val preferTmdb = media.isTmdb
            val effectiveMediaType = when {
                isDonghua -> "ANIME"
                preferTmdb -> if (isMovie) "MOVIE" else if (type == MediaType.ANIME) "ANIME" else "TV"
                type == MediaType.MOVIE -> "MOVIE"
                else -> "ANIME"
            }
            putString("mediaType", effectiveMediaType)
            putInt("year", media.year ?: 0)
            val eps = seasonObj?.episodeCount ?: media.episodes ?: media.nextAiringEpisode?.let { (it - 1).coerceAtLeast(1) } ?: 0
            putInt("episodeCount", eps)
            putInt("seasonNumber", targetSeasonNumber)
            putInt("episodeNumber", targetEpisodeNumber)
            putInt("startEpisodeNumber", seasonObj?.startEpisodeNumber ?: 1)
            val defaultDub = requireContext().getSharedPreferences(
                com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            ).getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_DEFAULT_DUB, false)
            putString("audio", if (defaultDub) "DUB" else "SUB")
            putString("poster", seasonObj?.posterUrl ?: media.coverUrl ?: media.bannerUrl)
            putString("animeTitle", targetTitle)
            if (media.seasons.isNotEmpty()) {
                putSerializable("seasons", ArrayList(media.seasons))
            }
            putBoolean("preferTmdb", preferTmdb)
            putBoolean("isMovie", isMovie)
        }
        findNavController().navigate(R.id.playerFragment, args)
    }

    private fun openMedia(media: Media) {
        val id = media.originalId ?: media.id.toLongOrNull() ?: -1L
        val isAnimeMedia = media.mediaType?.equals("ANIME", ignoreCase = true) == true
        val isMoviesTv = if (isAnimeMedia) {
            false
        } else if (arguments?.containsKey("preferTmdb") == true) {
            arguments?.getBoolean("preferTmdb", false) ?: false
        } else {
            MediaTypeManager.getInstance(requireContext()).currentMode.value == AppMediaMode.MOVIES_TV
        }
        val args = Bundle().apply {
            putLong("mediaId", id)
            putString("mediaType", media.mediaType ?: "ANIME")
            putBoolean("preferTmdb", isMoviesTv)
        }
        findNavController().navigate(R.id.detailsFragment, args)
    }

    private fun downloadCurrent() {
        val media = currentMedia ?: return
        val totalEps = media.episodes ?: 1
        val (_, isMovie) = resolveMediaType(media)

        if (isMovie || totalEps <= 1) {
            showQualityPicker(listOf(1))
        } else {
            val sheet = BatchDownloadBottomSheet.newInstance(totalEps)
            sheet.setOnConfirmListener { epNumbers ->
                showQualityPicker(epNumbers)
            }
            sheet.show(childFragmentManager, BatchDownloadBottomSheet.TAG)
        }
    }

    private fun showQualityPicker(epNumbers: List<Int>) {
        val media = currentMedia ?: return
        val (type, _) = resolveMediaType(media)
        val qualitySheet = ChooseQualityBottomSheet.newInstance(
            mediaId = media.id,
            mediaTitle = media.title,
            posterUrl = media.coverUrl ?: media.bannerUrl,
            episodes = epNumbers,
            mediaType = type,
            year = media.year,
            durationMinutes = media.durationMinutes,
        )
        qualitySheet.setOnConfirmListener { option, episodes ->
            enqueueEpisodes(episodes, option)
        }
        qualitySheet.show(childFragmentManager, ChooseQualityBottomSheet.TAG)
    }

    private fun enqueueEpisodes(epNumbers: List<Int>, option: QualityOption? = null) {
        val media = currentMedia ?: return
        val container = (requireActivity().application as AnimarvleApp).container
        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnDownload.isEnabled = false
            val (type, _) = resolveMediaType(media)
            val meta = MetaQuery(
                metaId = "anilist:${media.id}",
                type = type,
                titles = Titles(
                    english = media.title,
                    romaji = media.romajiTitle,
                    native = media.nativeTitle,
                ),
                synonyms = media.synonyms,
                year = media.year,
                episodeCount = media.episodes,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeoutOrNull(8000L) { container.matcher.match(meta, container.sourcesFor(type)) }
                }.getOrNull()
            } ?: MatchResult.NoMatch
            binding.btnDownload.isEnabled = true
            when (result) {
                is MatchResult.Matched -> {
                    val source = container.sourcesFor(type).firstOrNull { it.id == result.sourceId }
                        ?: container.sourcesFor(type).firstOrNull()

                    val sourceEpisodes = if (source != null && epNumbers.size > 1) {
                        withContext(Dispatchers.IO) {
                            runCatching { source.episodes(result.media) }.getOrNull()
                        }.orEmpty()
                    } else {
                        emptyList()
                    }

                    epNumbers.forEach { epNum ->
                        val epTitle = "Episode $epNum"
                        val epStreamUrl: String = if (epNum == epNumbers.first() && !option?.streamUrl.isNullOrBlank()) {
                            option!!.streamUrl
                        } else if (source != null) {
                            val ep = sourceEpisodes.firstOrNull { it.number.toInt() == epNum }
                            if (ep != null) {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val choice = com.animarvle.sourceapi.Choice(option?.audio ?: com.animarvle.sourceapi.Audio.SUB, option?.serverKey.orEmpty())
                                        val resolved = source.resolve(ep, choice)
                                        resolved.streams.firstOrNull {
                                            it.qualityLabel.equals(option?.qualityBadge, ignoreCase = true)
                                        }?.url ?: resolved.streams.firstOrNull()?.url
                                    }.getOrNull()
                                } ?: option?.streamUrl.orEmpty()
                            } else {
                                option?.streamUrl.orEmpty()
                            }
                        } else {
                            option?.streamUrl.orEmpty()
                        }

                        if (epStreamUrl.isNotBlank()) {
                            val quality = option?.qualityBadge ?: "AUTO"
                            val serverName = option?.serverName
                            val language = option?.language ?: "English"
                            container.downloads.enqueue(
                                id = "${media.id}_ep$epNum",
                                mediaId = media.id,
                                mediaTitle = media.title,
                                episodeTitle = epTitle,
                                episodeNumber = epNum,
                                posterUrl = media.coverUrl ?: result.media.poster,
                                streamUrl = epStreamUrl,
                                totalBytes = option?.estimatedBytes ?: 0L,
                                quality = quality,
                                serverName = serverName,
                                language = language,
                            )
                        }
                    }
                    if (epNumbers.size == 1) {
                        Toast.makeText(requireContext(), getString(R.string.download_queued_toast, "${media.title} Episode ${epNumbers.first()}"), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.download_batch_queued_toast, epNumbers.size), Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    Toast.makeText(requireContext(), R.string.state_offline_body, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveMediaType(media: MediaUi?): Pair<MediaType, Boolean> {
        val isDonghuaMedia = AnimarvleApp.isDonghua(media?.title) ||
            media?.genres?.any { it.contains("donghua", ignoreCase = true) } == true
        val isAnimation = media?.genres?.any { it.equals("Animation", ignoreCase = true) } == true
        val isTmdbMedia = media?.isTmdb == true || (media == null && (arguments?.getBoolean("preferTmdb", false) ?: false))
        val isMovieFormat = media?.format?.contains("MOVIE", ignoreCase = true) == true ||
            media?.format?.contains("FILM", ignoreCase = true) == true
        val isMovie = isMovieFormat || (isTmdbMedia && !isAnimation && !isDonghuaMedia && (media?.episodes ?: 1) <= 1 && media?.seasons.isNullOrEmpty())
        val type = if (isDonghuaMedia || isAnimation) {
            MediaType.ANIME
        } else if (isMovie || isTmdbMedia) {
            MediaType.MOVIE
        } else {
            MediaType.ANIME
        }
        return type to isMovie
    }

    private fun runtimeLabel(minutes: Int?): String? =
        minutes?.takeIf { it > 0 }?.let {
            if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m"
        }

    override fun onDestroyView() {
        stopSkeletonAnimation()
        super.onDestroyView()
        _binding = null
    }
}
