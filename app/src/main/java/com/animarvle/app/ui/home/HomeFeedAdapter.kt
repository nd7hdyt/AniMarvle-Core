package com.animarvle.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.animarvle.app.R

import android.widget.ImageView
import android.widget.ProgressBar
import coil.load

class HomeFeedAdapter(
    private val onMediaClick: (Media) -> Unit = {},
    private val onResumeClick: (Media) -> Unit = {},
    private val onPlayClick: (Media) -> Unit = {},
    private val onMyListClick: (Media) -> Unit = {},
    private val onSeeAllClick: (String) -> Unit = {},
    private val onProviderClick: (ProviderLogo) -> Unit = {},
    private val onFilterRailHeaderClick: (FeedRow.FilterableRail) -> Unit = {},
    private val onFilterRailOptionSelected: (FeedRow.FilterableRail, String) -> Unit = { _, _ -> },
    private val onFilterRailPillSelected: (FeedRow.FilterableRail, Int) -> Unit = { _, _ -> },
) : ListAdapter<FeedRow, RecyclerView.ViewHolder>(FeedRowDiffCallback()) {

    private var savedMediaIds: Set<Long> = emptySet()
    private var activeHeroAdapter: HeroBannerAdapter? = null

    fun setSavedIds(ids: Set<Long>) {
        savedMediaIds = ids
        activeHeroAdapter?.setSavedIds(ids)
    }

    companion object {
        private const val TYPE_HERO_CAROUSEL = 0
        private const val TYPE_CONTINUE_WATCHING = 1
        private const val TYPE_PROVIDER_BUBBLES = 2
        private const val TYPE_STANDARD_RAIL = 3
        private const val TYPE_FILTERABLE_RAIL = 4
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is FeedRow.HeroCarousel -> TYPE_HERO_CAROUSEL
            is FeedRow.ContinueWatching -> TYPE_CONTINUE_WATCHING
            is FeedRow.ProviderBubbles -> TYPE_PROVIDER_BUBBLES
            is FeedRow.StandardRail -> TYPE_STANDARD_RAIL
            is FeedRow.FilterableRail -> TYPE_FILTERABLE_RAIL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO_CAROUSEL -> {
                val view = inflater.inflate(R.layout.item_feed_hero_carousel, parent, false)
                HeroCarouselViewHolder(view)
            }
            TYPE_CONTINUE_WATCHING -> {
                val view = inflater.inflate(R.layout.item_feed_continue_watching, parent, false)
                ContinueWatchingViewHolder(view)
            }
            TYPE_PROVIDER_BUBBLES -> {
                val view = inflater.inflate(R.layout.item_feed_provider_bubbles, parent, false)
                ProviderBubblesViewHolder(view)
            }
            TYPE_STANDARD_RAIL -> {
                val view = inflater.inflate(R.layout.item_feed_standard_rail, parent, false)
                StandardRailViewHolder(view)
            }
            TYPE_FILTERABLE_RAIL -> {
                val view = inflater.inflate(R.layout.item_feed_filterable_rail, parent, false)
                FilterableRailViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is HeroCarouselViewHolder -> holder.bind(row as FeedRow.HeroCarousel)
            is ContinueWatchingViewHolder -> holder.bind(row as FeedRow.ContinueWatching)
            is ProviderBubblesViewHolder -> holder.bind(row as FeedRow.ProviderBubbles)
            is StandardRailViewHolder -> holder.bind(row as FeedRow.StandardRail)
            is FilterableRailViewHolder -> holder.bind(row as FeedRow.FilterableRail)
        }
    }

    // --- ViewHolders ---

    inner class HeroCarouselViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val viewPager: ViewPager2 = view.findViewById(R.id.heroViewPager)
        private val tvCurrent: TextView = view.findViewById(R.id.tvCarouselCurrent)
        private val tvTotal: TextView = view.findViewById(R.id.tvCarouselTotal)
        private val progressTrack: View = view.findViewById(R.id.carouselProgressTrack)
        private val progressFill: View = view.findViewById(R.id.carouselProgressFill)
        private val heroAdapter = HeroBannerAdapter(
            onMediaClick = { media -> onMediaClick(media) },
            onPlayClick = { media -> onPlayClick(media) },
            onMyListClick = { media -> onMyListClick(media) }
        )
        private var pageCallback: ViewPager2.OnPageChangeCallback? = null
        private val autoScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val autoScrollRunnable = object : Runnable {
            override fun run() {
                val count = heroAdapter.itemCount
                if (count > 1) {
                    val next = (viewPager.currentItem + 1) % count
                    viewPager.setCurrentItem(next, true)
                    autoScrollHandler.postDelayed(this, 4500L)
                }
            }
        }

        init {
            viewPager.adapter = heroAdapter
            activeHeroAdapter = heroAdapter
        }

        fun bind(row: FeedRow.HeroCarousel) {
            heroAdapter.setSavedIds(savedMediaIds)
            heroAdapter.submitList(row.items)
            pageCallback?.let { viewPager.unregisterOnPageChangeCallback(it) }
            autoScrollHandler.removeCallbacks(autoScrollRunnable)

            val total = row.items.size
            tvTotal.text = String.format(java.util.Locale.US, "%02d", total)

            val updateIndicator = { pos: Int ->
                val currentNum = if (total > 0) pos + 1 else 0
                tvCurrent.text = String.format(java.util.Locale.US, "%02d", currentNum)
                val trackWidthPx = (34f * itemView.resources.displayMetrics.density).toInt()
                val fillFraction = if (total > 0) (currentNum.toFloat() / total).coerceIn(0.15f, 1f) else 0f
                progressFill.layoutParams = progressFill.layoutParams.apply {
                    width = (trackWidthPx * fillFraction).toInt().coerceAtLeast(4)
                }
            }
            updateIndicator(viewPager.currentItem)

            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateIndicator(position)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                        autoScrollHandler.removeCallbacks(autoScrollRunnable)
                    } else if (state == ViewPager2.SCROLL_STATE_IDLE && heroAdapter.itemCount > 1) {
                        autoScrollHandler.removeCallbacks(autoScrollRunnable)
                        autoScrollHandler.postDelayed(autoScrollRunnable, 4500L)
                    }
                }
            }
            pageCallback = callback
            viewPager.registerOnPageChangeCallback(callback)

            if (total > 1) {
                autoScrollHandler.postDelayed(autoScrollRunnable, 4500L)
            }
        }
    }

    inner class ContinueWatchingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvRailTitle)
        private val recyclerView: RecyclerView = view.findViewById(R.id.horizontalRecyclerView)
        private val adapter = ContinueWatchingAdapter { media -> onResumeClick(media) }

        init {
            recyclerView.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            recyclerView.setHasFixedSize(true)
            recyclerView.adapter = adapter
        }

        fun bind(row: FeedRow.ContinueWatching) {
            tvTitle.text = row.title
            adapter.submitList(row.items)
        }
    }

    inner class ProviderBubblesViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val recyclerView: RecyclerView = view.findViewById(R.id.horizontalRecyclerView)
        private val adapter = ProviderBubbleAdapter { provider -> onProviderClick(provider) }

        init {
            recyclerView.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            recyclerView.adapter = adapter
        }

        fun bind(row: FeedRow.ProviderBubbles) {
            adapter.submitList(row.providers)
        }
    }

    inner class StandardRailViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvRailTitle)
        private val tvSeeAll: TextView = view.findViewById(R.id.tvSeeAll)
        private val recyclerView: RecyclerView = view.findViewById(R.id.horizontalRecyclerView)
        private val horizontalAdapter = HorizontalMediaAdapter { media -> onMediaClick(media) }

        init {
            recyclerView.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            recyclerView.setHasFixedSize(true)
            recyclerView.adapter = horizontalAdapter
        }

        fun bind(row: FeedRow.StandardRail) {
            tvTitle.text = row.title
            horizontalAdapter.submitList(row.items)

            tvSeeAll.setOnClickListener {
                onSeeAllClick(row.title)
            }
        }
    }

    inner class FilterableRailViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleContainer: View = view.findViewById(R.id.titleContainer)
        private val tvRailTitle: TextView = view.findViewById(R.id.tvRailTitle)
        private val btnFilterDropdown: View = view.findViewById(R.id.btnFilterDropdown)
        private val tvSelectedFilter: TextView = view.findViewById(R.id.tvSelectedFilter)
        private val pillContainer: View = view.findViewById(R.id.pillContainer)
        private val pillOption1: TextView = view.findViewById(R.id.pillOption1)
        private val pillOption2: TextView = view.findViewById(R.id.pillOption2)
        private val railProgressBar: ProgressBar = view.findViewById(R.id.railProgressBar)
        private val recyclerView: RecyclerView = view.findViewById(R.id.horizontalRecyclerView)
        private val horizontalAdapter = HorizontalMediaAdapter { media -> onMediaClick(media) }

        init {
            recyclerView.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            recyclerView.adapter = horizontalAdapter
        }

        fun bind(row: FeedRow.FilterableRail) {
            tvRailTitle.text = row.title
            tvSelectedFilter.text = row.selectedOption
            railProgressBar.visibility = if (row.isLoading) View.VISIBLE else View.GONE
            horizontalAdapter.submitList(row.items)

            titleContainer.setOnClickListener {
                onFilterRailHeaderClick(row)
            }

            btnFilterDropdown.setOnClickListener { anchor ->
                com.animarvle.app.ui.common.FilterMenuPopupHelper.showOptionMenu(
                    anchor = anchor,
                    options = row.availableOptions,
                    selectedOption = row.selectedOption,
                    onOptionSelected = { chosen ->
                        onFilterRailOptionSelected(row, chosen)
                    }
                )
            }

            if (row.pillOptions.size >= 2) {
                pillContainer.visibility = View.VISIBLE
                pillOption1.text = row.pillOptions[0]
                pillOption2.text = row.pillOptions[1]

                if (row.activePillIndex == 0) {
                    pillOption1.setBackgroundResource(R.drawable.bg_rail_pill_active)
                    pillOption1.setTextColor(android.graphics.Color.WHITE)
                    pillOption2.setBackgroundResource(R.drawable.bg_rail_pill_inactive)
                    pillOption2.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                } else {
                    pillOption2.setBackgroundResource(R.drawable.bg_rail_pill_active)
                    pillOption2.setTextColor(android.graphics.Color.WHITE)
                    pillOption1.setBackgroundResource(R.drawable.bg_rail_pill_inactive)
                    pillOption1.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                }

                pillOption1.setOnClickListener {
                    if (row.activePillIndex != 0) {
                        onFilterRailPillSelected(row, 0)
                    }
                }
                pillOption2.setOnClickListener {
                    if (row.activePillIndex != 1) {
                        onFilterRailPillSelected(row, 1)
                    }
                }
            } else {
                pillContainer.visibility = View.GONE
            }
        }
    }
}

class ProviderBubbleAdapter(
    private val onProviderClick: (ProviderLogo) -> Unit
) : ListAdapter<ProviderLogo, ProviderBubbleAdapter.ViewHolder>(ProviderLogoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_feed_provider_bubble, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val bubbleContainer: View = view.findViewById(R.id.bubbleContainer)
        private val tvInitial: TextView = view.findViewById(R.id.tvProviderInitial)
        private val tvName: TextView = view.findViewById(R.id.tvProviderName)
        private val ivIcon: ImageView = view.findViewById(R.id.ivProviderIcon)

        fun bind(provider: ProviderLogo) {
            tvName.text = provider.name

            val circle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(provider.brandBgColor)
                if (provider.name == "Netflix") {
                    setStroke((2f * itemView.resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#E50914"))
                } else {
                    setStroke((1.5f * itemView.resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#26FFFFFF"))
                }
            }
            bubbleContainer.background = circle

            if (provider.iconResId > 0) {
                ivIcon.visibility = View.VISIBLE
                ivIcon.setImageResource(provider.iconResId)
                tvInitial.visibility = View.GONE
            } else {
                ivIcon.visibility = View.GONE
                tvInitial.visibility = View.VISIBLE
                tvInitial.text = provider.initial
                tvInitial.setTextColor(provider.brandTextColor)
            }

            itemView.setOnClickListener { onProviderClick(provider) }
        }
    }
}

class ProviderLogoDiffCallback : DiffUtil.ItemCallback<ProviderLogo>() {
    override fun areItemsTheSame(oldItem: ProviderLogo, newItem: ProviderLogo): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: ProviderLogo, newItem: ProviderLogo): Boolean = oldItem == newItem
}

class FeedRowDiffCallback : DiffUtil.ItemCallback<FeedRow>() {
    override fun areItemsTheSame(oldItem: FeedRow, newItem: FeedRow): Boolean {
        if (oldItem::class != newItem::class) return false
        return when {
            oldItem is FeedRow.FilterableRail && newItem is FeedRow.FilterableRail -> oldItem.railId == newItem.railId
            oldItem is FeedRow.StandardRail && newItem is FeedRow.StandardRail -> oldItem.title == newItem.title
            oldItem is FeedRow.ContinueWatching && newItem is FeedRow.ContinueWatching -> oldItem.title == newItem.title
            oldItem is FeedRow.HeroCarousel && newItem is FeedRow.HeroCarousel -> true
            oldItem is FeedRow.ProviderBubbles && newItem is FeedRow.ProviderBubbles -> true
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: FeedRow, newItem: FeedRow): Boolean {
        return oldItem == newItem
    }
}
