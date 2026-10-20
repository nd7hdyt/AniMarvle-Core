package com.animarvle.app.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentSortFilterBinding
import com.google.android.material.chip.Chip

class SortFilterFragment : Fragment() {

    private var _binding: FragmentSortFilterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSortFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.resetButton.setOnClickListener {
            binding.categoryAll.isChecked = true
            binding.typeAll.isChecked = true
            binding.statusAll.isChecked = true
            binding.sortDefault.isChecked = true
        }

        binding.applyButton.setOnClickListener {
            val category = getSelectedText(binding.categoryGroup)
            val type = getSelectedText(binding.typeGroup)
            val status = getSelectedText(binding.statusGroup)
            val sort = getSelectedText(binding.sortGroup)

            setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    KEY_CATEGORY to category,
                    KEY_TYPE to type,
                    KEY_STATUS to status,
                    KEY_SORT to sort,
                ),
            )
            findNavController().navigateUp()
        }
    }

    private fun getSelectedText(group: com.google.android.material.chip.ChipGroup): String {
        val checkedId = group.checkedChipId
        if (checkedId == View.NO_ID) return "All"
        val chip = group.findViewById<Chip>(checkedId)
        return chip?.text?.toString() ?: "All"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY = "sort_filter_request"
        const val KEY_CATEGORY = "category"
        const val KEY_TYPE = "type"
        const val KEY_STATUS = "status"
        const val KEY_SORT = "sort"
    }
}
