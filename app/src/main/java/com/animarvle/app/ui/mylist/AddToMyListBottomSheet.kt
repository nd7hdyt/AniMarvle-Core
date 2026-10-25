package com.animarvle.app.ui.mylist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.animarvle.app.R
import com.animarvle.app.databinding.DialogAddToMyListBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class AddToMyListBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogAddToMyListBinding? = null
    private val binding get() = _binding!!

    private var mediaId: Long = 0
    private var title: String = ""
    private var posterUrl: String? = null
    private var format: String? = null
    private var score: Double? = null

    private var totalEpisodes: Int? = null
    private var year: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mediaId = it.getLong(ARG_MEDIA_ID, 0)
            title = it.getString(ARG_TITLE, "")
            posterUrl = it.getString(ARG_POSTER)
            format = it.getString(ARG_FORMAT)
            score = if (it.containsKey(ARG_SCORE)) it.getDouble(ARG_SCORE) else null
            totalEpisodes = if (it.containsKey(ARG_TOTAL_EPISODES)) it.getInt(ARG_TOTAL_EPISODES) else null
            year = if (it.containsKey(ARG_YEAR)) it.getInt(ARG_YEAR) else null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogAddToMyListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvMediaTitle.text = title

        val repository = (requireActivity().application as com.animarvle.app.AnimarvleApp).container.myList

        viewLifecycleOwner.lifecycleScope.launch {
            val currentStatus = repository.getStatus(mediaId)
            binding.btnRemove.isVisible = (currentStatus != null)
            highlightActiveStatus(currentStatus)
        }

        fun saveStatus(status: MyListStatus) {
            viewLifecycleOwner.lifecycleScope.launch {
                val existing = repository.itemsFlow.value.firstOrNull { it.mediaId == mediaId }
                val item = MyListItem(
                    mediaId = mediaId,
                    title = title.ifBlank { existing?.title ?: "" },
                    posterUrl = posterUrl ?: existing?.posterUrl,
                    format = format ?: existing?.format,
                    score = score ?: existing?.score,
                    status = status,
                    progress = existing?.progress ?: 0,
                    totalEpisodes = totalEpisodes ?: existing?.totalEpisodes,
                    year = year ?: existing?.year,
                )
                repository.upsert(item)
                Toast.makeText(
                    requireContext(),
                    "Added to My List (${status.label})",
                    Toast.LENGTH_SHORT,
                ).show()
                dismiss()
            }
        }

        binding.btnPending.setOnClickListener { saveStatus(MyListStatus.PLAN_TO_WATCH) }
        binding.btnWatching.setOnClickListener { saveStatus(MyListStatus.WATCHING) }
        binding.btnCompleted.setOnClickListener { saveStatus(MyListStatus.COMPLETED) }

        binding.btnRemove.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.remove(mediaId)
                Toast.makeText(requireContext(), getString(R.string.my_list_removed_toast), Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    private fun highlightActiveStatus(currentStatus: MyListStatus?) {
        val isPending = currentStatus == MyListStatus.PLAN_TO_WATCH || currentStatus?.key == "pending"
        val isWatching = currentStatus == MyListStatus.WATCHING
        val isCompleted = currentStatus == MyListStatus.COMPLETED

        updateOptionUi(
            container = binding.btnPending,
            icon = binding.ivPendingIcon,
            title = binding.tvPendingTitle,
            subtitle = binding.tvPendingSubtitle,
            check = binding.ivPendingCheck,
            isSelected = isPending
        )

        updateOptionUi(
            container = binding.btnWatching,
            icon = binding.ivWatchingIcon,
            title = binding.tvWatchingTitle,
            subtitle = binding.tvWatchingSubtitle,
            check = binding.ivWatchingCheck,
            isSelected = isWatching
        )

        updateOptionUi(
            container = binding.btnCompleted,
            icon = binding.ivCompletedIcon,
            title = binding.tvCompletedTitle,
            subtitle = binding.tvCompletedSubtitle,
            check = binding.ivCompletedCheck,
            isSelected = isCompleted
        )
    }

    private fun updateOptionUi(
        container: View,
        icon: android.widget.ImageView,
        title: android.widget.TextView,
        subtitle: android.widget.TextView,
        check: android.widget.ImageView,
        isSelected: Boolean
    ) {
        if (isSelected) {
            container.setBackgroundResource(R.drawable.bg_mylist_option_selected)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
            title.setTextColor(android.graphics.Color.BLACK)
            subtitle.setTextColor(android.graphics.Color.parseColor("#444444"))
            check.isVisible = true
            check.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
        } else {
            container.setBackgroundResource(R.drawable.bg_mylist_option_unselected)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            title.setTextColor(android.graphics.Color.WHITE)
            subtitle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
            check.isVisible = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddToMyListBottomSheet"
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_TITLE = "title"
        private const val ARG_POSTER = "poster"
        private const val ARG_FORMAT = "format"
        private const val ARG_SCORE = "score"
        private const val ARG_TOTAL_EPISODES = "total_episodes"
        private const val ARG_YEAR = "year"

        fun newInstance(
            mediaId: Long,
            title: String,
            posterUrl: String?,
            format: String? = null,
            score: Double? = null,
            totalEpisodes: Int? = null,
            year: Int? = null,
        ): AddToMyListBottomSheet {
            return AddToMyListBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                    putString(ARG_TITLE, title)
                    putString(ARG_POSTER, posterUrl)
                    putString(ARG_FORMAT, format)
                    if (score != null) putDouble(ARG_SCORE, score)
                    if (totalEpisodes != null) putInt(ARG_TOTAL_EPISODES, totalEpisodes)
                    if (year != null) putInt(ARG_YEAR, year)
                }
            }
        }
    }
}
