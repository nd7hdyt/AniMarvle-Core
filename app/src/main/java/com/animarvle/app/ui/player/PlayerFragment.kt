package com.animarvle.app.ui.player

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.hardware.SensorManager
import android.provider.Settings
import android.view.OrientationEventListener
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import coil.load
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentPlayerBinding
import com.animarvle.app.ui.detail.ChooseQualityBottomSheet
import com.animarvle.app.ui.theme.AppTheme
import com.animarvle.domain.DerivedUi
import com.animarvle.domain.MetaQuery
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.ServerOption
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import com.animarvle.sourceapi.SubtitleTrack
import com.animarvle.sourceapi.Titles
import kotlinx.coroutines.launch

/**
 * Player, Th3 portrait layout (PROMPT §7.7): video band on top with
 * back · title · quality pill · speed · subs · overflow, then seekbar and the
 * Lock · Volume · −10 · Play/Pause · +10 · Download · Fullscreen row; below it
 * "You are watching Episode N", Sub|Dub tabs, server chips and the episode grid.
 */
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlayerViewModel
    private var player: ExoPlayer? = null
    private var okFactory: OkHttpDataSource.Factory? = null

    private var selectedEpisodeRange: EpisodeRange? = null
    private val gridAdapter = EpisodeGridAdapter { epNumber -> viewModel.selectEpisodeByNumber(epNumber) }

    private var seekTracking = false
    private var locked = false
    private var muted = false
    private var controlsVisible = true
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls(animated = true) }
    private val hideGestureHudHandler = Handler(Looper.getMainLooper())
    private val hideGestureHudRunnable = Runnable { hideGestureHud() }
    private var gestureVolumePercent: Float = -1f
    private var isFullscreen = false
    private var orientationListener: OrientationEventListener? = null
    private var sleepTimerJob: kotlinx.coroutines.Job? = null
    private var sleepTimerEndOnEpisode = false
    private var subtitleDelayOffsetMs: Long = 0L
    private var currentStreamUrl: String? = null
    private var currentStreamHeaders: Map<String, String> = emptyMap()
    private var savedPlaybackPositionMs: Long = 0L
    private fun preparePlayerForDialog() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        showControls(animated = false)
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        _binding?.centerControls?.isVisible = false
    }

    private fun restorePlayerAfterDialog() {
        _binding?.centerControls?.isVisible = true
        scheduleHideControls()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
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

        val container = (requireActivity().application as AnimarvleApp).container
        val args = requireArguments()

        val mediaTitle = args.getString("mediaTitle").orEmpty()
        val rawMediaUrl = args.getString("mediaUrl").orEmpty()
        val posterUrl = args.getString("poster")
        val sanitizedTypeStr = HistoryRepository.sanitizeMediaType(
            args.getString("mediaType") ?: "ANIME",
            mediaTitle,
            rawMediaUrl,
            posterUrl = posterUrl
        )
        val rawMediaType = PlayerViewModel.typeFrom(sanitizedTypeStr)
        val isDonghua = AnimarvleApp.isDonghua(mediaTitle)
        val mediaType = if (isDonghua) MediaType.ANIME else rawMediaType
        val sourceId = args.getLong("sourceId", 0L).takeIf { it != 0L }
        val sources = container.sourcesFor(mediaType, mediaTitle)
        val candidateSource = sources.firstOrNull { it.id == sourceId }
        val isMovieSource = candidateSource != null && (
            candidateSource.name.contains("4k", ignoreCase = true) ||
            candidateSource.name.contains("moviebox", ignoreCase = true) ||
            candidateSource.name.contains("hexawatch", ignoreCase = true) ||
            candidateSource.name.contains("sflix", ignoreCase = true) ||
            candidateSource.name.contains("kisskh", ignoreCase = true) ||
            candidateSource.name.contains("moviesmod", ignoreCase = true) ||
            candidateSource.name.contains("uhdmovies", ignoreCase = true) ||
            candidateSource.name.contains("uniquestream", ignoreCase = true) ||
            candidateSource.name.contains("movie", ignoreCase = true)
        )
        val isDonghuaSource = candidateSource != null && (
            candidateSource.name.contains("anichi", ignoreCase = true) ||
            candidateSource.name.contains("kisskh", ignoreCase = true)
        )
        val initialSource = when {
            isDonghua -> if (isDonghuaSource) candidateSource else sources.firstOrNull()
            mediaType == MediaType.MOVIE -> if (isMovieSource) candidateSource else sources.firstOrNull()
            else -> candidateSource ?: sources.firstOrNull()
        }
        val romajiTitle = args.getString("romajiTitle")
        val nativeTitle = args.getString("nativeTitle")
        val synonyms = args.getStringArrayList("synonyms").orEmpty()
        val year = args.getInt("year", 0).takeIf { it > 0 }
        val episodeCount = args.getInt("episodeCount", 0).takeIf { it > 0 }
        val mediaId = args.getLong("mediaId", 0L)

        val isExplicitAnime = mediaType == MediaType.ANIME || isDonghua
        val preferTmdb = if (isExplicitAnime) {
            false
        } else if (args.containsKey("preferTmdb")) {
            args.getBoolean("preferTmdb", false)
        } else {
            (mediaType == MediaType.MOVIE) ||
                (com.animarvle.app.data.MediaTypeManager.getInstance(requireContext()).currentMode.value == com.animarvle.app.data.AppMediaMode.MOVIES_TV)
        }
        val metaPrefix = if (preferTmdb) "tmdb" else "anilist"
        val metaQuery = MetaQuery(
            metaId = "$metaPrefix:$mediaId",
            type = mediaType,
            titles = Titles(
                english = mediaTitle,
                romaji = romajiTitle,
                native = nativeTitle,
            ),
            synonyms = synonyms,
            year = year,
            episodeCount = episodeCount,
        )

        val isExplicitSourceMatch = sourceId != null && candidateSource != null && candidateSource.id == initialSource?.id
        val isExtensionUrl = rawMediaUrl.startsWith("http://") || rawMediaUrl.startsWith("https://") || rawMediaUrl.startsWith("/")
        val isRawNumberId = rawMediaUrl.isNotEmpty() && rawMediaUrl.all { it.isDigit() }

        val initialSourceMedia = if (isExplicitSourceMatch && !isRawNumberId && isExtensionUrl && (!isDonghua || rawMediaUrl.contains("anichi", ignoreCase = true))) {
            SourceMedia(
                url = rawMediaUrl,
                title = mediaTitle,
                year = year,
                episodeCount = episodeCount,
                poster = args.getString("poster"),
                type = mediaType,
                extra = buildMap {
                    val animeUrl = args.getString("animeUrl") ?: rawMediaUrl
                    animeUrl.let { put("sAnimeUrl", it) }
                    val animeTitle = args.getString("animeTitle") ?: mediaTitle
                    put("animeTitle", animeTitle)
                },
            )
        } else null

        val prefs = requireContext().getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        val defaultQuality = prefs.getString(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_DEFAULT_QUALITY,
            "auto",
        ) ?: "auto"

        val initialCustomQuery = if (mediaId > 0) container.sourceBindings.getCustomQuery(mediaId) else null

        val isOfflineDownload = args.getBoolean("isOfflineDownload", false)
        val offlineMediaUrl = args.getString("mediaUrl").orEmpty()
        val initialEpNum = args.getInt("episodeNumber", 1)
        val initialSeasonNum = args.getInt("seasonNumber", 1)

        val seasonsList: List<com.animarvle.app.domain.SeasonUi> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            args.getSerializable("seasons", ArrayList::class.java) as? ArrayList<com.animarvle.app.domain.SeasonUi>
        } else {
            @Suppress("DEPRECATION")
            args.getSerializable("seasons") as? ArrayList<com.animarvle.app.domain.SeasonUi>
        } ?: emptyList()

        val savedStream = container.history.getSavedStream(mediaTitle, initialEpNum)
        val resumePos = args.getLong("resumePositionMs", savedStream?.positionMs ?: 0L)
        if (resumePos > 0L) {
            savedPlaybackPositionMs = resumePos
        }

        viewModel = ViewModelProvider(
            this,
            PlayerViewModel.Factory(
                sources = sources,
                matcher = container.matcher,
                resolver = container.resolver,
                metaQuery = metaQuery,
                initialSource = initialSource,
                initialSourceMedia = initialSourceMedia,
                mediaTitle = mediaTitle,
                audio = PlayerViewModel.audioFrom(
                    args.getString("audio") ?: if (prefs.getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_DEFAULT_DUB, false)) "DUB" else "SUB"
                ),
                initialEpisodeNumber = initialEpNum,
                initialSeasonNumber = initialSeasonNum,
                initialSeasons = seasonsList,
                defaultQuality = defaultQuality,
                initialCustomQuery = initialCustomQuery,
                isOffline = isOfflineDownload,
                metadata = container.metadata,
                isMovie = if (args.containsKey("isMovie")) args.getBoolean("isMovie") else null,
                savedStream = savedStream,
                httpClient = container.http,
                initialPositionMs = resumePos,
            ),
        )[PlayerViewModel::class.java]

        if (isOfflineDownload) {
            val offlineEpisodes = container.downloads.downloadsFlow.value
                .filter { it.mediaTitle.equals(mediaTitle, ignoreCase = true) && it.status == com.animarvle.app.ui.download.DownloadStatus.COMPLETED }
                .sortedBy { it.episodeNumber }
            if (offlineEpisodes.isNotEmpty()) {
                val eps = offlineEpisodes.map { dl ->
                    val epPlayUrl = if (!dl.filePath.isNullOrBlank() && java.io.File(dl.filePath).exists()) {
                        android.net.Uri.fromFile(java.io.File(dl.filePath)).toString()
                    } else {
                        dl.streamUrl
                    }
                    com.animarvle.sourceapi.SourceEpisode(
                        number = dl.episodeNumber.toFloat(),
                        url = epPlayUrl,
                        title = dl.episodeTitle,
                    )
                }
                val curIdx = eps.indexOfFirst { it.number.toInt() == initialEpNum }.coerceAtLeast(0)
                viewModel.setOfflineEpisodes(eps, curIdx)
            }
        }

        // View Mode: Default is Thumbnail, togglable to Number Tiles
        val savedViewMode = prefs.getString("player_episode_view_mode", "thumbnail")
        val initialViewMode = if (savedViewMode == "number") EpisodeViewMode.NUMBER else EpisodeViewMode.THUMBNAIL
        gridAdapter.setViewMode(initialViewMode)
        binding.episodeGrid.layoutManager = GridLayoutManager(requireContext(), if (initialViewMode == EpisodeViewMode.THUMBNAIL) 2 else 6)
        binding.episodeGrid.adapter = gridAdapter
        binding.episodeGrid.isNestedScrollingEnabled = false
        updateViewModeToggleIcon(initialViewMode)

        binding.btnToggleViewMode.setOnClickListener {
            val newMode = if (gridAdapter.viewMode == EpisodeViewMode.THUMBNAIL) EpisodeViewMode.NUMBER else EpisodeViewMode.THUMBNAIL
            prefs.edit().putString("player_episode_view_mode", if (newMode == EpisodeViewMode.NUMBER) "number" else "thumbnail").apply()
            gridAdapter.setViewMode(newMode)
            (binding.episodeGrid.layoutManager as? GridLayoutManager)?.spanCount = if (newMode == EpisodeViewMode.THUMBNAIL) 2 else 6
            gridAdapter.notifyDataSetChanged()
            updateViewModeToggleIcon(newMode)
        }

        // In portrait mode, ensure the status bar is black and the video starts cleanly below the status bar,
        // so system icons (clock, battery, notifications) never overlap the video.
        ViewCompat.setOnApplyWindowInsetsListener(binding.playerRoot) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = if (isFullscreen) 0 else statusBarTop
            }
            insets
        }

        buildPlayer(container.http)
        wireControls()
        updateOrientationUi()
        observe()
        setupOrientationHandling()

        if (isOfflineDownload) {
            binding.serverNoticeText.isVisible = false
            binding.serverContainer.isVisible = false
            binding.audioTabs.isVisible = false
            binding.playerSourceCard.isVisible = false
            enterFullscreen()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (_binding?.episodesOverlay?.isVisible == true) {
                    hideInPlayerEpisodesDrawer()
                    return
                }
                val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false
                if (!isOffline && isFullscreen) {
                    exitFullscreen()
                } else {
                    isEnabled = false
                    findNavController().navigateUp()
                }
            }
        })

        if (isOfflineDownload && offlineMediaUrl.isNotBlank()) {
            binding.watchingText.text = spanned(
                getString(R.string.player_watching),
                getString(R.string.player_watching_episode, initialEpNum),
            )
            val streamKind = when {
                offlineMediaUrl.contains(".mpd", ignoreCase = true) -> StreamKind.DASH
                offlineMediaUrl.contains(".mp4", ignoreCase = true) -> StreamKind.PROGRESSIVE
                else -> StreamKind.HLS
            }
            binding.playerView.post {
                execute(
                    PlayerCommand.Prepare(
                        streams = listOf(Stream(offlineMediaUrl, streamKind)),
                        subtitles = emptyList(),
                        selectedSubtitle = null,
                        headers = emptyMap(),
                        startPositionMs = 0L,
                    ),
                )
            }
        }
    }

    private fun buildPlayer(http: okhttp3.OkHttpClient) {
        val container = (requireActivity().application as AnimarvleApp).container
        val ok = OkHttpDataSource.Factory(http)
        okFactory = ok
        val upstreamFactory = DefaultDataSource.Factory(requireContext(), ok)
        val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false
        val sourceFactory: androidx.media3.datasource.DataSource.Factory = if (isOffline) {
            CacheDataSource.Factory()
                .setCache(container.downloadCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstreamFactory
        }

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_500,
                /* maxBufferMs = */ 15_000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(sourceFactory))
            .setLoadControl(loadControl)
            .build()
        player = exo
        binding.playerView.player = exo

        applySubtitleAppearance()

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _binding ?: return
                binding.playPause.setImageResource(
                    if (isPlaying) R.drawable.ic_vortex_pause else R.drawable.ic_vortex_play,
                )
                if (isPlaying) {
                    updateBufferingUi(false)
                    scheduleHideControls()
                } else {
                    hideControlsHandler.removeCallbacks(hideControlsRunnable)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _binding ?: return
                val isBuffering = playbackState == Player.STATE_BUFFERING
                updateBufferingUi(isBuffering)

                if (playbackState == Player.STATE_ENDED) {
                    if (sleepTimerEndOnEpisode) {
                        sleepTimerEndOnEpisode = false
                        player?.pause()
                        return
                    }
                    val prefs = requireContext().getSharedPreferences(
                        com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE,
                    )
                    val autoplay = prefs.getBoolean(
                        com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_AUTOPLAY,
                        true,
                    )
                    if (autoplay) {
                        viewModel.playNextEpisode()
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                reportQualityLevels(tracks)
                val targetLangCode = viewModel.selectedLanguageCode.value
                if (targetLangCode.isNotBlank()) {
                    player?.let { p ->
                        if (p.trackSelectionParameters.preferredAudioLanguages.firstOrNull() != targetLangCode) {
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setPreferredAudioLanguage(targetLangCode)
                                .build()
                        }
                    }
                }
                viewModel.rebuildAudioOptions(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("AnimarvlePlayer", "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                val pos = player?.currentPosition ?: 0L
                val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false
                if (!isOffline) {
                    viewModel.onPlaybackFailed(pos)
                } else {
                    binding.streamUnavailableLayout.isVisible = true
                    binding.videoBand.isVisible = false
                    binding.playerScrollView.isVisible = false
                    binding.errorServerRow.isVisible = false
                    binding.streamUnavailableMessage.text = "Unable to play downloaded video. The download file may be incomplete or corrupted."
                    binding.errorRetryButton.setOnClickListener {
                        val offlineUrl = arguments?.getString("mediaUrl").orEmpty()
                        if (offlineUrl.isNotBlank()) {
                            binding.streamUnavailableLayout.isVisible = false
                            binding.videoBand.isVisible = true
                            binding.playerScrollView.isVisible = true
                            val kind = if (offlineUrl.contains(".mp4", ignoreCase = true)) StreamKind.PROGRESSIVE else StreamKind.HLS
                            execute(
                                PlayerCommand.Prepare(
                                    streams = listOf(Stream(offlineUrl, kind)),
                                    subtitles = emptyList(),
                                    selectedSubtitle = null,
                                    headers = emptyMap(),
                                    startPositionMs = 0L,
                                ),
                            )
                        }
                    }
                    binding.errorBackButton.setOnClickListener {
                        findNavController().navigateUp()
                    }
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireControls() {
        binding.backButton.setOnClickListener {
            val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false
            if (!isOffline && isFullscreen) {
                exitFullscreen()
            } else {
                findNavController().navigateUp()
            }
        }
        binding.btnEpisodes.setOnClickListener {
            showInPlayerEpisodesDrawer()
        }
        binding.episodesDrawerClose.setOnClickListener {
            hideInPlayerEpisodesDrawer()
        }
        binding.episodesDrawerBack.setOnClickListener {
            hideInPlayerEpisodesDrawer()
        }
        binding.episodesOverlay.setOnClickListener {
            hideInPlayerEpisodesDrawer()
        }
        binding.episodesDrawerContent.setOnClickListener {
            // Consume click so tapping drawer container doesn't close it
        }
        binding.ccButton.setOnClickListener {
            showSubtitleDialog()
            scheduleHideControls()
        }
        binding.settingsButton.setOnClickListener {
            showPlaybackSettingsBottomSheet()
            scheduleHideControls()
        }
        binding.playPause.setOnClickListener {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
            scheduleHideControls()
        }
        binding.fullscreenButton.setOnClickListener {
            toggleFullscreen()
            scheduleHideControls()
        }
        binding.btnDisplayMode.setOnClickListener {
            cycleDisplayMode()
            scheduleHideControls()
        }
        binding.skipPill.setOnClickListener {
            scheduleHideControls()
            val state = viewModel.state.value
            val skip = state.skipTimes
            val pos = player?.currentPosition ?: 0L
            val opening = skip?.opening
            val inIntro = opening?.let { pos in it } == true
            if (inIntro) {
                player?.seekTo(opening!!.last + 100)
            } else {
                val dur = player?.duration ?: Long.MAX_VALUE
                player?.seekTo(minOf(pos + 90_000L, dur))
            }
        }
        val playerPrefs = requireContext().getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        val pipEnabled = playerPrefs.getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_PIP, true)
        binding.btnPip.isVisible = pipEnabled
        binding.btnPip.setOnClickListener {
            enterPipMode()
            scheduleHideControls()
        }
        binding.btnLock.setOnClickListener { setLocked(true) }
        binding.unlockButton.setOnClickListener { setLocked(false) }
        binding.lockOverlay.setOnClickListener {
            binding.unlockButton.animate().cancel()
            binding.unlockButton.alpha = 1f
            binding.unlockButton.isVisible = true
        }
        binding.topBar.setOnClickListener { scheduleHideControls() }
        binding.bottomControls.setOnClickListener { scheduleHideControls() }
        binding.retryButton.setOnClickListener { viewModel.retry() }

        binding.btnPrevious.setOnClickListener {
            viewModel.playPreviousEpisode()
            scheduleHideControls()
        }
        binding.btnNext.setOnClickListener {
            viewModel.playNextEpisode()
            scheduleHideControls()
        }

        binding.tabSub.setOnClickListener {
            val pos = player?.currentPosition ?: 0L
            viewModel.selectAudio(Audio.SUB, resumeAtMs = pos)
        }
        binding.tabDub.setOnClickListener {
            val pos = player?.currentPosition ?: 0L
            viewModel.selectAudio(Audio.DUB, resumeAtMs = pos)
        }

        // Source Card & Wrong Title actions (Active on player screen below video)
        binding.playerSourceCard.setOnClickListener { showServerDialog() }
        binding.playerBtnWrongTitle.setOnClickListener { showCustomTitleDialog() }

        binding.errorRetryButton.setOnClickListener { viewModel.retry() }
        binding.errorBackButton.setOnClickListener { findNavController().navigateUp() }

        // Action Buttons Row: [ + Add to List ] [ 📺 Cast ] [ 📥 Download ]
        binding.btnAddToList.setOnClickListener {
            val st = viewModel.state.value
            val mId = arguments?.getLong("mediaId", 0L) ?: 0L
            val sheet = com.animarvle.app.ui.mylist.AddToMyListBottomSheet.newInstance(
                mediaId = mId,
                title = st.title,
                posterUrl = arguments?.getString("poster"),
            )
            sheet.show(childFragmentManager, com.animarvle.app.ui.mylist.AddToMyListBottomSheet.TAG)
        }
        binding.btnCast.setOnClickListener {
            val sheet = CastBottomSheet.newInstance()
            sheet.show(childFragmentManager, CastBottomSheet.TAG)
        }
        binding.btnDownload.setOnClickListener {
            triggerDownload()
        }

        // Season Selector dropdown (Option 1)
        binding.btnSeasonDropdown.setOnClickListener {
            val st = viewModel.state.value
            val seasons = st.availableSeasons.ifEmpty {
                val currentS = st.seasonNumber
                (1..maxOf(currentS, 1)).map {
                    com.animarvle.app.domain.SeasonUi(
                        id = it.toLong(),
                        title = "Season $it",
                        posterUrl = null,
                        seasonNumber = it,
                        episodeCount = null,
                    )
                }
            }
            SeasonSelectionBottomSheet.newInstance(
                seasons = seasons,
                currentSeasonNumber = st.seasonNumber,
                onSelected = { seasonNum ->
                    viewModel.selectSeason(seasonNum)
                },
            ).show(childFragmentManager, SeasonSelectionBottomSheet.TAG)
        }

        // Apply frosted-glass blur to the Volume & Brightness HUD capsules
        runCatching {
            VortexBlurHelper.applyGlassEffect(binding.brightnessIndicator, radiusDp = 18f)
            VortexBlurHelper.applyGlassEffect(binding.volumeIndicator, radiusDp = 18f)
        }

        // Episode Number Search
        binding.searchEpInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                performEpisodeSearch()
                true
            } else {
                false
            }
        }
        binding.searchEpContainer.setOnClickListener {
            binding.searchEpInput.requestFocus()
            val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            imm?.showSoftInput(binding.searchEpInput, InputMethodManager.SHOW_IMPLICIT)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                scheduleHideControls()
                val duration = player?.duration?.takeIf { it > 0 } ?: 0L
                val current = duration * progress / 1000
                binding.positionText.text = formatTime(current)
                binding.durationText.text = formatTime(duration)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seekTracking = true
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekTracking = false
                updateBufferingUi(true)
                val duration = player?.duration?.takeIf { it > 0 } ?: 0L
                player?.seekTo(duration * seekBar.progress / 1000)
                scheduleHideControls()
            }
        })

        applyDisplayMode(resizeModeIndex)

        val gestures = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!locked) toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val prefs = requireContext().getSharedPreferences(
                    com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
                    android.content.Context.MODE_PRIVATE,
                )
                val seekSec = prefs.getInt(
                    com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_SEEK_DURATION,
                    10,
                )
                val seekDeltaMs = seekSec * 1000L
                val isForward = e.x > binding.stage.width / 2f
                seekBy(if (isForward) seekDeltaMs else -seekDeltaMs)
                showSeekFeedback(isForward)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (locked || !isFullscreen || e1 == null) return false
                if (kotlin.math.abs(distanceY) > kotlin.math.abs(distanceX)) {
                    // Cross-side gesture mapping:
                    // Swiping on the LEFT side triggers Brightness (displayed on the RIGHT side)
                    // Swiping on the RIGHT side triggers Volume (displayed on the LEFT side)
                    val isTouchOnLeft = e1.x < binding.stage.width / 2f
                    val delta = distanceY / binding.stage.height
                    if (isTouchOnLeft) {
                        adjustBrightness(delta)
                    } else {
                        adjustVolume(delta)
                    }
                    return true
                }
                return false
            }
        })

        val touchListener = View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                gestureVolumePercent = -1f
                scheduleHideGestureHud()
            } else if (event.action == MotionEvent.ACTION_DOWN) {
                hideGestureHudHandler.removeCallbacks(hideGestureHudRunnable)
            }
            gestures.onTouchEvent(event)
        }
        binding.stage.setOnTouchListener(touchListener)
        binding.controlsOverlay.setOnTouchListener(touchListener)
    }

    /**
     * Updates item visibility, spacing, and SeekBar thumb style to match Reference Portrait (Image 6)
     * vs. Reference Landscape (Image 5).
     */
    private fun updateOrientationUi() {
        val b = _binding ?: return
        val state = if (::viewModel.isInitialized) viewModel.state.value else null
        val canShowEpisodes = state == null || (!state.isMovie || state.episodes.size > 1)

        // Landscape-only items (hidden in Portrait)
        b.btnEpisodes.isVisible = isFullscreen && canShowEpisodes
        b.btnLock.isVisible = isFullscreen
        b.btnDisplayMode.isVisible = isFullscreen

        // TopBar & BottomControls padding adjustments
        if (isFullscreen) {
            b.topBar.setPadding(dp(24), dp(18), dp(24), dp(24))
            b.bottomControls.setPadding(dp(24), dp(22), dp(24), dp(20))
        } else {
            b.topBar.setPadding(dp(14), dp(10), dp(14), dp(18))
            b.bottomControls.setPadding(dp(14), dp(16), dp(14), dp(12))
        }

        // Center Transport Controls sizing & spacing
        val prevNextSize = if (isFullscreen) dp(46) else dp(42)
        val playPauseSize = if (isFullscreen) dp(62) else dp(56)
        val transportGap = if (isFullscreen) dp(48) else dp(36)

        b.btnPrevious.layoutParams = (b.btnPrevious.layoutParams as ViewGroup.MarginLayoutParams).apply {
            width = prevNextSize
            height = prevNextSize
        }
        b.btnNext.layoutParams = (b.btnNext.layoutParams as ViewGroup.MarginLayoutParams).apply {
            width = prevNextSize
            height = prevNextSize
        }
        b.playPauseContainer.layoutParams = (b.playPauseContainer.layoutParams as ViewGroup.MarginLayoutParams).apply {
            width = playPauseSize
            height = playPauseSize
            marginStart = transportGap
            marginEnd = transportGap
        }

        // Skip 90s Pill margin adjustments
        b.skipPill.layoutParams = (b.skipPill.layoutParams as ViewGroup.MarginLayoutParams).apply {
            marginEnd = if (isFullscreen) dp(24) else dp(14)
            bottomMargin = if (isFullscreen) dp(58) else dp(50)
        }

        // SeekBar thumb: clean vertical pill in Portrait, cross-pill in Landscape
        b.seekBar.thumb = buildSeekBarThumb(isFullscreen)

        // Fullscreen toggle icon
        b.fullscreenButton.setImageResource(
            if (isFullscreen) R.drawable.ic_vortex_fullscreen_exit else R.drawable.ic_vortex_fullscreen
        )
    }

    private fun buildSeekBarThumb(landscape: Boolean): android.graphics.drawable.Drawable {
        return if (landscape) {
            val hBar = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dp(2).toFloat()
            }
            val vPill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dp(3).toFloat()
            }
            LayerDrawable(arrayOf(hBar, vPill)).apply {
                setLayerSize(0, dp(16), dp(3))
                setLayerGravity(0, Gravity.CENTER)
                setLayerSize(1, dp(5), dp(18))
                setLayerGravity(1, Gravity.CENTER)
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setSize(dp(5), dp(18))
                cornerRadius = dp(3).toFloat()
            }
        }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.commands.collect(::execute) }
                launch { tick() }
                launch {
                    val appContainer = (requireActivity().application as AnimarvleApp).container
                    appContainer.myList.itemsFlow.collect { items ->
                        updateMyListButton(items)
                    }
                }
            }
        }
    }

    private fun cleanShowTitle(title: String): String {
        val cleaned = title.replace(Regex("(?i)\\s*[:\\-]?\\s*\\(?\\s*(season\\s*\\d+|s\\d+)\\b.*?\\)?$"), "").trim()
        return cleaned.ifEmpty { title }
    }

    private fun updateViewModeToggleIcon(mode: EpisodeViewMode) {
        val b = _binding ?: return
        if (mode == EpisodeViewMode.THUMBNAIL) {
            b.btnToggleViewMode.setImageResource(R.drawable.ic_grid_view)
            b.btnToggleViewMode.contentDescription = "Switch to Number Tiles"
        } else {
            b.btnToggleViewMode.setImageResource(R.drawable.ic_tile_numbers)
            b.btnToggleViewMode.contentDescription = "Switch to Thumbnail Grid"
        }
    }

    private fun updateMyListButton(items: List<com.animarvle.app.ui.mylist.MyListItem>) {
        val b = _binding ?: return
        val st = if (::viewModel.isInitialized) viewModel.state.value else null
        val mId = arguments?.getLong("mediaId", 0L) ?: 0L
        val isSaved = items.any { it.mediaId == mId || (st != null && it.title.equals(st.title, ignoreCase = true)) }
        val accent = AppTheme.accent(requireContext())
        if (isSaved) {
            b.ivAddToList.setImageResource(R.drawable.ic_bookmark_filled)
            b.ivAddToList.imageTintList = ColorStateList.valueOf(accent)
            b.tvAddToList.text = "In List"
            b.tvAddToList.setTextColor(accent)
        } else {
            b.ivAddToList.setImageResource(R.drawable.ic_bookmark)
            b.ivAddToList.imageTintList = ColorStateList.valueOf(Color.WHITE)
            b.tvAddToList.text = "+ Add to List"
            b.tvAddToList.setTextColor(Color.WHITE)
        }
    }

    private fun execute(command: PlayerCommand) {
        val p = player ?: return
        when (command) {
            is PlayerCommand.Prepare -> {
                val primaryStream = command.streams.firstOrNull() ?: return
                currentStreamUrl = primaryStream.url
                currentStreamHeaders = command.headers
                okFactory?.setDefaultRequestProperties(command.headers)
                val startPos = command.startPositionMs.coerceAtLeast(0L)
                p.setMediaItem(toMediaItem(primaryStream, command.subtitles), startPos)
                if (startPos > 0L) p.seekTo(startPos)
                applySubtitle(command.selectedSubtitle)
                p.prepare()
                p.playWhenReady = true
            }

            is PlayerCommand.SetSpeed -> p.setPlaybackSpeed(command.speed)
            is PlayerCommand.SetSubtitle -> applySubtitle(command.track)
        }
    }

    private fun toMediaItem(stream: Stream, subtitles: List<SubtitleTrack>): MediaItem {
        val builder = MediaItem.Builder().setUri(stream.url)
        when (stream.kind) {
            StreamKind.DASH -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            StreamKind.HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            StreamKind.PROGRESSIVE -> {
                when {
                    stream.url.contains(".mkv", ignoreCase = true) || stream.url.contains("video/mkv", ignoreCase = true) || stream.url.contains("ext=.mkv", ignoreCase = true) -> {
                        builder.setMimeType(MimeTypes.VIDEO_MATROSKA)
                    }
                    stream.url.contains(".mp4", ignoreCase = true) -> {
                        builder.setMimeType(MimeTypes.VIDEO_MP4)
                    }
                    stream.url.contains(".webm", ignoreCase = true) -> {
                        builder.setMimeType(MimeTypes.VIDEO_WEBM)
                    }
                    // If no explicit container extension in URL, leave mimeType unassigned so DefaultExtractorsFactory auto-sniffs!
                }
            }
        }
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitles.map {
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.url))
                        .setMimeType(
                            when (it.format.name) {
                                "SRT" -> MimeTypes.APPLICATION_SUBRIP
                                "ASS" -> MimeTypes.TEXT_SSA
                                else -> MimeTypes.TEXT_VTT
                            },
                        )
                        .setLanguage(it.language)
                        .setLabel(it.label)
                        .build()
                },
            )
        }
        return builder.build()
    }

    private fun applySubtitle(track: SubtitleTrack?) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .apply { track?.let { setPreferredTextLanguage(it.language) } }
            .build()
    }

    private fun reportQualityLevels(tracks: Tracks) {
        val levels = mutableListOf<Stream>()
        tracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_VIDEO && group.isSupported) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.height > 0) {
                        levels += Stream(
                            url = format.id ?: "${format.height}",
                            kind = StreamKind.HLS,
                            qualityLabel = "${format.height}",
                            height = format.height,
                        )
                    }
                }
            }
        }
        val distinct = levels.distinctBy { it.height }
        if (distinct.size > 1) viewModel.onPlayerQualityLevels(distinct)
    }

    private fun render(state: PlayerUiState) {
        binding.titleText.text = state.title.ifBlank { getString(R.string.player_title) }

        // Episodes icon in top bar is strictly for landscape mode (Reference Image 5 vs Image 6)
        binding.btnEpisodes.isVisible = isFullscreen && (!state.isMovie || state.episodes.size > 1)
        binding.btnLock.isVisible = isFullscreen
        binding.btnDisplayMode.isVisible = isFullscreen

        if (state.isMovie) {
            binding.episodeSubtitleText.text = state.movieYear?.let { "$it • Movie" } ?: "Movie"
            binding.btnPrevious.isVisible = false
            binding.btnNext.isVisible = false
        } else {
            val ep = state.currentEpisode
            val epNum = state.currentEpisodeNumber
            val epTitle = ep?.title?.trim().orEmpty()
            val hasEpTitle = epTitle.isNotEmpty() && !epTitle.equals("episode $epNum", ignoreCase = true) && epTitle != "$epNum"
            binding.episodeSubtitleText.text = if (hasEpTitle) "E$epNum · $epTitle" else "E$epNum · ${state.title}"
            binding.btnPrevious.isVisible = true
            binding.btnNext.isVisible = true
            binding.btnPrevious.alpha = if (state.currentEpisodeIndex > 0) 1.0f else 0.40f
            binding.btnPrevious.isEnabled = state.currentEpisodeIndex > 0
            binding.btnNext.alpha = if (state.currentEpisodeIndex < state.episodes.size - 1) 1.0f else 0.40f
            binding.btnNext.isEnabled = state.currentEpisodeIndex < state.episodes.size - 1
        }

        if (_binding?.episodesOverlay?.isVisible == true) {
            inPlayerEpisodeAdapter?.submitEpisodes(state.episodes, state.currentEpisodeNumber)
        }

        val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false
        if (!isOffline) {
            when (state.phase) {
                PlayerPhase.RESOLVING -> {
                    binding.streamUnavailableLayout.isVisible = false
                    binding.videoBand.isVisible = true
                    binding.playerScrollView.isVisible = !isFullscreen
                    if (!state.message.isNullOrBlank()) {
                        binding.statusBlock.isVisible = true
                        binding.statusText.text = state.message
                        binding.statusSpinner.isVisible = true
                    } else {
                        binding.statusBlock.isVisible = false
                    }
                    updateBufferingUi(true)
                }

                PlayerPhase.READY -> {
                    binding.streamUnavailableLayout.isVisible = false
                    binding.videoBand.isVisible = true
                    binding.playerScrollView.isVisible = !isFullscreen
                    binding.statusBlock.isVisible = false
                    updateBufferingUi(false)
                }

                PlayerPhase.FAILED -> {
                    binding.streamUnavailableLayout.isVisible = true
                    binding.videoBand.isVisible = false
                    binding.playerScrollView.isVisible = false
                    binding.statusBlock.isVisible = false
                    updateBufferingUi(false)
                    binding.streamUnavailableMessage.text = state.error
                        ?: getString(R.string.player_stream_unavailable_body)
                    renderErrorChoices(state)
                }
            }
        } else {
            binding.streamUnavailableLayout.isVisible = false
            binding.videoBand.isVisible = true
            binding.playerScrollView.isVisible = !isFullscreen
            binding.statusBlock.isVisible = false
            updateBufferingUi(false)
            binding.playerSourceCard.isVisible = false
            binding.serverNoticeText.isVisible = false
            binding.serverContainer.isVisible = false
            binding.audioTabs.isVisible = false
        }

        val cleanTitle = cleanShowTitle(state.title)
        binding.titleText.text = cleanTitle
        val epSubtitle = if (state.isMovie) {
            state.movieYear?.toString().orEmpty()
        } else {
            "Season ${state.seasonNumber} • Episode ${state.currentEpisodeNumber}"
        }
        binding.episodeSubtitleText.text = epSubtitle

        binding.detailTitleText.text = cleanTitle
        val rating = arguments?.getFloat("rating", 0f) ?: 0f
        if (rating > 0f) {
            binding.detailRatingBadge.isVisible = true
            binding.detailRatingBadge.text = "★ ${String.format(java.util.Locale.US, "%.1f", rating)}"
        } else {
            binding.detailRatingBadge.isVisible = false
        }
        val yr = state.movieYear ?: arguments?.getInt("year", 0)?.takeIf { it > 0 }
        if (yr != null) {
            binding.detailYearText.isVisible = true
            binding.detailYearText.text = yr.toString()
            binding.detailBadgeDot.isVisible = rating > 0f
        } else {
            binding.detailYearText.isVisible = false
            binding.detailBadgeDot.isVisible = false
        }

        // Subtitle badge: "S1 E1" (or "Movie") matching AniLab Reference
        val epLabel = if (state.isMovie) {
            "Movie"
        } else {
            "S${state.seasonNumber} E${state.currentEpisodeNumber}"
        }
        binding.watchingText.text = epLabel
        binding.watchingText.isVisible = true
        binding.detailBadgeDot2.isVisible = (rating > 0f || yr != null)

        // Season Selector Dropdown (Option 1)
        if (state.isMovie) {
            binding.seasonSelectorRow.isVisible = false
        } else {
            binding.seasonSelectorRow.isVisible = true
            binding.tvSeasonDropdownLabel.text = "Season ${state.seasonNumber}"
        }

        // Active Source Card rendering (on player detail area below video)
        val srcName = state.sourceName.ifBlank { "Select Source" }.uppercase()
        binding.playerSourceName.text = srcName

        val foundText = when {
            state.phase == PlayerPhase.RESOLVING -> "Searching source..."
            state.matchedTitle != null -> "Found: ${state.matchedTitle}"
            state.phase == PlayerPhase.FAILED -> "No match on this source. Try another source or title."
            else -> "Source connected"
        }
        binding.playerSourceFoundText.text = foundText

        // Sub | Dub tabs: hidden completely per AniVortex minimalist design
        binding.audioTabs.isVisible = false

        renderServerChips(state)
        // ccButton stays pinned to the topBar in AniVortex style

        // Episode grid & ranges (PROMPT §7.7)
        renderEpisodeSection(state)

        if (locked) setLocked(true)
    }

    private fun renderEpisodeSection(state: PlayerUiState) {
        binding.episodesBlock.isVisible = !state.isMovie
        if (state.isMovie || state.episodes.isEmpty()) return

        val totalEpisodes = state.episodes.size
        val maxEp = state.episodes.maxOfOrNull { it.number.toInt() } ?: totalEpisodes
        val ranges = buildEpisodeRanges(maxEp)

        // Range filter visibility: user rule: "Includes EPS: 1–100 ▾ range filter when a season is above 1 eoisode"
        binding.epsRangeBtn.isVisible = totalEpisodes > 1

        val currentEpNum = state.currentEpisodeNumber
        val curRange = selectedEpisodeRange
        if (curRange == null || ranges.none { it.start == curRange.start && it.end == curRange.end }) {
            selectedEpisodeRange = ranges.firstOrNull { currentEpNum in it.start..it.end }
                ?: ranges.firstOrNull()
        }

        val activeRange = selectedEpisodeRange ?: EpisodeRange(1, minOf(100, maxEp))
        binding.epsRangeText.text = activeRange.label

        val posterFallback = arguments?.getString("poster")
        val rangeEpisodes = state.episodes.filter { it.number.toInt() in activeRange.start..activeRange.end }
        gridAdapter.submit(rangeEpisodes, state.currentEpisodeNumber, posterFallback)

        binding.epsRangeBtn.setOnClickListener {
            SelectEpisodesBottomSheet.newInstance(
                ranges = ranges,
                selectedRange = selectedEpisodeRange,
                onSelected = { chosenRange ->
                    selectedEpisodeRange = chosenRange
                    binding.epsRangeText.text = chosenRange.label
                    val filtered = state.episodes.filter { it.number.toInt() in chosenRange.start..chosenRange.end }
                    gridAdapter.submit(filtered, state.currentEpisodeNumber, posterFallback)
                },
            ).show(childFragmentManager, SelectEpisodesBottomSheet.TAG)
        }
    }

    private fun buildEpisodeRanges(totalEpisodes: Int, step: Int = 100): List<EpisodeRange> {
        if (totalEpisodes <= 0) return listOf(EpisodeRange(1, 1))
        val list = mutableListOf<EpisodeRange>()
        var start = 1
        while (start <= totalEpisodes) {
            val end = minOf(start + step - 1, totalEpisodes)
            list.add(EpisodeRange(start, end))
            start += step
        }
        return list
    }

    private fun performEpisodeSearch() {
        val query = binding.searchEpInput.text.toString().trim()
        val epNum = query.toIntOrNull() ?: return
        val currentEpisodes = viewModel.state.value.episodes
        val found = currentEpisodes.any { it.number.toInt() == epNum }
        if (found) {
            val maxEp = currentEpisodes.maxOfOrNull { it.number.toInt() } ?: currentEpisodes.size
            val ranges = buildEpisodeRanges(maxEp)
            selectedEpisodeRange = ranges.firstOrNull { epNum in it.start..it.end }
            viewModel.selectEpisodeByNumber(epNum)
            binding.searchEpInput.clearFocus()
            val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.searchEpInput.windowToken, 0)
            val filtered = currentEpisodes.filter { it.number.toInt() in (selectedEpisodeRange?.start ?: 1)..(selectedEpisodeRange?.end ?: maxEp) }
            gridAdapter.submit(filtered, epNum, arguments?.getString("poster"))
            val idx = filtered.indexOfFirst { it.number.toInt() == epNum }
            if (idx >= 0) {
                binding.episodeGrid.scrollToPosition(idx)
            }
        } else {
            Toast.makeText(requireContext(), getString(R.string.player_episode_not_found, epNum.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun spanned(label: String, value: String): CharSequence {
        val accent = AppTheme.accent(requireContext())
        val white = ContextCompat.getColor(requireContext(), R.color.on_bg)
        return SpannableStringBuilder().apply {
            append(label).setSpan(ForegroundColorSpan(accent), 0, length, SPAN_INCLUSIVE_EXCLUSIVE)
            append(" ")
            append(value).setSpan(ForegroundColorSpan(white), length - value.length, length, SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    private fun renderTab(
        label: TextView,
        underline: View,
        enabled: Boolean,
        selected: Boolean,
    ) {
        val accent = AppTheme.accent(requireContext())
        val onBg = ContextCompat.getColor(requireContext(), R.color.on_bg)
        val muted = ContextCompat.getColor(requireContext(), R.color.on_bg_muted)
        label.setTextColor(if (selected) accent else onBg)
        label.alpha = if (enabled) 1f else 0.35f
        label.isEnabled = enabled
        underline.isVisible = selected
        underline.setBackgroundColor(accent)
        if (!enabled) label.setTextColor(muted)
    }

    private fun renderServerChips(state: PlayerUiState) {
        val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false
        if (isOffline) {
            binding.serverNoticeText.isVisible = false
            binding.serverContainer.isVisible = false
            return
        }
        val row = binding.serverRow
        if (row.childCount != state.servers.size) {
            row.removeAllViews()
            repeat(state.servers.size) {
                val chip = TextView(requireContext()).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(8) }
                    setPadding(dp(20), dp(7), dp(20), dp(7))
                    textSize = 13.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                row.addView(chip)
            }
        }
        state.servers.forEachIndexed { index, server ->
            val chip = row.getChildAt(index) as? TextView ?: return@forEachIndexed
            val selected = server.key == state.selectedServer
            chip.text = server.label
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_server_pill_active else R.drawable.bg_server_pill_inactive,
            )
            chip.setTextColor(
                if (selected) ContextCompat.getColor(requireContext(), android.R.color.black)
                else ContextCompat.getColor(requireContext(), R.color.on_bg),
            )
            chip.setOnClickListener {
                val pos = player?.currentPosition ?: 0L
                viewModel.selectServer(server.key, resumeAtMs = pos)
            }
        }
    }

    private fun renderErrorChoices(state: PlayerUiState) {
        val row = binding.errorServerRow
        row.removeAllViews()
        state.servers.forEach { server ->
            val selected = server.key == state.selectedServer
            val chip = TextView(requireContext()).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setPadding(dp(20), dp(8), dp(20), dp(8))
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                text = server.label
                setBackgroundResource(
                    if (selected) R.drawable.bg_server_pill_active else R.drawable.bg_server_pill_inactive,
                )
                setTextColor(
                    if (selected) ContextCompat.getColor(requireContext(), android.R.color.black)
                    else ContextCompat.getColor(requireContext(), R.color.on_bg),
                )
                setOnClickListener {
                    val pos = player?.currentPosition ?: 0L
                    viewModel.selectServer(server.key, resumeAtMs = pos)
                }
            }
            row.addView(chip)
        }

        binding.errorAudioRow.isVisible = false
    }

    // ---- centered chooser dialogs (PROMPT §7.7) ----

    private fun formatToQuality(w: Int, h: Int): String? {
        return when {
            h >= 1600 || w >= 3000 -> "4K"
            h in 800..1599 || w in 1400..2999 -> "1080p"
            h in 500..799 || w in 900..1399 -> "720p"
            h in 400..499 || w in 600..899 -> "480p"
            h in 300..399 || w in 400..599 -> "360p"
            h in 200..299 -> "240p"
            h in 100..199 -> "144p"
            else -> null
        }
    }

    private fun getQualityRowsAndHandler(): Pair<List<ChoiceRow>, (Int) -> Unit> {
        val p = player
        val state = viewModel.state.value
        val quality = state.quality
        val vf = p?.videoFormat ?: run {
            p?.currentTracks?.groups
                ?.filter { it.type == C.TRACK_TYPE_VIDEO }
                ?.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }
                ?.firstOrNull()
        }
        val currentWidth = vf?.width ?: 0
        val currentHeight = vf?.height ?: 0
        val activeQuality = formatToQuality(currentWidth, currentHeight)

        val hlsTracks = p?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_VIDEO }
            ?.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it, false) || g.isSupported }.map { g to it } }
            ?: emptyList()

        // Extract quality from server options if provider offers distinct release resolutions (e.g. 4K releases)
        fun extractResolution(opt: ServerOption): String? {
            val text = "${opt.label} ${opt.hint.orEmpty()} ${opt.key}".lowercase()
            return when {
                text.contains("2160") || text.contains("4k") -> "4K"
                text.contains("1080") -> "1080p"
                text.contains("720") -> "720p"
                text.contains("480") -> "480p"
                text.contains("360") -> "360p"
                else -> null
            }
        }

        val serverQualities = mutableMapOf<String, ServerOption>()
        state.servers.forEach { opt ->
            val q = extractResolution(opt)
            if (q != null && !serverQualities.containsKey(q)) {
                serverQualities[q] = opt
            }
        }

        // Gather all available quality labels cleanly without phantom resolutions
        val collected = mutableListOf<String>()
        if (quality != null && quality.size > 1) {
            for (it in quality) {
                if (!it.label.equals("auto", ignoreCase = true)) {
                    collected.add(DerivedUi.sanitizeQualityLabel(it.label))
                }
            }
        }
        if (hlsTracks.isNotEmpty()) {
            for ((g, i) in hlsTracks) {
                val f = g.getTrackFormat(i)
                val q = formatToQuality(f.width, f.height)
                    ?: if (f.height > 0) "${f.height}p" else null
                if (q != null) collected.add(q)
            }
        }
        // Incorporate distinct resolution tiers offered by provider servers (e.g. 4K, 1080p, 720p)
        for (q in listOf("4K", "1080p", "720p", "480p", "360p")) {
            if (serverQualities.containsKey(q) && !collected.contains(q)) {
                collected.add(q)
            }
        }

        // CRITICAL: The currently active rendering stream resolution must ALWAYS be present in the list
        // so the user can permanently lock the player to this quality!
        if (activeQuality != null && !collected.contains(activeQuality)) {
            collected.add(activeQuality)
        }

        val order = listOf("4K", "1080p", "720p", "480p", "360p", "240p", "144p")
        val sortedQualities = collected
            .filter { !it.equals("Auto", ignoreCase = true) }
            .distinct()
            .sortedWith(compareBy { q ->
                val idx = order.indexOf(q)
                if (idx != -1) idx else 99
            })

        val autoDisplayLabel = "Auto"
        val displayRows = listOf(autoDisplayLabel) + sortedQualities
        val rawValues = listOf("Auto") + sortedQualities

        val isAuto = state.selectedQualityId.equals(DerivedUi.AUTO_ID, ignoreCase = true) || state.selectedQualityId.isBlank()
        val selectedIndex = if (isAuto) {
            0
        } else {
            rawValues.indexOfFirst { raw ->
                raw.equals(state.selectedQualityId, ignoreCase = true) ||
                state.selectedQualityId.contains(raw, ignoreCase = true) ||
                state.quality?.firstOrNull { it.id == state.selectedQualityId }?.label?.let { DerivedUi.sanitizeQualityLabel(it).equals(raw, ignoreCase = true) } == true
            }.coerceAtLeast(0)
        }

        val rows = displayRows.mapIndexed { idx, label ->
            ChoiceRow(label, idx == selectedIndex)
        }

        return rows to { index ->
            val chosenQuality = rawValues[index]
            val chosenItem = quality?.firstOrNull {
                val l = DerivedUi.sanitizeQualityLabel(it.label)
                l.equals(chosenQuality, ignoreCase = true)
            }
            val chosenId = if (chosenQuality.equals("Auto", ignoreCase = true)) {
                DerivedUi.AUTO_ID
            } else {
                chosenItem?.id ?: chosenQuality
            }

            val exactPos = p?.currentPosition ?: 0L
            viewModel.selectQuality(chosenId, resumeAtMs = exactPos)

            val hasLocalQuality = hlsTracks.any { (g, i) ->
                val f = g.getTrackFormat(i)
                val q = formatToQuality(f.width, f.height)
                q?.equals(chosenQuality, ignoreCase = true) == true
            } || (quality != null && quality.any {
                DerivedUi.sanitizeQualityLabel(it.label).equals(chosenQuality, ignoreCase = true)
            })

            val matchingServer = if (!hasLocalQuality) serverQualities[chosenQuality] else null
            if (matchingServer != null && matchingServer.key != state.selectedServer && !chosenQuality.equals("Auto", ignoreCase = true)) {
                viewModel.selectServer(matchingServer.key, resumeAtMs = exactPos)
            }

            if (p != null) {
                if (chosenQuality.equals("Auto", ignoreCase = true)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .clearVideoSizeConstraints()
                        .build()
                } else {
                    val (minW, maxW, minH, maxH) = when {
                        chosenQuality.contains("2160") || chosenQuality.contains("4k", ignoreCase = true) ->
                            listOf(2500, 3840, 1500, 2160)
                        chosenQuality.contains("1080") ->
                            listOf(1400, 1920, 750, 1080)
                        chosenQuality.contains("720") ->
                            listOf(900, 1280, 480, 720)
                        chosenQuality.contains("480") ->
                            listOf(600, 854, 350, 480)
                        chosenQuality.contains("360") ->
                            listOf(400, 640, 250, 360)
                        chosenQuality.contains("240") ->
                            listOf(200, 426, 150, 240)
                        chosenQuality.contains("144") ->
                            listOf(100, 256, 80, 144)
                        else ->
                            listOf(1400, 1920, 750, 1080)
                    }
                    val matchingTrack = hlsTracks.firstOrNull { (g, i) ->
                        val f = g.getTrackFormat(i)
                        val q = formatToQuality(f.width, f.height)
                        q == chosenQuality || (f.height in minH..maxH && f.width in minW..maxW)
                    }
                    val builder = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .setMaxVideoSize(maxW, maxH)
                        .setMinVideoSize(minW, minH)
                    if (matchingTrack != null) {
                        builder.addOverride(TrackSelectionOverride(matchingTrack.first.mediaTrackGroup, matchingTrack.second))
                    }
                    p.trackSelectionParameters = builder.build()
                }
            }
            toast(chosenQuality)
        }
    }

    private fun showQualityDialog() {
        val (rows, handler) = getQualityRowsAndHandler()
        preparePlayerForDialog()
        ChoiceDialog.show(
            context = requireContext(),
            title = getString(R.string.player_quality_title),
            rows = rows,
            onDismiss = { restorePlayerAfterDialog() },
        ) { index ->
            handler(index)
        }
    }

    private fun showSubtitleDialog() {
        val state = viewModel.state.value
        preparePlayerForDialog()
        SubtitlesBottomSheet(
            context = requireContext(),
            subtitles = state.subtitles,
            selectedSubtitle = state.selectedSubtitle,
            currentDelayMs = subtitleDelayOffsetMs,
            onSelectSubtitle = { track ->
                viewModel.selectSubtitle(track)
            },
            onDelayChange = { newDelayMs ->
                subtitleDelayOffsetMs = newDelayMs
                toast(getString(R.string.player_subtitle_delay_applied, newDelayMs))
            },
            onOpenSettings = {
                player?.pause()
                findNavController().navigate(R.id.subtitleSettingsFragment)
            },
            onDismiss = {
                restorePlayerAfterDialog()
            },
        ).show()
    }

    private fun formatAudioTrackLabel(lang: String?, rawLabel: String?, index: Int): String {
        val detected = (if (!SpokenLanguage.isUntaggedOrUndetermined(rawLabel)) SpokenLanguage.fromRawTag(rawLabel) else null)
            ?: (if (!SpokenLanguage.isUntaggedOrUndetermined(lang)) SpokenLanguage.fromRawTag(lang) else null)
        if (detected != null) {
            return detected.displayName
        }
        if (!rawLabel.isNullOrBlank() && !SpokenLanguage.isUntaggedOrUndetermined(rawLabel) && !rawLabel.matches(Regex("^[a-z]{2,3}$", RegexOption.IGNORE_CASE))) {
            return rawLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        val code = (lang ?: rawLabel.orEmpty()).trim().lowercase()
        if (code.isNotBlank() && !SpokenLanguage.isUntaggedOrUndetermined(code)) {
            try {
                val loc = java.util.Locale.forLanguageTag(code)
                val name = loc.getDisplayLanguage(java.util.Locale.ENGLISH)
                if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) return name
            } catch (_: Exception) {}
        }
        return viewModel.state.value.audioLanguage.takeIf { !SpokenLanguage.isUntaggedOrUndetermined(it) } ?: "English"
    }

    private fun showPlaybackSettingsBottomSheet() {
        val speedStr = "${viewModel.state.value.speed}x"
        val state = viewModel.state.value
        val p = player

        val vf = p?.videoFormat ?: run {
            p?.currentTracks?.groups
                ?.filter { it.type == C.TRACK_TYPE_VIDEO }
                ?.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }
                ?.firstOrNull()
        }
        val heightBadge = formatToQuality(vf?.width ?: 0, vf?.height ?: 0)

        val currentQualityLabel = when {
            state.selectedQualityId.equals(DerivedUi.AUTO_ID, ignoreCase = true) || state.selectedQualityId.isBlank() -> {
                "Auto"
            }
            else -> state.quality
                ?.firstOrNull { it.id == state.selectedQualityId }
                ?.label?.let { if (it.equals("auto", ignoreCase = true)) "Auto" else DerivedUi.sanitizeQualityLabel(it) }
                ?: DerivedUi.sanitizeQualityLabel(state.selectedQualityId)
        }

        val hlsTracks = p?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
            ?.flatMap { g -> (0 until g.length).map { g to it } }
            ?: emptyList()

        val hasMultiQuality = (state.quality != null && state.quality.size > 1) || hlsTracks.size > 1

        val audioOptions = viewModel.availableAudioOptions.value
        val currentAudioLabel = audioOptions.firstOrNull {
            it.languageCode.equals(viewModel.selectedLanguageCode.value, ignoreCase = true)
        }?.languageName ?: state.audioLanguage.ifBlank { "English" }

        val hasMultiAudio = audioOptions.size > 1

        val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false

        val rootRows = mutableListOf<ChoiceRow>()
        rootRows.add(
            ChoiceRow(
                title = getString(R.string.player_speed),
                subtitle = speedStr,
                iconRes = R.drawable.ic_vortex_playback_speed,
            )
        )
        if (!isOffline) {
            rootRows.add(
                ChoiceRow(
                    title = getString(R.string.player_quality),
                    subtitle = currentQualityLabel,
                    iconRes = R.drawable.ic_tune,
                )
            )
            rootRows.add(
                ChoiceRow(
                    title = "Audio",
                    subtitle = currentAudioLabel,
                    iconRes = R.drawable.ic_music_note,
                )
            )
        }

        preparePlayerForDialog()
        ChoiceDialog.showWithSubmenus(
            context = requireContext(),
            initialPage = MenuPage(
                title = getString(R.string.player_settings),
                rows = rootRows
            ) { ctrl, index ->
                when (index) {
                    0 -> {
                        val (speedRows, speedHandler) = getSpeedRowsAndHandler()
                        ctrl.navigateTo(
                            title = getString(R.string.player_speed_title),
                            rows = speedRows
                        ) { c, pickedIdx ->
                            speedHandler(pickedIdx)
                            c.dismiss()
                        }
                    }
                    1 -> {
                        val (qualityRows, qualityHandler) = getQualityRowsAndHandler()
                        ctrl.navigateTo(
                            title = getString(R.string.player_quality_title),
                            rows = qualityRows
                        ) { c, pickedIdx ->
                            qualityHandler(pickedIdx)
                            c.dismiss()
                        }
                    }
                    2 -> {
                        val (audioRows, audioHandler) = getAudioRowsAndHandler()
                        ctrl.navigateTo(
                            title = "Audio",
                            rows = audioRows
                        ) { c, pickedIdx ->
                            audioHandler(pickedIdx)
                            c.dismiss()
                        }
                    }
                }
            },
            onDismiss = { restorePlayerAfterDialog() }
        )
    }

    private fun getAudioRowsAndHandler(): Pair<List<ChoiceRow>, (Int) -> Unit> {
        val options = viewModel.availableAudioOptions.value
        val activeLangCode = viewModel.selectedLanguageCode.value
        val state = viewModel.state.value

        if (options.isEmpty()) {
            val current = state.audioLanguage.ifBlank { "English" }
            return listOf(ChoiceRow(current, true)) to { toast(current) }
        }

        val hasBothTypes = options.any { it is ResolvedAudioOption.EmbeddedTrack } &&
                options.any { it is ResolvedAudioOption.ServerStream }

        val labels = options.map { option ->
            when (option) {
                is ResolvedAudioOption.EmbeddedTrack -> option.languageName
                is ResolvedAudioOption.ServerStream -> {
                    if (hasBothTypes) "${option.languageName} (Switch Server)" else option.languageName
                }
            }
        }

        val selectedIndex = options.indexOfFirst { opt ->
            opt.languageCode.equals(activeLangCode, ignoreCase = true) ||
            opt.languageName.equals(state.audioLanguage, ignoreCase = true)
        }.coerceAtLeast(0)

        val rows = labels.mapIndexed { idx, label ->
            ChoiceRow(label, idx == selectedIndex)
        }

        return rows to { which ->
            val chosen = options.getOrNull(which) ?: return@to
            applyAudioOption(chosen)
        }
    }

    private fun applyAudioOption(option: ResolvedAudioOption) {
        val p = player ?: return
        when (option) {
            is ResolvedAudioOption.EmbeddedTrack -> {
                val override = TrackSelectionOverride(
                    option.trackGroup,
                    listOf(option.trackIndex)
                )
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .addOverride(override)
                    .setPreferredAudioLanguage(option.languageCode)
                    .build()

                viewModel.onLanguageSelected(option.languageCode, option.languageName)
                toast("Audio: ${option.languageName}")
            }

            is ResolvedAudioOption.ServerStream -> {
                val savedPositionMs = p.currentPosition
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setPreferredAudioLanguage(option.languageCode)
                    .build()
                toast("Loading ${option.languageName}...")
                viewModel.selectServerStreamLanguage(option, resumeAtMs = savedPositionMs)
            }
        }
    }

    private fun showAudioDialog() {
        val (rows, handler) = getAudioRowsAndHandler()
        preparePlayerForDialog()
        ChoiceDialog.show(
            context = requireContext(),
            title = "Audio",
            rows = rows,
            onDismiss = { restorePlayerAfterDialog() },
        ) { index ->
            handler(index)
        }
    }

    private fun getSpeedRowsAndHandler(): Pair<List<ChoiceRow>, (Int) -> Unit> {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val current = viewModel.state.value.speed
        val rows = speeds.map { ChoiceRow("${it} x", it == current) }
        return rows to { index ->
            viewModel.setSpeed(speeds[index])
        }
    }

    private fun showSpeedDialog() {
        val (rows, handler) = getSpeedRowsAndHandler()
        preparePlayerForDialog()
        ChoiceDialog.show(
            context = requireContext(),
            title = getString(R.string.player_speed_title),
            rows = rows,
            onDismiss = { restorePlayerAfterDialog() },
        ) { index ->
            handler(index)
        }
    }

    private var inPlayerEpisodeAdapter: InPlayerEpisodeAdapter? = null

    private fun showInPlayerEpisodesDrawer() {
        val state = viewModel.state.value
        val episodes = state.episodes
        if (episodes.isEmpty()) {
            toast(getString(R.string.player_episode_not_found, ""))
            return
        }
        hideControls(animated = false)

        val fallbackPoster = arguments?.getString("poster")
        val currentEpNum = state.currentEpisodeNumber

        binding.episodesDrawerTitle.text = state.title.ifBlank { getString(R.string.player_title) }
        binding.episodesDrawerSubtitle.text = "Season ${state.seasonNumber} • Episode $currentEpNum"
        binding.episodesDrawerSeasonText.text = "Season ${state.seasonNumber}"

        if (inPlayerEpisodeAdapter == null) {
            inPlayerEpisodeAdapter = InPlayerEpisodeAdapter(
                episodes = episodes,
                selectedEpNumber = currentEpNum,
                fallbackThumb = fallbackPoster,
            ) { pickedEp ->
                val chosenNum = pickedEp.number.toInt()
                inPlayerEpisodeAdapter?.updateSelection(chosenNum)
                binding.episodesDrawerSubtitle.text = "Season ${state.seasonNumber} • Episode $chosenNum"
                viewModel.selectEpisodeByNumber(chosenNum)
            }
            binding.episodesDrawerRecycler.adapter = inPlayerEpisodeAdapter
        } else {
            inPlayerEpisodeAdapter?.submitEpisodes(episodes, currentEpNum)
        }

        binding.episodesOverlay.alpha = 0f
        binding.episodesOverlay.isVisible = true
        binding.episodesOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .start()

        val curIdx = episodes.indexOfFirst { it.number.toInt() == currentEpNum }
        if (curIdx >= 0) {
            binding.episodesDrawerRecycler.scrollToPosition(curIdx)
        }
    }

    private fun hideInPlayerEpisodesDrawer() {
        if (_binding?.episodesOverlay?.isVisible == true) {
            binding.episodesOverlay.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    _binding?.episodesOverlay?.isVisible = false
                    _binding?.episodesOverlay?.alpha = 1f
                }
                .start()
            showControls(animated = true)
        }
    }

    private fun showMoreMenu() {
        val labels = listOf(
            getString(R.string.player_quality),
            getString(R.string.player_speed),
            getString(R.string.player_subtitles),
            getString(R.string.player_server),
            getString(R.string.player_display_mode),
            getString(R.string.player_rotate),
            getString(R.string.action_download),
            getString(R.string.player_pip),
            getString(R.string.player_subtitle_delay),
            getString(R.string.settings_subtitle),
            getString(R.string.player_select_player),
            getString(R.string.player_sleep_timer),
        )
        ChoiceDialog.show(
            requireContext(),
            getString(R.string.player_more),
            labels.map { ChoiceRow(it, selected = false) },
        ) { index ->
            when (index) {
                0 -> showQualityDialog()
                1 -> showSpeedDialog()
                2 -> showSubtitleDialog()
                3 -> showServerDialog()
                4 -> cycleDisplayMode()
                5 -> toggleRotation()
                6 -> triggerDownload()
                7 -> enterPipMode()
                8 -> showSubtitleDelayDialog()
                9 -> {
                    player?.pause()
                    findNavController().navigate(R.id.subtitleSettingsFragment)
                }
                10 -> showSelectPlayerDialog()
                11 -> showSleepTimerDialog()
                else -> toast(labels[index])
            }
        }
    }

    private fun triggerDownload() {
        val state = viewModel.state.value
        val currentEp = state.episodes.getOrNull(state.currentEpisodeIndex)
        val epNumber = currentEp?.number?.toInt() ?: 1
        val title = arguments?.getString("mediaTitle") ?: arguments?.getString("title").orEmpty()
        val poster = arguments?.getString("poster") ?: arguments?.getString("posterUrl")
        val mediaId = arguments?.getLong("mediaId", 0L) ?: 0L
        val isMovie = state.isMovie
        val year = arguments?.getInt("year", 0)?.takeIf { it > 0 }

        val durationMinutes = (player?.duration?.takeIf { it > 0 }?.div(60_000L))?.toInt()
            ?: arguments?.getInt("durationMinutes", 0)?.takeIf { it > 0 }

        val sheet = ChooseQualityBottomSheet.newInstance(
            mediaId = mediaId,
            mediaTitle = title,
            posterUrl = poster,
            episodes = listOf(epNumber),
            mediaType = if (isMovie) MediaType.MOVIE else MediaType.ANIME,
            year = year,
            durationMinutes = durationMinutes,
        )
        sheet.setOnConfirmListener { option, _ ->
            val appContainer = (requireActivity().application as AnimarvleApp).container
            val epTitle = currentEp?.title ?: getString(R.string.player_watching_episode, epNumber)
            val streamUrl = option.streamUrl.ifBlank { currentEp?.url.orEmpty() }
            val id = "${title.hashCode()}_ep$epNumber"
            viewLifecycleOwner.lifecycleScope.launch {
                appContainer.downloads.enqueue(
                    id = id,
                    mediaId = mediaId.takeIf { it > 0L },
                    mediaTitle = title,
                    episodeTitle = epTitle,
                    episodeNumber = epNumber,
                    posterUrl = poster,
                    streamUrl = streamUrl,
                    totalBytes = option.estimatedBytes,
                    quality = option.qualityBadge,
                    serverName = option.serverName,
                    language = option.language,
                )
                toast(getString(R.string.download_queued_toast, "$title $epTitle (${option.qualityBadge})"))
            }
        }
        sheet.show(childFragmentManager, ChooseQualityBottomSheet.TAG)
    }

    private var resizeModeIndex = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val resizeColors = intArrayOf(
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#38BDF8"),
        Color.parseColor("#38BDF8"),
    )
    private val resizeLabels = arrayOf(
        R.string.player_mode_fit,
        R.string.player_mode_fill,
        R.string.player_mode_zoom,
    )

    private fun applyDisplayMode(index: Int) {
        resizeModeIndex = (index % resizeModes.size + resizeModes.size) % resizeModes.size
        binding.playerView.resizeMode = resizeModes[resizeModeIndex]
        binding.btnDisplayMode.imageTintList = ColorStateList.valueOf(resizeColors[resizeModeIndex])
    }

    private fun cycleDisplayMode() {
        applyDisplayMode(resizeModeIndex + 1)
    }

    private fun toggleRotation() {
        toggleFullscreen()
    }

    private fun showSubtitleDelayDialog() {
        val delays = listOf(
            -2000L to "-2.0 s",
            -1000L to "-1.0 s",
            -500L to "-500 ms",
            0L to getString(R.string.player_subtitle_delay_none),
            500L to "+500 ms",
            1000L to "+1.0 s",
            2000L to "+2.0 s",
        )
        val rows = delays.map { ChoiceRow(it.second, it.first == subtitleDelayOffsetMs) }
        ChoiceDialog.show(requireContext(), getString(R.string.player_subtitle_delay_title), rows) { selected ->
            val chosen = delays[selected]
            subtitleDelayOffsetMs = chosen.first
            toast(getString(R.string.player_subtitle_delay_applied, chosen.first))
        }
    }

    private fun showSelectPlayerDialog() {
        val options = listOf(
            getString(R.string.player_select_player_internal),
            getString(R.string.player_select_player_external),
        )
        ChoiceDialog.show(requireContext(), getString(R.string.player_select_player), options.map { ChoiceRow(it, selected = false) }) { selected ->
            if (selected == 1) {
                launchExternalPlayer()
            }
        }
    }

    private fun launchExternalPlayer() {
        val streamUrl = currentStreamUrl
        if (streamUrl.isNullOrBlank()) {
            toast(getString(R.string.player_no_stream_external))
            return
        }
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(streamUrl), "video/*")
                putExtra(android.content.Intent.EXTRA_TITLE, viewModel.state.value.title)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            player?.pause()
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.player_select_player)))
        }.onFailure {
            toast(getString(R.string.player_no_stream_external))
        }
    }

    private fun showSleepTimerDialog() {
        val options = listOf(
            getString(R.string.player_sleep_timer_off) to 0L,
            getString(R.string.player_sleep_timer_15) to 15 * 60 * 1000L,
            getString(R.string.player_sleep_timer_30) to 30 * 60 * 1000L,
            getString(R.string.player_sleep_timer_45) to 45 * 60 * 1000L,
            getString(R.string.player_sleep_timer_60) to 60 * 60 * 1000L,
            getString(R.string.player_sleep_timer_end_ep) to -1L,
        )
        val rows = options.map {
            val isSelected = when (it.second) {
                0L -> sleepTimerJob == null && !sleepTimerEndOnEpisode
                -1L -> sleepTimerEndOnEpisode
                else -> false
            }
            ChoiceRow(it.first, isSelected)
        }
        ChoiceDialog.show(requireContext(), getString(R.string.player_sleep_timer), rows) { selectedIndex ->
            val chosen = options[selectedIndex]
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            sleepTimerEndOnEpisode = false

            when (chosen.second) {
                0L -> toast(getString(R.string.player_sleep_timer_cancelled))
                -1L -> {
                    sleepTimerEndOnEpisode = true
                    toast(getString(R.string.player_sleep_timer_set, chosen.first))
                }
                else -> {
                    val durationMs = chosen.second
                    sleepTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(durationMs)
                        player?.pause()
                        toast(getString(R.string.player_sleep_timer) + " finished")
                    }
                    toast(getString(R.string.player_sleep_timer_set, chosen.first))
                }
            }
        }
    }

    private fun showServerDialog() {
        val state = viewModel.state.value
        val sheet = ServerSelectionBottomSheet.newInstance(
            servers = state.servers,
            selectedKey = state.selectedServer,
            audio = state.audio,
        ) { pickedKey, targetAudio ->
            val pos = player?.currentPosition ?: 0L
            if (targetAudio != null && targetAudio != state.audio) {
                viewModel.selectAudio(targetAudio, resumeAtMs = pos)
            }
            viewModel.selectServer(pickedKey, resumeAtMs = pos)
        }
        sheet.show(childFragmentManager, "ServerSelectionBottomSheet")
    }

    // ---- position loop / skip ----

    private suspend fun tick() {
        while (true) {
            val b = _binding ?: return
            val p = player
            if (p != null && !seekTracking) {
                val pos = p.currentPosition
                val dur = p.duration.takeIf { it > 0 } ?: 0L
                if (dur > 0) binding.seekBar.progress = (pos * 1000 / dur).toInt()
                binding.positionText.text = formatTime(pos)
                binding.durationText.text = formatTime(dur)
                updateSkipButtons(pos)
            }
            kotlinx.coroutines.delay(500)
        }
    }

    private fun updateSkipButtons(positionMs: Long) {
        val state = viewModel.state.value
        val isAnime = !state.isMovie && (arguments?.getString("mediaType") != "MOVIE")
        val skip = state.skipTimes
        val inIntro = skip?.opening?.let { positionMs in it } == true
        val inOutro = skip?.ending?.let { positionMs in it } == true

        val prefs = requireContext().getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        val showSkip = prefs.getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_SHOW_SKIP, true)

        // Skip 90s Pill exclusive for anime (floating above timeline)
        binding.skipPill.isVisible = isAnime && !locked && controlsVisible && showSkip
        binding.skipPillText.text = if (inIntro) getString(R.string.player_skip_intro) else "Skip 90s"

        if (skip == null) {
            binding.skipIntro.isVisible = false
            binding.skipOutro.isVisible = false
            return
        }

        val autoSkip = prefs.getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_AUTO_SKIP, false)

        if (autoSkip) {
            if (inIntro) {
                skip.opening?.let { player?.seekTo(it.last + 100) }
                binding.skipIntro.isVisible = false
                return
            }
            if (inOutro) {
                skip.ending?.let { player?.seekTo(it.last + 100) }
                binding.skipOutro.isVisible = false
                return
            }
        }

        val visible = showSkip && !locked
        binding.skipIntro.isVisible = visible && inIntro
        binding.skipOutro.isVisible = visible && inOutro

        binding.skipIntro.setOnClickListener { skip.opening?.let { player?.seekTo(it.last + 100) } }
        binding.skipOutro.setOnClickListener { skip.ending?.let { player?.seekTo(it.last + 100) } }
    }

    // ---- buffering & loading indicator ----

    private var isBufferingState = false

    private fun updateBufferingUi(isBufferingOrSeeking: Boolean) {
        val b = _binding ?: return
        isBufferingState = isBufferingOrSeeking
        if (isBufferingOrSeeking) {
            b.playPause.visibility = View.INVISIBLE
            b.playerLoadingIndicator.visibility = View.VISIBLE
            if (!controlsVisible && !locked) {
                showControls(animated = true)
            }
        } else {
            b.playerLoadingIndicator.visibility = View.GONE
            b.playPause.visibility = if (locked) View.GONE else View.VISIBLE
        }
    }

    // ---- visibility / lock ----

    private fun showControls(animated: Boolean = true) {
        val b = _binding ?: return
        if (locked) return
        controlsVisible = true
        b.controlsOverlay.animate().cancel()
        if (animated) {
            b.controlsOverlay.alpha = 0f
            b.controlsOverlay.isVisible = true
            b.controlsOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        } else {
            b.controlsOverlay.alpha = 1f
            b.controlsOverlay.isVisible = true
        }
        scheduleHideControls()
    }

    private fun hideControls(animated: Boolean = true) {
        val b = _binding ?: return
        controlsVisible = false
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        b.controlsOverlay.animate().cancel()
        if (animated) {
            b.controlsOverlay.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    _binding?.controlsOverlay?.isVisible = false
                }
                .start()
        } else {
            b.controlsOverlay.alpha = 0f
            b.controlsOverlay.isVisible = false
        }
    }

    private fun toggleControls() {
        if (locked) return
        if (controlsVisible) {
            hideControls(animated = true)
        } else {
            showControls(animated = true)
        }
    }

    private fun scheduleHideControls() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true && controlsVisible && !locked && !isBufferingState) {
            hideControlsHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun setLocked(value: Boolean) {
        locked = value
        val b = _binding ?: return
        b.lockOverlay.isVisible = value
        if (value) {
            hideControls(animated = false)
        } else {
            showControls(animated = true)
        }
    }

    // ---- helpers ----

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    private fun showSeekFeedback(forward: Boolean) {
        val view = if (forward) binding.forwardIndicator else binding.rewindIndicator
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.isVisible = true

        view.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(200)
            .withEndAction {
                view.animate()
                    .alpha(0f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(300)
                    .withEndAction {
                        view.isVisible = false
                    }
                    .start()
            }
            .start()

        val prefs = requireContext().getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        if (prefs.getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_HAPTIC, true)) {
            binding.stage.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun adjustBrightness(delta: Float) {
        val act = activity ?: return
        val lp = act.window.attributes
        var current = lp.screenBrightness
        if (current < 0) {
            current = runCatching {
                android.provider.Settings.System.getInt(
                    act.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    128,
                ) / 255f
            }.getOrDefault(0.5f)
        }
        val updated = (current + delta).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = updated
        act.window.attributes = lp

        val pct = Math.round(updated * 100f)
        showBrightnessHud(pct, updated)
    }

    private fun showBrightnessHud(pct: Int, fraction: Float) {
        hideGestureHudHandler.removeCallbacks(hideGestureHudRunnable)
        if (controlsVisible) hideControls(animated = false)
        binding.volumeIndicator.isVisible = false
        binding.volumeIndicator.animate().cancel()

        binding.brightnessIndicator.animate().cancel()
        binding.brightnessIndicator.alpha = 1f
        binding.brightnessIndicator.isVisible = true

        binding.brightnessText.text = "$pct%"

        val trackHeight = binding.brightnessTrackContainer.height.takeIf { it > 0 } ?: dp(94)
        val thumbSize = binding.brightnessThumb.height.takeIf { it > 0 } ?: dp(12)
        val usableTravel = (trackHeight - thumbSize).coerceAtLeast(0)
        val thumbOffset = fraction * usableTravel
        binding.brightnessThumb.translationY = -thumbOffset
        binding.brightnessProgressFill.layoutParams = binding.brightnessProgressFill.layoutParams.apply {
            height = if (pct == 0) 0 else (thumbOffset + thumbSize / 2f).toInt()
        }
    }

    private fun adjustVolume(delta: Float) {
        val audioManager = ContextCompat.getSystemService(requireContext(), AudioManager::class.java) ?: return
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (gestureVolumePercent < 0f) {
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            gestureVolumePercent = (curVol.toFloat() / maxVol.toFloat()) * 100f
        }

        gestureVolumePercent = (gestureVolumePercent + delta * 100f).coerceIn(0f, 100f)
        val newStreamIndex = Math.round((gestureVolumePercent / 100f) * maxVol).coerceIn(0, maxVol)
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newStreamIndex, 0)
        }
        player?.volume = 1.0f

        val pct = Math.round(gestureVolumePercent)
        val frac = gestureVolumePercent / 100f
        showVolumeHud(pct, frac)
    }

    private fun showVolumeHud(pct: Int, fraction: Float) {
        hideGestureHudHandler.removeCallbacks(hideGestureHudRunnable)
        if (controlsVisible) hideControls(animated = false)
        binding.brightnessIndicator.isVisible = false
        binding.brightnessIndicator.animate().cancel()

        binding.volumeIndicator.animate().cancel()
        binding.volumeIndicator.alpha = 1f
        binding.volumeIndicator.isVisible = true

        binding.volumeText.text = "$pct%"
        binding.volumeIcon.setImageResource(
            if (pct == 0) R.drawable.ic_volume_off else R.drawable.ic_volume,
        )

        val trackHeight = binding.volumeTrackContainer.height.takeIf { it > 0 } ?: dp(94)
        val thumbSize = binding.volumeThumb.height.takeIf { it > 0 } ?: dp(12)
        val usableTravel = (trackHeight - thumbSize).coerceAtLeast(0)
        val thumbOffset = fraction * usableTravel
        binding.volumeThumb.translationY = -thumbOffset
        binding.volumeProgressFill.layoutParams = binding.volumeProgressFill.layoutParams.apply {
            height = if (pct == 0) 0 else (thumbOffset + thumbSize / 2f).toInt()
        }
    }

    private fun scheduleHideGestureHud() {
        hideGestureHudHandler.removeCallbacks(hideGestureHudRunnable)
        hideGestureHudHandler.postDelayed(hideGestureHudRunnable, 700L)
    }

    private fun hideGestureHud() {
        val b = _binding ?: return
        hideGestureHudHandler.removeCallbacks(hideGestureHudRunnable)
        if (b.brightnessIndicator.isVisible) {
            b.brightnessIndicator.animate().cancel()
            b.brightnessIndicator.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    _binding?.brightnessIndicator?.isVisible = false
                    _binding?.brightnessIndicator?.alpha = 1f
                }
                .start()
        }
        if (b.volumeIndicator.isVisible) {
            b.volumeIndicator.animate().cancel()
            b.volumeIndicator.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    _binding?.volumeIndicator?.isVisible = false
                    _binding?.volumeIndicator?.alpha = 1f
                }
                .start()
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (if (ms < 0) -ms else ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

    private fun enterPipMode() {
        val prefs = requireContext().getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        if (!prefs.getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_PIP, true)) {
            toast(getString(R.string.player_pip))
            return
        }
        val act = activity ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val aspect = android.util.Rational(16, 9)
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .build()
            act.enterPictureInPictureMode(params)
        } else {
            toast(getString(R.string.player_pip))
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            hideControls(animated = false)
            binding.playerScrollView.isVisible = false
        } else {
            showControls(animated = true)
            binding.playerScrollView.isVisible = true
        }
    }

    override fun onPause() {
        super.onPause()
        val prefs = requireContext().getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        val allowBackground = prefs.getBoolean(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_BACKGROUND_PLAY,
            false,
        )
        val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            activity?.isInPictureInPictureMode == true
        } else false

        player?.let { p ->
            val pos = p.currentPosition
            if (pos > 0L) {
                savedPlaybackPositionMs = pos
            }
            saveCurrentProgress(pos, p.duration)
            if (!allowBackground && !isInPip) {
                p.pause()
            }
        }
    }

    private fun saveCurrentProgress(positionMs: Long, durationMs: Long) {
        if (positionMs <= 0 || durationMs <= 0) return
        val app = activity?.application as? AnimarvleApp ?: return
        val state = viewModel.state.value
        val mediaUrl = state.currentEpisode?.url ?: arguments?.getString("mediaUrl").orEmpty()
        if (mediaUrl.isBlank()) return
        val poster = arguments?.getString("poster")
        val isDonghua = AnimarvleApp.isDonghua(state.title)
        val rawTypeArg = arguments?.getString("mediaType")
        val isExplicitAnime = isDonghua ||
            rawTypeArg?.equals("ANIME", ignoreCase = true) == true ||
            HistoryRepository.isAnimeTitle(state.title) ||
            poster?.contains("anilist.co") == true ||
            poster?.contains("myanimelist.net") == true ||
            poster?.contains("hentai") == true ||
            poster?.contains("animesex") == true
        val preferTmdb = if (isExplicitAnime) false else (
            (arguments?.getBoolean("preferTmdb", false) ?: false) ||
            mediaUrl.toLongOrNull() != null ||
            mediaUrl.contains("tmdb=")
        )
        val mediaType = when {
            isExplicitAnime -> "ANIME"
            !preferTmdb -> "ANIME"
            rawTypeArg?.equals("TV", ignoreCase = true) == true -> "TV"
            rawTypeArg?.equals("MOVIE", ignoreCase = true) == true -> "MOVIE"
            else -> if (state.isMovie) "MOVIE" else "TV"
        }
        val streamToSave = currentStreamUrl
        val headersToSave = currentStreamHeaders
        val serverToSave = viewModel.state.value.selectedServer
        viewLifecycleOwner.lifecycleScope.launch {
            app.container.history.saveProgress(
                mediaUrl = mediaUrl,
                title = state.title,
                posterUrl = poster,
                episodeNumber = state.currentEpisodeNumber,
                positionMs = positionMs,
                durationMs = durationMs,
                audio = state.audio.name,
                mediaType = mediaType,
                seasonNumber = state.seasonNumber,
                streamUrl = streamToSave,
                streamHeaders = headersToSave,
                serverName = serverToSave,
            )
        }
    }

    private fun isAutoRotateEnabled(): Boolean {
        val act = activity ?: return false
        return runCatching {
            Settings.System.getInt(
                act.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0,
            ) == 1
        }.getOrDefault(false)
    }

    private fun setupOrientationHandling() {
        val act = activity ?: return

        if (isAutoRotateEnabled()) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            val isCurrentlyLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isCurrentlyLandscape && !isFullscreen) {
                applyFullscreenUi(true)
            }
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        orientationListener = object : OrientationEventListener(requireContext(), SensorManager.SENSOR_DELAY_NORMAL) {
            private var lastDetected = -1 // 0 = portrait, 1 = landscape

            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (!isAutoRotateEnabled()) return

                val isLandscape = (orientation in 60..120) || (orientation in 240..300)
                val isPortrait = (orientation in 335..360) || (orientation in 0..25)

                if (isLandscape && lastDetected != 1) {
                    lastDetected = 1
                    if (!isFullscreen) {
                        enterFullscreen(fromSensor = true)
                    }
                } else if (isPortrait && lastDetected != 0) {
                    lastDetected = 0
                    if (isFullscreen) {
                        exitFullscreen(fromSensor = true)
                    }
                }
            }
        }

        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isAutoRotateEnabled()) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        }
        orientationListener?.enable()
    }

    override fun onStop() {
        orientationListener?.disable()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isAutoRotateEnabled()) return
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (!isFullscreen) {
                applyFullscreenUi(true)
            }
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (isFullscreen) {
                applyFullscreenUi(false)
            }
        }
    }

    private fun applyFullscreenUi(fullscreen: Boolean) {
        val act = activity ?: return
        val b = _binding ?: return
        isFullscreen = fullscreen

        if (fullscreen) {
            b.playerScrollView.isVisible = false
            b.statusBarSpacer.layoutParams = b.statusBarSpacer.layoutParams.apply {
                height = 0
            }
            b.stage.layoutParams = b.stage.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            b.videoBand.layoutParams = b.videoBand.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            b.fullscreenButton.setImageResource(R.drawable.ic_vortex_fullscreen_exit)
            // Immersive system bars
            WindowInsetsControllerCompat(act.window, act.window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            val isOffline = arguments?.getBoolean("isOfflineDownload", false) ?: false
            b.playerScrollView.isVisible = true
            if (isOffline) {
                b.serverNoticeText.isVisible = false
                b.serverContainer.isVisible = false
                b.audioTabs.isVisible = false
                b.playerSourceCard.isVisible = false
            }
            val statusBarTop = requireView().rootWindowInsets?.let {
                WindowInsetsCompat.toWindowInsetsCompat(it).getInsets(WindowInsetsCompat.Type.statusBars()).top
            } ?: dp(24)
            b.statusBarSpacer.layoutParams = b.statusBarSpacer.layoutParams.apply {
                height = statusBarTop
            }
            b.stage.layoutParams = b.stage.layoutParams.apply {
                height = dp(230)
            }
            b.videoBand.layoutParams = b.videoBand.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            b.fullscreenButton.setImageResource(R.drawable.ic_vortex_fullscreen)
            WindowInsetsControllerCompat(act.window, act.window.decorView).apply {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        updateOrientationUi()
    }

    private fun enterFullscreen(fromSensor: Boolean = false) {
        val act = activity ?: return
        if (!fromSensor) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        applyFullscreenUi(true)
    }

    private fun exitFullscreen(fromSensor: Boolean = false) {
        val act = activity ?: return
        if (!fromSensor) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else if (isAutoRotateEnabled()) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        }
        applyFullscreenUi(false)
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen(fromSensor = false)
        } else {
            enterFullscreen(fromSensor = false)
        }
    }

    private fun showSourceSelectorSheet() {
        val app = requireActivity().application as AnimarvleApp
        val mediaTitle = requireArguments().getString("mediaTitle").orEmpty()
        val rawMediaType = PlayerViewModel.typeFrom(requireArguments().getString("mediaType"))
        val mediaType = if (AnimarvleApp.isDonghua(mediaTitle)) MediaType.ANIME else rawMediaType
        val sources = app.container.sourcesFor(mediaType, mediaTitle)
        if (sources.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.player_no_extensions_installed), Toast.LENGTH_SHORT).show()
            return
        }

        val mediaId = requireArguments().getLong("mediaId", 0L)
        val currentSourceId = viewModel.activeSourceId

        val sheet = com.animarvle.app.ui.detail.SourceSelectorBottomSheet.newInstance(
            sources = sources,
            selectedSourceId = currentSourceId,
        ) { selectedSource ->
            if (mediaId > 0) {
                app.container.sourceBindings.setBoundSourceId(mediaId, selectedSource.id)
            }
            viewModel.selectSource(selectedSource.id)
        }
        sheet.show(childFragmentManager, com.animarvle.app.ui.detail.SourceSelectorBottomSheet.TAG)
    }

    private fun showCustomTitleDialog() {
        val app = requireActivity().application as AnimarvleApp
        val mediaId = requireArguments().getLong("mediaId", 0L)
        val defaultTitle = requireArguments().getString("mediaTitle").orEmpty()
        val currentCustom = if (mediaId > 0) app.container.sourceBindings.getCustomQuery(mediaId) else null

        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.player_title_override_hint)
            setText(currentCustom ?: defaultTitle)
            setSelection(text.length)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_bg))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.on_bg_muted))
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_title_override_title)
            .setMessage(R.string.player_title_override_message)
            .setView(input)
            .setPositiveButton(R.string.player_title_override_search) { _, _ ->
                val newQuery = input.text.toString().trim()
                if (mediaId > 0) {
                    app.container.sourceBindings.setCustomQuery(mediaId, newQuery)
                }
                viewModel.updateCustomQuery(newQuery)
            }
            .setNegativeButton(R.string.player_title_override_reset) { _, _ ->
                if (mediaId > 0) {
                    app.container.sourceBindings.setCustomQuery(mediaId, null)
                }
                viewModel.updateCustomQuery(null)
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        applySubtitleAppearance()
        player?.let { p ->
            if (p.playbackState == androidx.media3.common.Player.STATE_IDLE && viewModel.state.value.phase == PlayerPhase.READY) {
                val resumePos = if (savedPlaybackPositionMs > 0L) savedPlaybackPositionMs else p.currentPosition
                viewModel.rePrepareCurrentIfReady(resumePos)
            } else if (p.playbackState == androidx.media3.common.Player.STATE_READY && !p.isPlaying) {
                p.playWhenReady = true
            }
        }
    }

    private fun applySubtitleAppearance() {
        val subView = _binding?.playerView?.subtitleView ?: return
        val subPrefs = requireContext().getSharedPreferences(
            com.animarvle.app.ui.profile.SubtitleSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        val subSize = subPrefs.getInt(com.animarvle.app.ui.profile.SubtitleSettingsFragment.KEY_TEXT_SIZE, 16)
        val subColor = subPrefs.getInt(
            com.animarvle.app.ui.profile.SubtitleSettingsFragment.KEY_TEXT_COLOR,
            android.graphics.Color.WHITE,
        )
        val outlineEnabled = subPrefs.getBoolean(
            com.animarvle.app.ui.profile.SubtitleSettingsFragment.KEY_OUTLINE_ENABLED,
            true,
        )
        val bottomPaddingDp = subPrefs.getInt(
            com.animarvle.app.ui.profile.SubtitleSettingsFragment.KEY_BOTTOM_PADDING,
            20,
        )

        subView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subSize.toFloat())
        // Convert bottom padding dp (0..60) into SubtitleView bottom padding fraction
        val bottomFraction = (bottomPaddingDp.toFloat() / 250f).coerceIn(0.02f, 0.35f)
        subView.setBottomPaddingFraction(bottomFraction)

        val edgeType = if (outlineEnabled) {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } else {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        }

        subView.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                subColor,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                edgeType,
                android.graphics.Color.BLACK,
                android.graphics.Typeface.DEFAULT_BOLD,
            ),
        )
    }

    override fun onDestroyView() {
        orientationListener?.disable()
        orientationListener = null
        activity?.let { act ->
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowInsetsControllerCompat(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        player?.let { p ->
            val pos = p.currentPosition
            if (pos > 0L) {
                savedPlaybackPositionMs = pos
            }
        }
        hideControlsHandler.removeCallbacksAndMessages(null)
        binding.playerView.player = null
        player?.release()
        player = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val SEEK_MS = 10_000L
        const val CONTROLS_TIMEOUT_MS = 3500L
    }
}

private class InPlayerEpisodeAdapter(
    private var episodes: List<SourceEpisode>,
    private var selectedEpNumber: Int,
    private val fallbackThumb: String?,
    private val onPick: (SourceEpisode) -> Unit,
) : androidx.recyclerview.widget.RecyclerView.Adapter<InPlayerEpisodeAdapter.Holder>() {

    class Holder(val binding: com.animarvle.app.databinding.ItemInPlayerEpisodeBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        com.animarvle.app.databinding.ItemInPlayerEpisodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        ),
    )

    override fun getItemCount(): Int = episodes.size

    fun updateSelection(epNumber: Int) {
        val prev = episodes.indexOfFirst { it.number.toInt() == selectedEpNumber }
        val next = episodes.indexOfFirst { it.number.toInt() == epNumber }
        selectedEpNumber = epNumber
        if (prev >= 0) notifyItemChanged(prev)
        if (next >= 0) notifyItemChanged(next)
    }

    fun submitEpisodes(newEpisodes: List<SourceEpisode>, epNumber: Int) {
        episodes = newEpisodes
        selectedEpNumber = epNumber
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ep = episodes[position]
        val epNum = ep.number.toInt()
        val isSelected = epNum == selectedEpNumber

        val density = holder.itemView.resources.displayMetrics.density
        holder.binding.cardContainer.strokeWidth = if (isSelected) (2 * density).toInt() else 0

        holder.binding.episodeNumberTitle.text = "Episode : $epNum"

        val epOverview = ep.extra["overview"]
        val epDesc = ep.extra["description"]
        val epTitle = ep.title
        val overview = when {
            !epOverview.isNullOrBlank() -> epOverview
            !epDesc.isNullOrBlank() -> epDesc
            !epTitle.isNullOrBlank() && !epTitle.equals("Episode $epNum", ignoreCase = true) && epTitle != "$epNum" -> epTitle
            else -> "Episode $epNum"
        }
        holder.binding.episodeDescription.text = overview

        val thumbUrl = if (!ep.thumbnail.isNullOrBlank()) ep.thumbnail else fallbackThumb
        holder.binding.episodeThumbnail.load(thumbUrl) {
            crossfade(true)
            placeholder(R.drawable.bg_search_poster_placeholder)
            error(R.drawable.bg_search_poster_placeholder)
        }

        holder.binding.cardContainer.setOnClickListener {
            onPick(ep)
        }
    }
}