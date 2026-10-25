package com.animarvle.app.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.activity.result.contract.ActivityResultContracts
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.data.account.GoogleAuthHelper
import com.animarvle.app.databinding.FragmentAuthLetYouInBinding
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AuthLetYouInFragment : Fragment() {

    private var _binding: FragmentAuthLetYouInBinding? = null
    private val binding get() = _binding!!

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGoogleSignInResult(result.data)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAuthLetYouInBinding.inflate(inflater, container, false)
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
            if (!findNavController().navigateUp()) {
                // If this was start destination or root
                findNavController().navigate(
                    R.id.homeFragment,
                    null,
                    navOptions { popUpTo(R.id.authLetYouInFragment) { inclusive = true } },
                )
            }
        }

        binding.btnSignInPassword.setOnClickListener {
            findNavController().navigate(R.id.loginFragment)
        }

        binding.tvSignUpLink.setOnClickListener {
            findNavController().navigate(R.id.signUpFragment)
        }

        binding.btnGoogle.setOnClickListener {
            try {
                binding.loadingOverlay.isVisible = true
                val signInIntent = GoogleAuthHelper.getSignInIntent(requireActivity())
                googleSignInLauncher.launch(signInIntent)
            } catch (e: Exception) {
                binding.loadingOverlay.isVisible = false
                Toast.makeText(
                    requireContext(),
                    "Unable to start Google Sign-In: ${e.localizedMessage ?: e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun handleGoogleSignInResult(data: android.content.Intent?) {
        val accountRepo = (requireActivity().application as AnimarvleApp).container.account
        try {
            val task = GoogleAuthHelper.getSignedInAccountFromIntent(data)
            val googleAccount = task.getResult(ApiException::class.java)
            val userAccount = accountRepo.onGoogleSignInSuccess(googleAccount)
            binding.loadingOverlay.isVisible = false
            Toast.makeText(
                requireContext(),
                "Signed in as ${userAccount.username}",
                Toast.LENGTH_SHORT,
            ).show()

            val prev = findNavController().previousBackStackEntry?.destination?.id
            if (prev == R.id.welcomeFragment || prev == null) {
                findNavController().navigate(
                    R.id.homeFragment,
                    null,
                    navOptions { popUpTo(R.id.welcomeFragment) { inclusive = true } },
                )
            } else {
                findNavController().popBackStack()
            }
        } catch (e: ApiException) {
            binding.loadingOverlay.isVisible = false
            if (e.statusCode == 12501 || e.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                return
            }
            if (e.statusCode == 10) {
                showDeveloperErrorDialog()
                return
            }
            val errorMsg = when (e.statusCode) {
                7 -> "Network error during Google Sign-In. Please check your connection."
                12500 -> "Google Sign-In failed (12500). Please verify Google Play Services."
                else -> "Google Sign-In failed (code ${e.statusCode}): ${e.message}"
            }
            Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            binding.loadingOverlay.isVisible = false
            Toast.makeText(requireContext(), "Google Sign-In error: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeveloperErrorDialog() {
        val sha1 = GoogleAuthHelper.getSigningSha1(requireContext())
        val accountRepo = (requireActivity().application as AnimarvleApp).container.account

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Google Sign-In (Code 10)")
            .setMessage(
                "Google Play Services requires registering this build's SHA-1 fingerprint in the Google Cloud Console for package:\n" +
                "com.animarvle.app\n\n" +
                "SHA-1 Fingerprint:\n$sha1\n\n" +
                "Would you like to copy the SHA-1 or proceed immediately with a Demo Google Account?"
            )
            .setPositiveButton("Use Demo Account") { _, _ ->
                lifecycleScope.launch {
                    binding.loadingOverlay.isVisible = true
                    val result = accountRepo.signInWithGoogle()
                    binding.loadingOverlay.isVisible = false
                    result.onSuccess { account ->
                        Toast.makeText(requireContext(), "Signed in as ${account.username}", Toast.LENGTH_SHORT).show()
                        val prev = findNavController().previousBackStackEntry?.destination?.id
                        if (prev == R.id.welcomeFragment || prev == null) {
                            findNavController().navigate(
                                R.id.homeFragment,
                                null,
                                navOptions { popUpTo(R.id.welcomeFragment) { inclusive = true } },
                            )
                        } else {
                            findNavController().popBackStack()
                        }
                    }
                }
            }
            .setNeutralButton("Copy SHA-1") { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("SHA-1 Fingerprint", sha1)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "SHA-1 copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
