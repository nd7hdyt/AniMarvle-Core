package com.animarvle.app.ui.player

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetServerSelectionBinding
import com.animarvle.app.databinding.ItemServerSelectionBinding
import com.animarvle.app.ui.theme.AppTheme
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.ServerOption
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Modern "Server for this episode" bottom sheet selector matching Mizuki's architecture:
 * - Subtitle with choices count
 * - Language checking / ready status indicator
 * - Segmented tabs: [ All ] [ Sub ] [ Dub ]
 * - Horizontal language chips: [ Any ] [ English ] [ Hindi ] [ Arabic ] [ Spanish ] [ Japanese ]
 * - Server item cards with "Playing now", "Fast", "Can download", Subbed/Dubbed, and language badges.
 */
class ServerSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetServerSelectionBinding? = null
    private val binding get() = _binding!!

    private var allServers: List<ServerOption> = emptyList()
    private var selectedKey: String? = null
    private var currentAudio: Audio = Audio.SUB
    private var onServerPicked: ((serverKey: String, targetAudio: Audio?) -> Unit)? = null

    // Filter states
    private var activeCategory: CategoryFilter = CategoryFilter.ALL
    private var activeLanguage: String = "Any"

    private lateinit var adapter: ServerAdapter

    enum class CategoryFilter { ALL, SUB, DUB }

    data class ServerUiModel(
        val key: String,
        val name: String,
        val hint: String,
        val isPlayingNow: Boolean,
        val isFast: Boolean = true,
        val canDownload: Boolean = true,
        val audioType: Audio,
        val language: String,
    )

    fun setup(
        servers: List<ServerOption>,
        selectedServerKey: String?,
        audio: Audio,
        onPick: (serverKey: String, targetAudio: Audio?) -> Unit,
    ) {
        this.allServers = servers
        this.selectedKey = selectedServerKey
        this.currentAudio = audio
        this.onServerPicked = onPick
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetServerSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener { dismiss() }

        // Setup RecyclerView
        adapter = ServerAdapter { picked ->
            onServerPicked?.invoke(picked.key, picked.audioType)
            dismiss()
        }
        binding.recyclerServers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerServers.adapter = adapter

        setupCategoryTabs()
        setupLanguageChips()
        applyFilters()
    }

    private fun setupCategoryTabs() {
        val accent = AppTheme.accent(requireContext())
        val muted = ContextCompat.getColor(requireContext(), R.color.on_bg_muted)
        val selectedBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_segmented_item_selected)

        fun updateTabUi(tab: CategoryFilter) {
            activeCategory = tab
            binding.tabAll.apply {
                background = if (tab == CategoryFilter.ALL) selectedBg else null
                setTextColor(if (tab == CategoryFilter.ALL) Color.BLACK else muted)
            }
            binding.tabSub.apply {
                background = if (tab == CategoryFilter.SUB) selectedBg else null
                setTextColor(if (tab == CategoryFilter.SUB) Color.BLACK else muted)
            }
            binding.tabDub.apply {
                background = if (tab == CategoryFilter.DUB) selectedBg else null
                setTextColor(if (tab == CategoryFilter.DUB) Color.BLACK else muted)
            }
            applyFilters()
        }

        binding.tabAll.setOnClickListener { updateTabUi(CategoryFilter.ALL) }
        binding.tabSub.setOnClickListener { updateTabUi(CategoryFilter.SUB) }
        binding.tabDub.setOnClickListener { updateTabUi(CategoryFilter.DUB) }
    }

    private fun setupLanguageChips() {
        val chips = listOf(
            binding.chipAny to "Any",
            binding.chipEnglish to "English",
            binding.chipHindi to "Hindi",
            binding.chipArabic to "Arabic",
            binding.chipSpanish to "Spanish",
            binding.chipJapanese to "Japanese",
        )

        chips.forEach { (view, lang) ->
            view.setOnClickListener {
                activeLanguage = lang
                chips.forEach { (v, l) ->
                    val isSel = (l == activeLanguage)
                    v.isSelected = isSel
                    v.setTextColor(
                        if (isSel) Color.WHITE
                        else ContextCompat.getColor(requireContext(), R.color.on_bg_muted),
                    )
                }
                applyFilters()
            }
        }
        binding.chipAny.isSelected = true
    }

    private fun applyFilters() {
        val parsed = allServers.map { opt ->
            val labelLower = opt.label.lowercase()
            val isDub = labelLower.contains("dub") || opt.audioLanguage.equals("dub", ignoreCase = true)
            val audioType = if (isDub) Audio.DUB else Audio.SUB

            val detectedLang = when {
                opt.audioLanguage?.isNotBlank() == true -> opt.audioLanguage!!
                labelLower.contains("chinese") || labelLower.contains("mandarin") || labelLower.contains("donghua") -> "Chinese"
                labelLower.contains("korean") || labelLower.contains("k-drama") -> "Korean"
                labelLower.contains("hindi") -> "Hindi"
                labelLower.contains("arabic") -> "Arabic"
                labelLower.contains("spanish") || labelLower.contains("es-") -> "Spanish"
                labelLower.contains("japanese") || labelLower.contains("raw") -> "Japanese"
                else -> "English"
            }

            val isCurrent = opt.key == selectedKey

            ServerUiModel(
                key = opt.key,
                name = opt.label,
                hint = opt.hint ?: if (isDub) "Dubbed · Fast · 1080p" else "Auto · Direct HLS · 1080p",
                isPlayingNow = isCurrent,
                isFast = true,
                canDownload = true,
                audioType = audioType,
                language = detectedLang,
            )
        }

        val filtered = parsed.filter { item ->
            val categoryMatches = when (activeCategory) {
                CategoryFilter.ALL -> true
                CategoryFilter.SUB -> item.audioType == Audio.SUB
                CategoryFilter.DUB -> item.audioType == Audio.DUB
            }
            val languageMatches = (activeLanguage == "Any" || item.language.equals(activeLanguage, ignoreCase = true))
            categoryMatches && languageMatches
        }

        binding.textSubtitle.text = getString(R.string.server_sheet_subtitle, filtered.size)
        binding.emptyServersView.isVisible = filtered.isEmpty()
        binding.recyclerServers.isVisible = filtered.isNotEmpty()
        adapter.submitList(filtered)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ServerAdapter(
        private val onPick: (ServerUiModel) -> Unit,
    ) : RecyclerView.Adapter<ServerAdapter.Holder>() {

        private val items = mutableListOf<ServerUiModel>()

        fun submitList(newItems: List<ServerUiModel>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemServerSelectionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemServerSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            val accent = AppTheme.accent(ctx)

            holder.binding.textServerName.text = item.name
            holder.binding.textServerHint.text = item.hint

            // Playing now badge
            holder.binding.badgePlayingNow.isVisible = item.isPlayingNow

            // Audio & Language badges
            holder.binding.badgeAudio.text = if (item.audioType == Audio.DUB) "Dubbed" else "Subbed"
            holder.binding.badgeLanguage.text = item.language

            // Selected card style & radio check
            holder.binding.serverCard.isSelected = item.isPlayingNow
            holder.binding.radioCheck.setImageResource(
                if (item.isPlayingNow) R.drawable.ic_circle_checked else R.drawable.ic_circle_unchecked,
            )
            if (item.isPlayingNow) {
                holder.binding.radioCheck.imageTintList = ColorStateList.valueOf(accent)
            } else {
                holder.binding.radioCheck.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.on_bg_muted),
                )
            }

            holder.itemView.setOnClickListener { onPick(item) }
        }
    }

    companion object {
        fun newInstance(
            servers: List<ServerOption>,
            selectedKey: String?,
            audio: Audio,
            onPick: (serverKey: String, targetAudio: Audio?) -> Unit,
        ): ServerSelectionBottomSheet = ServerSelectionBottomSheet().apply {
            setup(servers, selectedKey, audio, onPick)
        }
    }
}
