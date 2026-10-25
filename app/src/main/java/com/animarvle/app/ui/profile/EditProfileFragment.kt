package com.animarvle.app.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentEditProfileBinding

class EditProfileFragment : Fragment() {

    private var _binding: FragmentEditProfileBinding? = null
    private val binding get() = _binding!!

    private var selectedAvatarUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEditProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams = binding.statusBarSpacer.layoutParams.apply {
                height = top
            }
            insets
        }

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        val accountRepo = (requireActivity().application as AnimarvleApp).container.account
        val currentAccount = accountRepo.currentAccount

        val initialName = currentAccount?.username ?: "Animarvle User"
        selectedAvatarUrl = currentAccount?.avatarUrl

        binding.etDisplayName.setText(initialName)
        binding.tvNamePreview.text = initialName

        updateAvatarPreview(selectedAvatarUrl, initialName)

        binding.etDisplayName.doAfterTextChanged { text ->
            val entered = text?.toString()?.trim().orEmpty()
            binding.tvNamePreview.text = entered.ifEmpty { "Username" }
        }

        val avatars = AvatarPresets.avatars
        binding.tvAvatarCount.text = getString(R.string.profile_avatars_available, avatars.size)

        val adapter = EditProfileAdapter(
            avatars = avatars,
            selectedAvatarId = selectedAvatarUrl,
            onAvatarSelected = { avatar ->
                selectedAvatarUrl = avatar.imageUrl ?: avatar.id
                updateAvatarPreview(avatar.imageUrl, binding.etDisplayName.text.toString())
            },
        )

        binding.rvAvatars.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvAvatars.adapter = adapter

        binding.btnSaveChanges.setOnClickListener {
            val newName = binding.etDisplayName.text?.toString()?.trim().orEmpty()
            if (newName.isBlank()) {
                Toast.makeText(requireContext(), "Please enter a display name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            accountRepo.updateProfile(newName, selectedAvatarUrl)
            Toast.makeText(requireContext(), getString(R.string.profile_changes_saved), Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    private fun updateAvatarPreview(avatarUrl: String?, name: String) {
        if (!avatarUrl.isNullOrBlank()) {
            binding.ivAvatarPreview.visibility = View.VISIBLE
            binding.tvAvatarPreviewInitial.visibility = View.GONE
            binding.ivAvatarPreview.load(avatarUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            binding.ivAvatarPreview.visibility = View.GONE
            binding.tvAvatarPreviewInitial.visibility = View.VISIBLE
            binding.tvAvatarPreviewInitial.text = name.take(1).uppercase()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
