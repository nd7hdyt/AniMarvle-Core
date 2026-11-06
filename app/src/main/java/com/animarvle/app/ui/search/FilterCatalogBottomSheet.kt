package com.animarvle.app.ui.search

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetFilterCatalogBinding
import com.animarvle.app.databinding.ItemFilterGenreChipBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip

class FilterCatalogBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetFilterCatalogBinding? = null
    private val binding get() = _binding!!

    private var draftFilters: SearchFilterOptions = SearchFilterOptions()
    var onFilterListener: ((SearchFilterOptions) -> Unit)? = null

    private var activePopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        draftFilters = arguments?.getSerializable(ARG_FILTERS) as? SearchFilterOptions ?: SearchFilterOptions()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetFilterCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                val displayMetrics = resources.displayMetrics
                val targetHeight = (displayMetrics.heightPixels * 0.85f).toInt()
                sheet.layoutParams.height = targetHeight
                behavior.maxHeight = targetHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isFitToContents = true
                sheet.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupRowClicks()
        setupGenres()
        setupActions()
        renderDraftValues()
        updateHeaderSummary()
    }

    private fun setupTabs() {
        binding.tabGeneral.setOnClickListener { switchTab(Tab.GENERAL) }
        binding.tabRelease.setOnClickListener { switchTab(Tab.RELEASE) }
        binding.tabGenres.setOnClickListener { switchTab(Tab.GENRES) }
        binding.tabCast.setOnClickListener { switchTab(Tab.CAST) }
        binding.tabThemes.setOnClickListener { switchTab(Tab.THEMES) }
    }

    private enum class Tab { GENERAL, RELEASE, GENRES, CAST, THEMES }

    private fun switchTab(tab: Tab) {
        val tabs = listOf(
            Triple(binding.tabGeneralText, binding.tabGeneralIndicator, binding.pageGeneral),
            Triple(binding.tabReleaseText, binding.tabReleaseIndicator, binding.pageRelease),
            Triple(binding.tabGenresText, binding.tabGenresIndicator, binding.pageGenres),
            Triple(binding.tabCastText, binding.tabCastIndicator, binding.pageCast),
            Triple(binding.tabThemesText, binding.tabThemesIndicator, binding.pageThemes),
        )

        val selectedIndex = tab.ordinal
        for (i in tabs.indices) {
            val (textView, indicator, page) = tabs[i]
            val isSelected = (i == selectedIndex)
            textView.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#8E8E93"))
            indicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            page.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    private fun setupRowClicks() {
        // Sort by
        binding.rowSortBy.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.SORT_OPTIONS.map { PopupItem(it) }, draftFilters.sort) { sel ->
                draftFilters = draftFilters.copy(sort = sel)
                binding.valSortBy.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Format
        binding.rowFormat.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.FORMAT_OPTIONS.map { PopupItem(it) }, draftFilters.format) { sel ->
                draftFilters = draftFilters.copy(format = sel)
                binding.valFormat.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Status
        binding.rowStatus.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.STATUS_OPTIONS.map { PopupItem(it) }, draftFilters.status) { sel ->
                draftFilters = draftFilters.copy(status = sel)
                binding.valStatus.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Episodes
        binding.rowEpisodes.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.EPISODE_OPTIONS.map { PopupItem(it) }, draftFilters.episodes) { sel ->
                draftFilters = draftFilters.copy(episodes = sel)
                binding.valEpisodes.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Min Rating
        binding.rowMinRating.setOnClickListener { anchor ->
            val curLabel = if (draftFilters.minRating > 0.0) "${draftFilters.minRating}+ ⭐" else "Any"
            showOptionPopup(anchor, SearchFilterData.RATING_OPTIONS.map { PopupItem(it) }, curLabel) { sel ->
                val ratingVal = sel.substringBefore("+").substringBefore(" ").toDoubleOrNull() ?: 0.0
                draftFilters = draftFilters.copy(minRating = ratingVal)
                binding.valMinRating.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Dubbed switch
        binding.switchDubbed.setOnCheckedChangeListener { _, isChecked ->
            draftFilters = draftFilters.copy(dubbedOnly = isChecked)
            updateHeaderSummary()
        }

        // Watched
        binding.rowWatched.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.WATCH_OPTIONS.map { PopupItem(it) }, draftFilters.watchedOnly) { sel ->
                draftFilters = draftFilters.copy(watchedOnly = sel)
                binding.valWatched.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Year (with Decades and Years section headers)
        binding.rowYear.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.YEAR_POPUP_ITEMS, draftFilters.year) { sel ->
                draftFilters = draftFilters.copy(year = sel)
                binding.valYear.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Season
        binding.rowSeason.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.SEASON_OPTIONS.map { PopupItem(it) }, draftFilters.season) { sel ->
                draftFilters = draftFilters.copy(season = sel)
                binding.valSeason.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Director
        binding.rowDirector.setOnClickListener { anchor ->
            val options = listOf(PopupItem("Any")) + SearchFilterData.DIRECTORS.keys.map { PopupItem(it) }
            showOptionPopup(anchor, options, draftFilters.director ?: "Any") { sel ->
                draftFilters = draftFilters.copy(director = if (sel == "Any") null else sel)
                binding.valDirector.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Male Actor
        binding.rowMaleActor.setOnClickListener { anchor ->
            val options = listOf(PopupItem("Any")) + SearchFilterData.MALE_ACTORS.keys.map { PopupItem(it) }
            showOptionPopup(anchor, options, draftFilters.maleActor ?: "Any") { sel ->
                draftFilters = draftFilters.copy(maleActor = if (sel == "Any") null else sel)
                binding.valMaleActor.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Female Actress
        binding.rowFemaleActress.setOnClickListener { anchor ->
            val options = listOf(PopupItem("Any")) + SearchFilterData.FEMALE_ACTRESSES.keys.map { PopupItem(it) }
            showOptionPopup(anchor, options, draftFilters.femaleActress ?: "Any") { sel ->
                draftFilters = draftFilters.copy(femaleActress = if (sel == "Any") null else sel)
                binding.valFemaleActress.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Studio
        binding.rowStudio.setOnClickListener { anchor ->
            val options = listOf(PopupItem("Any")) + SearchFilterData.STUDIOS.keys.map { PopupItem(it) }
            showOptionPopup(anchor, options, draftFilters.studio ?: "Any") { sel ->
                draftFilters = draftFilters.copy(studio = if (sel == "Any") null else sel)
                binding.valStudio.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Romance Type
        binding.rowRomanceType.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.ROMANCE_TYPES.map { PopupItem(it) }, draftFilters.romanceType) { sel ->
                draftFilters = draftFilters.copy(romanceType = sel)
                binding.valRomanceType.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Series Type
        binding.rowSeriesType.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.SERIES_TYPES.map { PopupItem(it) }, draftFilters.seriesType) { sel ->
                draftFilters = draftFilters.copy(seriesType = sel)
                binding.valSeriesType.text = "$sel ›"
                updateHeaderSummary()
            }
        }

        // Adult Content (18+)
        binding.rowAdultContent.setOnClickListener { anchor ->
            showOptionPopup(anchor, SearchFilterData.ADULT_OPTIONS.map { PopupItem(it) }, draftFilters.adultContent) { sel ->
                draftFilters = draftFilters.copy(adultContent = sel)
                binding.valAdultContent.text = "$sel ›"
                updateHeaderSummary()
            }
        }
    }

    private fun setupGenres() {
        val allGenres = (SearchFilterData.MOVIE_GENRES.keys + SearchFilterData.ANIME_GENRES)
            .distinct()
            .sorted()

        binding.chipGroupGenres.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (genre in allGenres) {
            val chipBinding = ItemFilterGenreChipBinding.inflate(inflater, binding.chipGroupGenres, false)
            val chip = chipBinding.root
            chip.text = genre
            chip.isChecked = draftFilters.genres.contains(genre)
            chip.setOnCheckedChangeListener { _, isChecked ->
                val updated = draftFilters.genres.toMutableSet()
                if (isChecked) updated.add(genre) else updated.remove(genre)
                draftFilters = draftFilters.copy(genres = updated)
                updateHeaderSummary()
            }
            binding.chipGroupGenres.addView(chip)
        }
    }

    private fun setupActions() {
        binding.btnClearFilters.setOnClickListener {
            draftFilters = SearchFilterOptions()
            renderDraftValues()
            // uncheck all chips
            for (i in 0 until binding.chipGroupGenres.childCount) {
                (binding.chipGroupGenres.getChildAt(i) as? Chip)?.isChecked = false
            }
            updateHeaderSummary()
        }

        binding.btnShowResults.setOnClickListener {
            onFilterListener?.invoke(draftFilters)
            dismiss()
        }
    }

    private fun renderDraftValues() {
        binding.valSortBy.text = "${draftFilters.sort} ›"
        binding.valFormat.text = "${draftFilters.format} ›"
        binding.valStatus.text = "${draftFilters.status} ›"
        binding.valEpisodes.text = "${draftFilters.episodes} ›"
        binding.valMinRating.text = if (draftFilters.minRating > 0.0) "${draftFilters.minRating}+ ⭐ ›" else "Any ›"
        binding.switchDubbed.isChecked = draftFilters.dubbedOnly
        binding.valWatched.text = "${draftFilters.watchedOnly} ›"
        binding.valYear.text = "${draftFilters.year} ›"
        binding.valSeason.text = "${draftFilters.season} ›"
        binding.valDirector.text = "${draftFilters.director ?: "Any"} ›"
        binding.valMaleActor.text = "${draftFilters.maleActor ?: "Any"} ›"
        binding.valFemaleActress.text = "${draftFilters.femaleActress ?: "Any"} ›"
        binding.valStudio.text = "${draftFilters.studio ?: "Any"} ›"
        binding.valRomanceType.text = "${draftFilters.romanceType} ›"
        binding.valSeriesType.text = "${draftFilters.seriesType} ›"
        binding.valAdultContent.text = "${draftFilters.adultContent} ›"
    }

    private fun updateHeaderSummary() {
        val count = draftFilters.activeFilterCount
        if (count > 0) {
            binding.tvMatchCount.text = "$count filter(s) active"
        } else {
            binding.tvMatchCount.text = "Select filters to narrow results"
        }
    }

    private fun showOptionPopup(
        anchor: View,
        entries: List<PopupItem>,
        currentSelection: String,
        onSelected: (String) -> Unit,
    ) {
        activePopup?.dismiss()

        val context = requireContext()
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_filter_menu, null)
        val rv = popupView.findViewById<RecyclerView>(R.id.rvPopupOptions)

        val popupWidth = dp(200)

        // Calculate available vertical space so popup remains firmly bounded
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val anchorY = location[1]
        val screenHeight = resources.displayMetrics.heightPixels
        val bottomMargin = dp(84) // space for "Show results" bar
        val availableBelow = screenHeight - anchorY - anchor.height - bottomMargin
        val availableAbove = anchorY - dp(80)

        val itemH = dp(44)
        val headerH = dp(30)
        val totalContentH = entries.sumOf { if (it.isHeader) headerH else itemH } + dp(12)

        val (popupHeight, yOffset) = if (availableBelow >= dp(200) || availableBelow >= availableAbove) {
            val h = minOf(totalContentH, minOf(dp(340), availableBelow.coerceAtLeast(dp(200))))
            h to 0
        } else {
            val h = minOf(totalContentH, minOf(dp(340), availableAbove.coerceAtLeast(dp(200))))
            h to -(h + anchor.height)
        }

        val popup = PopupWindow(
            popupView,
            popupWidth,
            popupHeight,
            true,
        ).apply {
            elevation = dp(18).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        // CRITICAL: Intercept touch events so list scrolls in place and never drags parent bottom sheet
        rv.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemViewType(position: Int): Int = if (entries[position].isHeader) 0 else 1

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                return if (viewType == 0) {
                    val view = inflater.inflate(R.layout.item_filter_popup_header, parent, false)
                    object : RecyclerView.ViewHolder(view) {}
                } else {
                    val view = inflater.inflate(R.layout.item_filter_popup_option, parent, false)
                    object : RecyclerView.ViewHolder(view) {}
                }
            }

            override fun getItemCount(): Int = entries.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = entries[position]
                if (item.isHeader) {
                    val tv = holder.itemView.findViewById<TextView>(R.id.tvHeaderTitle)
                    tv.text = item.title
                } else {
                    val tv = holder.itemView.findViewById<TextView>(R.id.tvOptionTitle)
                    val check = holder.itemView.findViewById<ImageView>(R.id.ivOptionCheck)
                    tv.text = item.title
                    val isSelected = item.title.equals(currentSelection, ignoreCase = true)
                    check.isVisible = isSelected
                    tv.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#E5E5EA"))

                    holder.itemView.setOnClickListener {
                        onSelected(item.title)
                        popup.dismiss()
                    }
                }
            }
        }

        // Scroll to selected position initially so the selected item is in view
        val selectedIndex = entries.indexOfFirst { !it.isHeader && it.title.equals(currentSelection, ignoreCase = true) }
        if (selectedIndex > 3) {
            rv.scrollToPosition(selectedIndex)
        }

        activePopup = popup
        val xOffset = anchor.width - popupWidth
        popup.showAsDropDown(anchor, xOffset, yOffset)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        activePopup?.dismiss()
        activePopup = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "FilterCatalogBottomSheet"
        private const val ARG_FILTERS = "arg_filters"

        fun newInstance(current: SearchFilterOptions): FilterCatalogBottomSheet {
            return FilterCatalogBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_FILTERS, current)
                }
            }
        }
    }
}
