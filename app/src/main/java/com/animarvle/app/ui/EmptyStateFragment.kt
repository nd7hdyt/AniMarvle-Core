package com.animarvle.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.animarvle.app.databinding.FragmentEmptyStateBinding

/**
 * Shared shell screen for the five bottom-nav tabs (PROMPT §6 empty-state component).
 * Real content replaces this in later milestones; M1 only proves the tab shell and themes.
 */
abstract class EmptyStateFragment(
    @StringRes private val titleRes: Int,
    @StringRes private val emptyTitleRes: Int,
    @StringRes private val emptyBodyRes: Int,
) : Fragment() {

    private var _binding: FragmentEmptyStateBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEmptyStateBinding.inflate(inflater, container, false)
        binding.toolbarTitle.setText(titleRes)
        binding.emptyTitle.setText(emptyTitleRes)
        binding.emptyBody.setText(emptyBodyRes)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
