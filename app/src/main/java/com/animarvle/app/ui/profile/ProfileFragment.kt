package com.animarvle.app.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentProfileBinding
import com.animarvle.app.ui.theme.AppTheme
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
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

        binding.toolbarTitle.setText(R.string.nav_profile)

        val accountRepo = (requireActivity().application as com.animarvle.app.AnimarvleApp).container.account

        binding.continueButton.setOnClickListener {
            findNavController().navigate(R.id.authLetYouInFragment)
        }

        binding.btnSyncNow.setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(),
                "Cloud library synchronized with ${accountRepo.currentAccount?.provider ?: "Account"}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        binding.btnSignOut.setOnClickListener {
            accountRepo.signOut(requireContext())
            android.widget.Toast.makeText(requireContext(), getString(R.string.auth_signed_out), android.widget.Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                accountRepo.accountFlow.collect { account ->
                    if (account != null && account.isLoggedIn) {
                        binding.loggedOutLayout.visibility = View.GONE
                        binding.loggedInLayout.visibility = View.VISIBLE
                        binding.tvUserName.text = account.username
                        binding.tvUserEmail.text = account.email
                        binding.tvSyncStatus.text = "✓ ${account.provider} Synced"

                        val avatar = account.avatarUrl
                        if (!avatar.isNullOrBlank()) {
                            binding.userAvatarImage.visibility = View.VISIBLE
                            binding.userAvatarInitial.visibility = View.GONE
                            binding.userAvatarImage.load(avatar) {
                                crossfade(true)
                                transformations(CircleCropTransformation())
                            }
                        } else {
                            binding.userAvatarImage.visibility = View.GONE
                            binding.userAvatarInitial.visibility = View.VISIBLE
                            binding.userAvatarInitial.text = account.username.take(1).uppercase()
                        }
                    } else {
                        binding.loggedOutLayout.visibility = View.VISIBLE
                        binding.loggedInLayout.visibility = View.GONE
                    }
                }
            }
        }

        binding.interfaceSettingsRow.setOnClickListener {
            findNavController().navigate(R.id.interfaceFragment)
        }

        binding.playerSettingsRow.setOnClickListener {
            findNavController().navigate(R.id.playerSettingsFragment)
        }

        binding.subtitleSettingsRow.setOnClickListener {
            findNavController().navigate(R.id.subtitleSettingsFragment)
        }

        binding.sourcesRow.setOnClickListener {
            findNavController().navigate(R.id.extensionManagerFragment)
        }

        binding.aboutRow.setOnClickListener {
            findNavController().navigate(R.id.aboutFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            val paletteName = AppTheme.nameFor(AppTheme.palette(requireContext()))
            binding.interfaceSubtitle.text = "$paletteName • Theme & language"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
