package com.animarvle.app.ui.detail

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetSourceSelectorBinding
import com.animarvle.app.extensions.TachiyomiAnimeSource
import com.animarvle.sourceapi.MediaSource
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SourceSelectorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourceSelectorBinding? = null
    private val binding get() = _binding!!

    private var sources: List<MediaSource> = emptyList()
    private var selectedSourceId: Long? = null
    private var onSourceSelected: ((MediaSource) -> Unit)? = null

    private lateinit var adapter: SourceSelectorAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetSourceSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.installedCountBadge.text = "${sources.size} installed"

        adapter = SourceSelectorAdapter(
            selectedSourceId = selectedSourceId,
            onItemClicked = { source ->
                onSourceSelected?.invoke(source)
                dismiss()
            },
        )

        binding.sourcesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.sourcesRecyclerView.adapter = adapter
        adapter.submitList(sources)

        binding.searchSourcesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                val q = s?.toString().orEmpty().trim().lowercase()
                if (q.isBlank()) {
                    adapter.submitList(sources)
                } else {
                    adapter.submitList(sources.filter { it.name.lowercase().contains(q) })
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setSources(
        sources: List<MediaSource>,
        selectedSourceId: Long?,
        onSelected: (MediaSource) -> Unit,
    ) {
        this.sources = sources
        this.selectedSourceId = selectedSourceId
        this.onSourceSelected = onSelected
    }

    companion object {
        const val TAG = "SourceSelectorBottomSheet"

        fun newInstance(
            sources: List<MediaSource>,
            selectedSourceId: Long?,
            onSelected: (MediaSource) -> Unit,
        ): SourceSelectorBottomSheet {
            return SourceSelectorBottomSheet().apply {
                setSources(sources, selectedSourceId, onSelected)
            }
        }
    }
}

private class SourceSelectorAdapter(
    private val selectedSourceId: Long?,
    private val onItemClicked: (MediaSource) -> Unit,
) : ListAdapter<MediaSource, SourceSelectorAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source_selector, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.itemSourceIcon)
        private val name: TextView = view.findViewById(R.id.itemSourceName)
        private val engineBadge: TextView = view.findViewById(R.id.itemSourceEngineBadge)
        private val langBadge: TextView = view.findViewById(R.id.itemSourceLangBadge)
        private val nsfwBadge: TextView = view.findViewById(R.id.itemSourceNsfwBadge)
        private val radio: RadioButton = view.findViewById(R.id.itemSourceRadio)

        fun bind(source: MediaSource) {
            name.text = source.name.uppercase()
            val tachiSource = source as? TachiyomiAnimeSource
            val lang = tachiSource?.language?.uppercase() ?: "EN"
            langBadge.text = lang
            engineBadge.text = "Aniyomi"

            val isNsfw = source.name.contains("hentai", ignoreCase = true) ||
                source.name.contains("rule34", ignoreCase = true) ||
                source.name.contains("oppai", ignoreCase = true) ||
                source.name.contains("hstream", ignoreCase = true)
            nsfwBadge.isVisible = isNsfw

            radio.isChecked = source.id == selectedSourceId

            itemView.setOnClickListener {
                onItemClicked(source)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<MediaSource>() {
        override fun areItemsTheSame(oldItem: MediaSource, newItem: MediaSource) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MediaSource, newItem: MediaSource) = oldItem.id == newItem.id
    }
}
