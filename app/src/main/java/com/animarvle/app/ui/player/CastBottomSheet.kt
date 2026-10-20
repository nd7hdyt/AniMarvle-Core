package com.animarvle.app.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.animarvle.app.databinding.DialogCastSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CastBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogCastSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogCastSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener { dismiss() }

        // Simulate local network device discovery with graceful empty state fallback
        viewLifecycleOwner.lifecycleScope.launch {
            delay(2200)
            if (_binding != null) {
                binding.scanningContainer.isVisible = false
                binding.emptyDevicesText.isVisible = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CastBottomSheet"

        fun newInstance(): CastBottomSheet = CastBottomSheet()
    }
}
