package com.animarvle.app.ui.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.extensions.ExtensionRepo

class ExtensionRepoAdapter(
    private val onOpenWebsite: (ExtensionRepo) -> Unit,
    private val onCopyUrl: (ExtensionRepo) -> Unit,
    private val onDelete: (ExtensionRepo) -> Unit,
) : ListAdapter<ExtensionRepo, ExtensionRepoAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_extension_repo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.repoName)
        private val urlText: TextView = itemView.findViewById(R.id.repoUrl)
        private val openButton: ImageButton = itemView.findViewById(R.id.repoOpenWebsite)
        private val copyButton: ImageButton = itemView.findViewById(R.id.repoCopyUrl)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.repoDelete)

        fun bind(repo: ExtensionRepo) {
            nameText.text = repo.name
            urlText.text = repo.baseUrl
            openButton.setOnClickListener { onOpenWebsite(repo) }
            copyButton.setOnClickListener { onCopyUrl(repo) }
            deleteButton.setOnClickListener { onDelete(repo) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ExtensionRepo>() {
            override fun areItemsTheSame(oldItem: ExtensionRepo, newItem: ExtensionRepo): Boolean =
                oldItem.baseUrl == newItem.baseUrl

            override fun areContentsTheSame(oldItem: ExtensionRepo, newItem: ExtensionRepo): Boolean =
                oldItem == newItem
        }
    }
}
