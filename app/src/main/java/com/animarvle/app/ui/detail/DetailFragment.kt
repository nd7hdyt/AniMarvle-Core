package com.animarvle.app.ui.detail

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentDetailBinding
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.domain.SeasonUi
import com.animarvle.app.ui.home.PosterAdapter
import com.animarvle.app.extensions.TachiyomiAnimeSource
import com.animarvle.app.extensions.TachiyomiAnimeSource.Companion.KEY_ANIME_URL
import com.animarvle.app.ui.theme.AppTheme
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.domain.MatchResult
import com.animarvle.domain.MetaQuery
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.Titles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Details screen (PROMPT §7.6). M2 renders metadata only; the Episodes block
 * shows the "Install a source" state until sources ship in M5.
 */
class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: DetailViewModel
    private val recommendationsAdapter = PosterAdapter { media -> open(media) }
    private var selectedSeasonNumber: Int = 1
    private val seasonAdapter = DetailSeasonAdapter { season ->
        if (season.seasonNumber != null) {
            onSeasonSelected(season)
        } else {
            open(season.id, "tv")
        }
    }
    private val episodeAdapter = DetailEpisodeAdapter { epNum -> playCurrent(epNum) }
    private var synopsisExpanded = false
    private var current: MediaUi? = null
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mediaId = arguments?.getLong("mediaId") ?: -1L
        val mediaType = arguments?.getString("mediaType")
        val repo = (requireActivity().application as AnimarvleApp).container.metadata
        val isExplicitAnime = mediaType?.equals("anime", ignoreCase = true) == true
        val preferTmdb = if (arguments?.containsKey("preferTmdb") == true) {
            arguments?.getBoolean("preferTmdb", false) ?: false
        } else if (isExplicitAnime) {
            false
        } else {
            com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
                .currentMode.value == com.animarvle.app.data.AppMediaMode.MOVIES_TV
        }
        viewModel = ViewModelProvider(this, DetailViewModel.Factory(repo, mediaId, preferTmdb, mediaType))[DetailViewModel::class.java]

        binding.recommendations.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.recommendations.adapter = recommendationsAdapter

        binding.seasonsList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.seasonsList.adapter = seasonAdapter

        binding.episodesList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.episodesList.adapter = episodeAdapter

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            binding.statusBarSpacer.layoutParams.height = statusBarInsets.top
            binding.statusBarSpacer.requestLayout()
            insets
        }

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.castButton.setOnClickListener {
            val sheet = com.animarvle.app.ui.player.CastBottomSheet.newInstance()
            sheet.show(childFragmentManager, com.animarvle.app.ui.player.CastBottomSheet.TAG)
        }
        binding.viewMore.setOnClickListener { toggleSynopsis() }
        binding.stateRetry.setOnClickListener { viewModel.load() }
        binding.tabMoreLikeThis.setOnClickListener { selectTab(moreLikeThis = true) }
        binding.tabComments.setOnClickListener { selectTab(moreLikeThis = false) }
        binding.bookmark.setOnClickListener {
            val state = viewModel.state.value
            if (state is DetailState.Content) {
                val media = state.media
                val sheet = com.animarvle.app.ui.mylist.AddToMyListBottomSheet.newInstance(
                    mediaId = media.id,
                    title = media.title,
                    posterUrl = media.coverUrl,
                    format = media.format,
                    score = media.score,
                    totalEpisodes = media.episodes,
                    year = media.year,
                )
                sheet.show(childFragmentManager, com.animarvle.app.ui.mylist.AddToMyListBottomSheet.TAG)
            } else {
                toast(R.string.details_save)
            }
        }
        binding.share.setOnClickListener {
            val media = current ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                val text = getString(R.string.detail_share_text, media.title)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, media.title)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.details_share)))
        }
        binding.playButton.setOnClickListener {
            val media = current
            val appContainer = (requireActivity().application as AnimarvleApp).container
            val matchingHistory = appContainer.history.historyFlow.value
                .filter { it.title.equals(media?.title, ignoreCase = true) }
            val latest = matchingHistory.maxByOrNull { it.updatedAtMs }
            val resumeEp = latest?.episodeNumber ?: 1
            if (latest?.seasonNumber != null && latest.seasonNumber > 0) {
                selectedSeasonNumber = latest.seasonNumber
            }
            playCurrent(resumeEp)
        }
        binding.downloadButton.setOnClickListener { downloadCurrent() }
        binding.installSource.setOnClickListener {
            findNavController().navigate(R.id.extensionReposFragment)
        }
        binding.searchEpPill.setOnClickListener { showEpisodeSearchDialog() }

        binding.contentScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // Banner Parallax: bannerImage moves way slower than scroll movement (~35% speed)
            binding.bannerImage.translationY = scrollY.coerceAtLeast(0) * 0.65f
        }

        val appContainer = (requireActivity().application as AnimarvleApp).container
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect(::render)
                }
                launch {
                    appContainer.history.historyFlow.collect { historyList ->
                        updateEpisodesAndPlayButton(historyList)
                    }
                }
                launch {
                    appContainer.myList.itemsFlow.collect { mylistItems ->
                        updateBookmarkState(mylistItems)
                    }
                }
            }
        }
    }

    private fun render(state: DetailState) {
        when (state) {
            is DetailState.Loading -> {
                binding.stateBlock.isVisible = false
                startSkeletonAnimation()
            }

            is DetailState.Error -> {
                stopSkeletonAnimation()
                binding.contentScrollView.isVisible = true
                binding.stateBlock.isVisible = true
                binding.stateTitle.setText(R.string.state_offline_title)
                val raw = state.message.orEmpty()
                val isNetwork = raw.contains("Unable to resolve host", ignoreCase = true) ||
                    raw.contains("No address", ignoreCase = true) ||
                    raw.contains("Failed to connect", ignoreCase = true) ||
                    raw.contains("timeout", ignoreCase = true) ||
                    raw.contains("hostname", ignoreCase = true) ||
                    raw.isBlank()
                binding.stateBody.text = if (isNetwork) getString(R.string.state_offline_body) else raw
            }

            is DetailState.Content -> {
                stopSkeletonAnimation()
                binding.stateBlock.isVisible = false
                binding.contentScrollView.isVisible = true
                bind(state.media)
                recommendationsAdapter.submit(state.recommendations)
            }
        }
    }

    private fun startSkeletonAnimation() {
        binding.skeletonContainer.isVisible = true
        binding.contentScrollView.isVisible = false
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

    private fun bind(media: MediaUi) {
        current = media
        binding.bannerImage.load(media.bannerUrl ?: media.coverUrl)
        binding.title.text = media.title

        val score = media.score
        binding.metaScore.isVisible = score != null
        binding.metaScore.text = score?.let { String.format(Locale.US, "★ %.1f", it) } ?: ""
        binding.metaDot.isVisible = score != null && media.year != null
        binding.metaYear.text = media.year?.toString() ?: ""
        binding.metaYear.isVisible = media.year != null

        binding.badgeFormat.text = media.format?.replace('_', ' ') ?: ""
        binding.badgeFormat.isVisible = !media.format.isNullOrBlank()

        val runtime = runtimeLabel(media.durationMinutes)
        binding.badgeRuntime.text = runtime ?: ""
        binding.badgeRuntime.isVisible = runtime != null

        binding.genres.text = getString(R.string.details_genre_prefix, media.genres.joinToString(", "))
        binding.genres.isVisible = media.genres.isNotEmpty()

        val hasSynopsis = !media.synopsis.isNullOrBlank()
        binding.synopsis.text = media.synopsis ?: ""
        binding.synopsis.isVisible = hasSynopsis
        binding.viewMore.isVisible = hasSynopsis

        renderCountdown(media)
        val (_, isMovie) = resolveMediaType(media)
        val seasons = media.seasons
        if (!isMovie && seasons.isNotEmpty()) {
            val initialSeason = seasons.firstOrNull { it.isCurrent }?.seasonNumber
                ?: seasons.firstOrNull { it.seasonNumber != null }?.seasonNumber
                ?: 1
            selectedSeasonNumber = initialSeason
            val updatedSeasons = seasons.map { s ->
                s.copy(isCurrent = (s.seasonNumber == selectedSeasonNumber || (s.seasonNumber == null && s.isCurrent)))
            }
            binding.seasonsSection.isVisible = true
            seasonAdapter.submit(updatedSeasons)
        } else {
            binding.seasonsSection.isVisible = false
        }
        val appContainer = (requireActivity().application as AnimarvleApp).container
        updateEpisodesAndPlayButton(appContainer.history.historyFlow.value)
        updateBookmarkState(appContainer.myList.itemsFlow.value)
        selectTab(moreLikeThis = true)
    }

    private fun onSeasonSelected(season: SeasonUi) {
        val seasonNum = season.seasonNumber ?: return
        selectedSeasonNumber = seasonNum
        val currentMedia = current ?: return
        val updatedSeasons = currentMedia.seasons.map { s ->
            s.copy(isCurrent = s.seasonNumber == seasonNum)
        }
        seasonAdapter.submit(updatedSeasons)
        val appContainer = (requireActivity().application as AnimarvleApp).container
        updateEpisodesAndPlayButton(appContainer.history.historyFlow.value)
    }

    private fun updateBookmarkState(items: List<com.animarvle.app.ui.mylist.MyListItem>) {
        val media = current ?: return
        val saved = items.firstOrNull { it.mediaId == media.id }
        val accentColor = com.animarvle.app.ui.theme.AppTheme.accent(requireContext())
        if (saved != null) {
            binding.bookmark.setImageResource(R.drawable.ic_bookmark_filled)
            binding.bookmark.imageTintList = android.content.res.ColorStateList.valueOf(accentColor)
            binding.bookmark.contentDescription = "In My List (${saved.status.label})"
            binding.inLibraryBadge.isVisible = true
            binding.inLibraryBadge.text = "✓ ${saved.status.label}"
            binding.inLibraryBadge.setTextColor(accentColor)
        } else {
            binding.bookmark.setImageResource(R.drawable.ic_bookmark)
            binding.bookmark.imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.on_bg)
            )
            binding.bookmark.contentDescription = getString(R.string.details_save)
            binding.inLibraryBadge.isVisible = false
        }
    }

    private fun updateEpisodesAndPlayButton(historyList: List<com.animarvle.app.ui.player.HistoryItem>) {
        val media = current ?: return
        val (_, isMovie) = resolveMediaType(media)
        val matchingHistory = historyList.filter { it.title.equals(media.title, ignoreCase = true) }
        val latestHistory = matchingHistory.maxByOrNull { it.updatedAtMs }

        // Update Play button label: "Resume Ep N" or "Resume" if in history (PROMPT §7.6)
        if (latestHistory != null) {
            if (isMovie) {
                binding.playButton.setText(R.string.details_resume)
            } else if (latestHistory.episodeNumber > 0) {
                binding.playButton.text = getString(R.string.details_resume_ep, latestHistory.episodeNumber)
            } else {
                binding.playButton.setText(R.string.action_play)
            }
        } else {
            binding.playButton.setText(R.string.action_play)
        }

        if (isMovie) {
            binding.episodesSection.isVisible = false
            return
        }

        binding.episodesSection.isVisible = true
        // Populate Episodes List
        val seasonObj = media.seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
        val seasonEpCount = seasonObj?.episodeCount
        val totalEps = seasonEpCount
            ?: media.episodes
            ?: media.nextAiringEpisode?.let { (it - 1).coerceAtLeast(1) }
            ?: 12
        val episodeItems = (1..totalEps).map { epNum ->
            val hist = matchingHistory.firstOrNull { it.seasonNumber == selectedSeasonNumber && it.episodeNumber == epNum }
                ?: matchingHistory.firstOrNull { it.episodeNumber == epNum }
            DetailEpisodeUi(
                episodeNumber = epNum,
                title = getString(R.string.details_next_episode, epNum),
                thumbnailUrl = seasonObj?.posterUrl ?: media.bannerUrl ?: media.coverUrl,
                history = hist,
            )
        }
        episodeAdapter.submitList(episodeItems)
        binding.episodesList.isVisible = episodeItems.isNotEmpty()
        binding.episodeState.isVisible = episodeItems.isEmpty()

        val appContainer = (requireActivity().application as AnimarvleApp).container
        viewLifecycleOwner.lifecycleScope.launch {
            val preferTmdb = if (arguments?.containsKey("preferTmdb") == true) {
                arguments?.getBoolean("preferTmdb", false) ?: false
            } else {
                com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
                    .currentMode.value == com.animarvle.app.data.AppMediaMode.MOVIES_TV
            }
            val isAdult = media.genres.any { it.equals("Hentai", ignoreCase = true) } ||
                com.animarvle.app.data.MetadataRepository.isAdultTitle(media.title) ||
                com.animarvle.app.data.MetadataRepository.isAdultTitle(media.romajiTitle)
            val metaMap = appContainer.metadata.episodeMetadata(
                mediaId = media.id,
                title = media.title,
                seasonNumber = selectedSeasonNumber,
                isTmdb = preferTmdb,
                isAdult = isAdult,
                romaji = media.romajiTitle,
            )
            if (metaMap.isNotEmpty() && current?.id == media.id) {
                val maxEp = maxOf(totalEps, metaMap.keys.maxOrNull() ?: totalEps)
                val updatedItems = (1..maxEp).map { epNum ->
                    val hist = matchingHistory.firstOrNull { it.seasonNumber == selectedSeasonNumber && it.episodeNumber == epNum }
                        ?: matchingHistory.firstOrNull { it.episodeNumber == epNum }
                    val meta = metaMap[epNum]
                    DetailEpisodeUi(
                        episodeNumber = epNum,
                        title = meta?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.details_next_episode, epNum),
                        thumbnailUrl = meta?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: (seasonObj?.posterUrl ?: media.bannerUrl ?: media.coverUrl),
                        history = hist,
                    )
                }
                episodeAdapter.submitList(updatedItems)
                binding.episodesList.isVisible = updatedItems.isNotEmpty()
                binding.episodeState.isVisible = updatedItems.isEmpty()
            }
        }
    }

    private fun renderCountdown(media: MediaUi) {
        val episode = media.nextAiringEpisode
        val airingAt = media.nextAiringAtEpochSec
        if (episode == null || airingAt == null) {
            binding.countdownChip.isVisible = false
            return
        }
        val remaining = airingAt - System.currentTimeMillis() / 1000
        if (remaining <= 0) {
            binding.countdownChip.isVisible = false
            return
        }
        val days = remaining / 86_400
        val hours = (remaining % 86_400) / 3_600
        val whenText = if (days > 0) "${days}d ${hours}h" else "${hours}h"
        val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(airingAt * 1000))
        binding.countdownChip.text = getString(R.string.details_next_episode, episode) + " · $whenText ($date)"
        binding.countdownChip.isVisible = true
    }

    private fun toggleSynopsis() {
        synopsisExpanded = !synopsisExpanded
        binding.synopsis.maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3
        binding.synopsis.ellipsize = if (synopsisExpanded) null else android.text.TextUtils.TruncateAt.END
        binding.viewMore.setText(if (synopsisExpanded) R.string.details_view_less else R.string.details_view_more)
    }

    private fun selectTab(moreLikeThis: Boolean) {
        binding.tabMoreUnderline.isVisible = moreLikeThis
        binding.tabCommentsUnderline.isVisible = !moreLikeThis
        binding.tabMoreLabel.setTextColor(if (moreLikeThis) accentOrOnSurface() else muted())
        binding.tabCommentsLabel.setTextColor(if (moreLikeThis) muted() else accentOrOnSurface())
        binding.recommendations.isVisible = moreLikeThis
        binding.commentsEmpty.isVisible = !moreLikeThis
    }

    private fun accentOrOnSurface(): Int = AppTheme.accent(requireContext())

    private fun muted(): Int = AppTheme.muted(requireContext())

    private fun resolveMediaType(media: MediaUi?): Pair<MediaType, Boolean> {
        val isDonghuaMedia = com.animarvle.app.AnimarvleApp.isDonghua(media?.title) ||
            media?.genres?.any { it.contains("donghua", ignoreCase = true) } == true
        val isAnimation = media?.genres?.any { it.equals("Animation", ignoreCase = true) } == true
        val isMoviesAndTv = com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
            .currentMode.value == com.animarvle.app.data.AppMediaMode.MOVIES_TV
        val isMovieFormat = media?.format?.contains("MOVIE", ignoreCase = true) == true ||
            media?.format?.contains("FILM", ignoreCase = true) == true
        val isMovie = isMovieFormat || (isMoviesAndTv && !isAnimation && !isDonghuaMedia && (media?.episodes ?: 1) <= 1 && media?.seasons.isNullOrEmpty())
        val type = if (isDonghuaMedia || isAnimation) {
            MediaType.ANIME
        } else if (isMoviesAndTv || isMovieFormat) {
            MediaType.MOVIE
        } else {
            MediaType.ANIME
        }
        return type to isMovie
    }

    /**
     * Navigates to the Player screen immediately (Th3-Anime style §7.7).
     * Source resolution, server fallback, and stream extraction happen asynchronously
     * inside the Player screen while showing playback HUD and loading spinner.
     */
    private fun playCurrent(targetEpisodeNumber: Int = 1) {
        val media = current ?: return
        val (type, isMovie) = resolveMediaType(media)
        val args = Bundle().apply {
            val appContainer = (requireActivity().application as AnimarvleApp).container
            val isDonghua = AnimarvleApp.isDonghua(media.title)
            val candidateBound = appContainer.sourceBindings.getBoundSourceId(media.id)
            val sources = appContainer.sourcesFor(type, media.title)
            val boundSourceId: Long? = if (isDonghua) {
                val isCandidateValid = sources.any { s -> s.id == candidateBound && (s.name.contains("anichi", ignoreCase = true) || s.name.contains("kisskh", ignoreCase = true)) }
                if (isCandidateValid) candidateBound else sources.firstOrNull()?.id
            } else {
                candidateBound ?: sources.firstOrNull()?.id
            }
            boundSourceId?.let { putLong("sourceId", it) }
            putLong("mediaId", media.id)
            putString("mediaTitle", media.title)
            putString("romajiTitle", media.romajiTitle)
            putString("nativeTitle", media.nativeTitle)
            putStringArrayList("synonyms", ArrayList(media.synonyms))
            putString("mediaType", type.name)
            putInt("year", media.year ?: 0)
            val seasonObj = media.seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
            val eps = seasonObj?.episodeCount ?: media.episodes ?: media.nextAiringEpisode?.let { (it - 1).coerceAtLeast(1) } ?: 0
            putInt("seasonNumber", selectedSeasonNumber)
            putInt("episodeCount", eps)
            putInt("episodeNumber", targetEpisodeNumber)
            if (media.seasons.isNotEmpty()) {
                putSerializable("seasons", ArrayList(media.seasons))
            }
            if (media.score != null) {
                putFloat("rating", media.score.toFloat())
            }
            val defaultDub = requireContext().getSharedPreferences(
                com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            ).getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_DEFAULT_DUB, false)
            putString("audio", if (defaultDub) "DUB" else "SUB")
            putString("poster", seasonObj?.posterUrl ?: media.bannerUrl ?: media.coverUrl)
            putString("animeTitle", media.title)
            val preferTmdb = media.isTmdb
            putBoolean("preferTmdb", preferTmdb)
            putBoolean("isMovie", isMovie)
        }
        findNavController().navigate(R.id.playerFragment, args)
    }

    private fun downloadCurrent() {
        val media = current ?: return
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
        val media = current ?: return
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
        val media = current ?: return
        val container = (requireActivity().application as AnimarvleApp).container
        viewLifecycleOwner.lifecycleScope.launch {
            binding.downloadButton.isEnabled = false
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
                    withTimeoutOrNull(MATCH_TIMEOUT_MS) { container.matcher.match(meta, container.sourcesFor(type)) }
                }.getOrNull()
            } ?: MatchResult.NoMatch
            binding.downloadButton.isEnabled = true
            when (result) {
                is MatchResult.Matched -> {
                    val quality = option?.qualityBadge ?: "AUTO"
                    val serverName = option?.serverName
                    val language = option?.language ?: "English"
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
                        val epTitle = getString(R.string.details_next_episode, epNum)
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
                        val epTitle = getString(R.string.details_next_episode, epNumbers.first())
                        toast(getString(R.string.download_queued_toast, "${media.title} $epTitle ($quality)"))
                    } else {
                        toast(getString(R.string.download_batch_queued_toast, epNumbers.size))
                    }
                }
                MatchResult.NoMatch -> {
                    val quality = option?.qualityBadge ?: "AUTO"
                    val serverName = option?.serverName
                    val streamUrl = option?.streamUrl.orEmpty()
                    if (streamUrl.isNotBlank()) {
                        epNumbers.forEach { epNum ->
                            val epTitle = getString(R.string.details_next_episode, epNum)
                            container.downloads.enqueue(
                                id = "${media.id}_ep$epNum",
                                mediaId = media.id,
                                mediaTitle = media.title,
                                episodeTitle = epTitle,
                                episodeNumber = epNum,
                                posterUrl = media.coverUrl ?: media.bannerUrl,
                                streamUrl = streamUrl,
                                totalBytes = option?.estimatedBytes ?: 0L,
                                quality = quality,
                                serverName = serverName,
                                language = option?.language ?: "English",
                            )
                        }
                        toast(getString(R.string.detail_enqueued_episodes, epNumbers.size, quality))
                    } else {
                        toast(R.string.details_install_source)
                    }
                }
            }
        }
    }

    private fun showEpisodeSearchDialog() {
        val media = current ?: return
        val maxEp = media.episodes ?: 999
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.details_search_ep_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 32, 48, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.details_search_episode)
            .setView(input)
            .setPositiveButton(R.string.details_search_ep_jump) { _, _ ->
                val epNum = input.text.toString().trim().toIntOrNull()
                if (epNum != null && epNum in 1..maxEp) {
                    playCurrent(epNum)
                } else {
                    toast(getString(R.string.details_search_ep_invalid))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun open(media: MediaUi) {
        val isMovie = media.format.equals("MOVIE", ignoreCase = true)
        open(media.id, if (isMovie) "movie" else "tv")
    }

    private fun open(id: Long, mediaType: String? = null) {
        val args = Bundle().apply {
            putLong("mediaId", id)
            if (mediaType != null) putString("mediaType", mediaType)
        }
        findNavController().navigate(R.id.detailFragment, args)
    }

    private fun runtimeLabel(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h %02d".format(m) + "m" else "${m} min"
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Cap on a full source sweep; the host applies its own timeouts (ARCH §5). */
        const val MATCH_TIMEOUT_MS = 20_000L
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSkeletonAnimation()
        _binding = null
    }
}