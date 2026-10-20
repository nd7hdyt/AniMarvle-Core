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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mediaId = it.getLong(ARG_MEDIA_ID, 0)
            title = it.getString(ARG_TITLE, "")
            posterUrl = it.getString(ARG_POSTER)
            format = it.getString(ARG_FORMAT)
            score = if (it.containsKey(ARG_SCORE)) it.getDouble(ARG_SCORE) else null
            totalEpisodes = if (it.containsKey(ARG_TOTAL_EPISODES)) it.getInt(ARG_TOTAL_EPISODES) else null
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

        val repository = (requireActivity().application as com.animarvle.app.AnimarvleApp).container.myList

        viewLifecycleOwner.lifecycleScope.launch {
            val currentStatus = repository.getStatus(mediaId)
            binding.btnRemove.isVisible = (currentStatus != null)
            highlightActiveStatus(currentStatus)
        }

        fun saveStatus(status: MyListStatus) {
            viewLifecycleOwner.lifecycleScope.launch {
                val item = MyListItem(
                    mediaId = mediaId,
                    title = title,
                    posterUrl = posterUrl,
                    format = format,
                    score = score,
                    status = status,
                    totalEpisodes = totalEpisodes,
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

        binding.btnWatching.setOnClickListener { saveStatus(MyListStatus.WATCHING) }
        binding.btnPlanToWatch.setOnClickListener { saveStatus(MyListStatus.PLAN_TO_WATCH) }
        binding.btnCompleted.setOnClickListener { saveStatus(MyListStatus.COMPLETED) }
        binding.btnOnHold.setOnClickListener { saveStatus(MyListStatus.ON_HOLD) }
        binding.btnDropped.setOnClickListener { saveStatus(MyListStatus.DROPPED) }

        binding.btnRemove.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.remove(mediaId)
                Toast.makeText(requireContext(), getString(R.string.my_list_removed_toast), Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    private fun highlightActiveStatus(currentStatus: MyListStatus?) {
        val buttons = mapOf(
            MyListStatus.WATCHING to binding.btnWatching,
            MyListStatus.PLAN_TO_WATCH to binding.btnPlanToWatch,
            MyListStatus.COMPLETED to binding.btnCompleted,
            MyListStatus.ON_HOLD to binding.btnOnHold,
            MyListStatus.DROPPED to binding.btnDropped,
        )

        buttons.forEach { (status, button) ->
            if (status == currentStatus) {
                button.strokeWidth = 2
                button.strokeColor = resources.getColorStateList(R.color.green_accent, null)
            } else {
                button.strokeWidth = 0
            }
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

        fun newInstance(
            mediaId: Long,
            title: String,
            posterUrl: String?,
            format: String? = null,
            score: Double? = null,
            totalEpisodes: Int? = null,
        ): AddToMyListBottomSheet {
            return AddToMyListBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                    putString(ARG_TITLE, title)
                    putString(ARG_POSTER, posterUrl)
                    putString(ARG_FORMAT, format)
                    if (score != null) putDouble(ARG_SCORE, score)
                    if (totalEpisodes != null) putInt(ARG_TOTAL_EPISODES, totalEpisodes)
                }
            }
        }
    }
}
