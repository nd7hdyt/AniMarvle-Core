package com.animarvle.app.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentWelcomeBinding

class WelcomeFragment : Fragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val accountRepo = (requireActivity().application as AnimarvleApp).container.account

        binding.btnWatchNow.setOnClickListener {
            accountRepo.setOnboardingCompleted(true)
            findNavController().navigate(
                R.id.homeFragment,
                null,
                navOptions {
                    popUpTo(R.id.welcomeFragment) { inclusive = true }
                },
            )
        }

        binding.btnSignIn.setOnClickListener {
            findNavController().navigate(R.id.authLetYouInFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
