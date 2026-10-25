package com.animarvle.app.ui.auth

import android.os.Bundle
import android.text.InputType
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
import com.animarvle.app.databinding.FragmentLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private var isPasswordVisible = false

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
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
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

        val accountRepo = (requireActivity().application as AnimarvleApp).container.account

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                binding.etPassword.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility)
            } else {
                binding.etPassword.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility_off)
            }
            binding.etPassword.setSelection(binding.etPassword.text.length)
        }

        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "Password reset link sent to registered email",
                Toast.LENGTH_LONG,
            ).show()
        }

        binding.tvSignUpLink.setOnClickListener {
            findNavController().navigate(R.id.signUpFragment)
        }

        binding.btnSignIn.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty()) {
                binding.tvError.text = getString(R.string.auth_error_enter_email)
                binding.tvError.isVisible = true
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                binding.tvError.text = getString(R.string.auth_error_enter_password)
                binding.tvError.isVisible = true
                return@setOnClickListener
            }

            binding.tvError.isVisible = false
            binding.loadingOverlay.isVisible = true

            lifecycleScope.launch {
                val result = accountRepo.signInWithPassword(email, password)
                binding.loadingOverlay.isVisible = false
                result.onSuccess { account ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.auth_welcome_back, account.username),
                        Toast.LENGTH_SHORT,
                    ).show()

                    navigateAfterAuth()
                }.onFailure { err ->
                    binding.tvError.text = err.message ?: "Authentication failed"
                    binding.tvError.isVisible = true
                }
            }
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
            navigateAfterAuth()
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
                        navigateAfterAuth()
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

    private fun navigateAfterAuth() {
        // Pop back to either home or profile depending on stack
        val hasProfileInStack = try {
            findNavController().getBackStackEntry(R.id.profileFragment)
            true
        } catch (_: Exception) {
            false
        }

        if (hasProfileInStack) {
            findNavController().popBackStack(R.id.profileFragment, false)
        } else {
            findNavController().navigate(
                R.id.homeFragment,
                null,
                navOptions {
                    popUpTo(R.id.nav_main) { inclusive = true }
                },
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
