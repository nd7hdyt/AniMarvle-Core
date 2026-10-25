package com.animarvle.app.ui.schedule

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.os.bundleOf
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
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentScheduleBinding
import com.animarvle.app.databinding.ItemDayBinding
import com.animarvle.app.ui.mylist.AddToMyListBottomSheet
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * Schedule (PROMPT §7.8): day strip + timeline. The day strip shows 3 days before
 * through 3 days after today, matching the reference layout.
 */
class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ScheduleViewModel
    private var pulseAnimator: ObjectAnimator? = null
    private val adapter = ScheduleAdapter(
        onOpen = { item ->
            val isMovie = item.format.equals("Movie", ignoreCase = true)
            findNavController().navigate(
                R.id.detailsFragment,
                bundleOf(
                    "mediaId" to item.mediaId,
                    "mediaType" to if (isMovie) "movie" else "tv",
                ),
            )
        },
        onToggleMyList = { item ->
            val sheet = AddToMyListBottomSheet.newInstance(
                mediaId = item.mediaId,
                title = item.title,
                posterUrl = item.coverUrl,
                format = item.format,
                totalEpisodes = item.totalEpisodes,
            )
            sheet.show(childFragmentManager, AddToMyListBottomSheet.TAG)
        },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = (requireActivity().application as AnimarvleApp).container
        val repo = container.metadata
        val mediaTypeManager = com.animarvle.app.data.MediaTypeManager.getInstance(requireContext())
        viewModel = ViewModelProvider(this, ScheduleViewModel.Factory(repo, mediaTypeManager))[ScheduleViewModel::class.java]

        ViewCompat.setOnApplyWindowInsetsListener(binding.scheduleRoot) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.searchButton.setOnClickListener { findNavController().navigate(R.id.searchFragment) }
        binding.swipe.setColorSchemeColors(android.graphics.Color.TRANSPARENT)
        binding.swipe.setProgressBackgroundColorSchemeColor(android.graphics.Color.TRANSPARENT)
        binding.swipe.setOnRefreshListener {
            binding.reloadIndicator.isVisible = true
            viewModel.load(viewModel.state.value.dayOffset)
        }
        binding.retryButton.setOnClickListener { viewModel.load(viewModel.state.value.dayOffset) }

        binding.tabAnime.setOnClickListener {
            viewModel.setMode(com.animarvle.app.data.AppMediaMode.ANIME)
        }
        binding.tabMoviesTv.setOnClickListener {
            viewModel.setMode(com.animarvle.app.data.AppMediaMode.MOVIES_TV)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect(::render)
                }
                launch {
                    container.myList.itemsFlow.collect { items ->
                        val savedMap = items.associate { it.mediaId to it.status }
                        adapter.setSavedMedia(savedMap)
                    }
                }
            }
        }
    }

    private fun render(state: ScheduleState) {
        val isReloading = state.loading && state.items.isNotEmpty()
        binding.swipe.isRefreshing = isReloading
        binding.reloadIndicator.isVisible = isReloading
        renderDayStrip(state.dayOffset)
        adapter.submit(state.items, isToday = state.dayOffset == 0)

        val isAnime = state.mode == com.animarvle.app.data.AppMediaMode.ANIME
        binding.tabAnime.setBackgroundResource(
            if (isAnime) R.drawable.bg_display_mode_btn_selected else R.drawable.bg_display_mode_btn_unselected
        )
        binding.tabAnime.setTextColor(if (isAnime) Color.BLACK else Color.parseColor("#8E92A0"))

        binding.tabMoviesTv.setBackgroundResource(
            if (!isAnime) R.drawable.bg_display_mode_btn_selected else R.drawable.bg_display_mode_btn_unselected
        )
        binding.tabMoviesTv.setTextColor(if (!isAnime) Color.BLACK else Color.parseColor("#8E92A0"))

        if (state.totalCount != null) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, state.dayOffset) }
            val monthDayFmt = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
            val prefix = if (state.dayOffset == 0) "Today, " else ""
            binding.scheduleCountHeader.text = "$prefix${monthDayFmt.format(cal.time)} (${state.totalCount})"
            binding.scheduleCountHeader.isVisible = true
        } else {
            binding.scheduleCountHeader.isVisible = false
        }

        val showSkeleton = state.loading && state.items.isEmpty()
        if (showSkeleton) {
            startSkeletonAnimation()
        } else {
            stopSkeletonAnimation()
        }

        val showError = state.error != null
        val showEmpty = !showError && state.loaded && state.items.isEmpty()
        binding.list.isVisible = !showError && !showEmpty && !showSkeleton
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
            binding.emptyTitle.setText(R.string.schedule_no_items_title)
            binding.emptyBody.setText(R.string.schedule_no_items_body)
            binding.retryButton.isVisible = false
        }
    }

    private fun startSkeletonAnimation() {
        binding.scheduleSkeleton.isVisible = true
        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(
                binding.scheduleSkeleton,
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
            b.scheduleSkeleton.alpha = 1.0f
            b.scheduleSkeleton.isVisible = false
        }
    }

    /** Builds 7 day pills centred on today (3 before / 3 after), selecting [selected]. */
    private fun renderDayStrip(selected: Int) {
        val inflater = LayoutInflater.from(requireContext())
        binding.dayStrip.removeAllViews()
        val today = Calendar.getInstance()
        val weekdayFmt = java.text.SimpleDateFormat("EEE", Locale.getDefault())
        val dayFmt = java.text.SimpleDateFormat("d", Locale.getDefault())

        for (offset in -3..3) {
            val cal = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val pill = ItemDayBinding.inflate(inflater, binding.dayStrip, false)
            pill.dayName.text = weekdayFmt.format(cal.time)
            pill.dayNumber.text = dayFmt.format(cal.time)
            val isSelected = offset == selected
            pill.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_day_pill_selected else R.drawable.bg_day_pill,
            )
            if (isSelected) {
                pill.dayName.setTextColor(Color.BLACK)
                pill.dayNumber.setTextColor(Color.BLACK)
            } else {
                pill.dayName.setTextColor(Color.parseColor("#8E92A0"))
                pill.dayNumber.setTextColor(Color.WHITE)
            }
            pill.root.setOnClickListener {
                viewModel.load(offset)
            }
            binding.dayStrip.addView(pill.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSkeletonAnimation()
        _binding = null
    }
}