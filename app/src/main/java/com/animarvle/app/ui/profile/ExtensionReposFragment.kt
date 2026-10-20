package com.animarvle.app.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.FragmentExtensionReposBinding
import com.animarvle.app.extensions.ExtensionRepo
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExtensionReposFragment : Fragment() {

    private var _binding: FragmentExtensionReposBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExtensionReposViewModel by viewModels()
    private lateinit var adapter: ExtensionRepoAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentExtensionReposBinding.inflate(inflater, container, false)
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

        binding.buttonBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.toolbarTitle.setText(R.string.extension_repos_title)

        checkFirstUseConsent()

        binding.refreshButton.setOnClickListener { viewModel.refresh() }

        adapter = ExtensionRepoAdapter(
            onOpenWebsite = { repo -> openWebsite(repo) },
            onCopyUrl = { repo -> copyUrl(repo) },
            onDelete = { repo -> confirmDelete(repo) },
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.fab.setOnClickListener { showAddDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.repos.collectLatest { repos ->
                        adapter.submitList(repos)
                        binding.emptyView.visibility = if (repos.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.loading.collectLatest { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                    }
                }
                launch {
                    viewModel.error.collectLatest { error ->
                        error?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showAddDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.extension_repo_url_hint)
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.extension_repo_add_title)
            .setView(input)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    viewModel.addRepo(url)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(repo: ExtensionRepo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.extension_repo_delete_title)
            .setMessage(getString(R.string.extension_repo_delete_message, repo.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteRepo(repo.baseUrl)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openWebsite(repo: ExtensionRepo) {
        if (repo.website.isBlank()) {
            Toast.makeText(requireContext(), R.string.extension_repo_no_website, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(repo.website))
            startActivity(intent)
        }
    }

    private fun copyUrl(repo: ExtensionRepo) {
        val url = "${repo.baseUrl}/index.min.json"
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Extension repo URL", url))
        Toast.makeText(requireContext(), R.string.extension_repo_url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun checkFirstUseConsent() {
        val prefs = requireContext().getSharedPreferences(PREFS_SOURCES_CONSENT, android.content.Context.MODE_PRIVATE)
        val accepted = prefs.getBoolean(KEY_CONSENT_ACCEPTED, false)
        if (!accepted) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.sources_consent_title)
                .setMessage(R.string.sources_consent_message)
                .setPositiveButton(R.string.sources_consent_agree) { _, _ ->
                    prefs.edit().putBoolean(KEY_CONSENT_ACCEPTED, true).apply()
                }
                .setNegativeButton(R.string.action_cancel) { _, _ ->
                    NavHostFragment.findNavController(this@ExtensionReposFragment).navigateUp()
                }
                .setCancelable(false)
                .show()
        }
    }

    companion object {
        private const val PREFS_SOURCES_CONSENT = "sources_consent_prefs"
        private const val KEY_CONSENT_ACCEPTED = "consent_accepted"
    }
}
