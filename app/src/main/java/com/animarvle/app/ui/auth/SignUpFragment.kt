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
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentSignUpBinding
import kotlinx.coroutines.launch

class SignUpFragment : Fragment() {

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!
    private var isPasswordVisible = false
    private var isConfirmPasswordVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
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

        binding.btnToggleConfirmPassword.setOnClickListener {
            isConfirmPasswordVisible = !isConfirmPasswordVisible
            if (isConfirmPasswordVisible) {
                binding.etConfirmPassword.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                binding.btnToggleConfirmPassword.setImageResource(R.drawable.ic_visibility)
            } else {
                binding.etConfirmPassword.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.btnToggleConfirmPassword.setImageResource(R.drawable.ic_visibility_off)
            }
            binding.etConfirmPassword.setSelection(binding.etConfirmPassword.text.length)
        }

        binding.tvSignInLink.setOnClickListener {
            if (!findNavController().popBackStack(R.id.loginFragment, false)) {
                findNavController().navigate(R.id.loginFragment)
            }
        }

        binding.btnSignUp.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val confirm = binding.etConfirmPassword.text.toString().trim()

            if (username.isEmpty()) {
                binding.tvError.text = getString(R.string.auth_error_enter_username)
                binding.tvError.isVisible = true
                return@setOnClickListener
            }
            if (email.isEmpty()) {
                binding.tvError.text = getString(R.string.auth_error_enter_email)
                binding.tvError.isVisible = true
                return@setOnClickListener
            }
            if (password.length < 6) {
                binding.tvError.text = getString(R.string.auth_error_password_min_6)
                binding.tvError.isVisible = true
                return@setOnClickListener
            }
            if (password != confirm) {
                binding.tvError.text = getString(R.string.auth_error_password_mismatch)
                binding.tvError.isVisible = true
                return@setOnClickListener
            }

            binding.tvError.isVisible = false
            binding.loadingOverlay.isVisible = true

            lifecycleScope.launch {
                val result = accountRepo.signUp(username, email, password)
                binding.loadingOverlay.isVisible = false
                result.onSuccess { account ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.auth_account_created, account.username),
                        Toast.LENGTH_SHORT,
                    ).show()

                    navigateAfterAuth()
                }.onFailure { err ->
                    binding.tvError.text = err.message ?: "Sign up failed"
                    binding.tvError.isVisible = true
                }
            }
        }
    }

    private fun navigateAfterAuth() {
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
