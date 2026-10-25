package com.animarvle.app.ui.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemAvatarGridBinding

class EditProfileAdapter(
    private val avatars: List<AnimeAvatar>,
    private var selectedAvatarId: String?,
    private val onAvatarSelected: (AnimeAvatar) -> Unit,
) : RecyclerView.Adapter<EditProfileAdapter.AvatarViewHolder>() {

    fun setSelectedId(newId: String?) {
        val oldIndex = avatars.indexOfFirst { it.id == selectedAvatarId || it.imageUrl == selectedAvatarId }
        selectedAvatarId = newId
        val newIndex = avatars.indexOfFirst { it.id == selectedAvatarId || it.imageUrl == selectedAvatarId }
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
        val binding = ItemAvatarGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AvatarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
        holder.bind(avatars[position])
    }

    override fun getItemCount(): Int = avatars.size

    inner class AvatarViewHolder(private val binding: ItemAvatarGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AnimeAvatar) {
            val isSelected = (item.id == selectedAvatarId || item.imageUrl == selectedAvatarId)
            binding.selectionBorder.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (item.drawableRes != null) {
                binding.ivAvatar.setImageResource(item.drawableRes)
            } else if (!item.imageUrl.isNullOrBlank()) {
                binding.ivAvatar.load(item.imageUrl) {
                    crossfade(true)
                }
            } else {
                binding.ivAvatar.setImageResource(R.drawable.animarvle_logo_white)
            }

            binding.root.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val clickedItem = avatars[currentPos]
                    setSelectedId(clickedItem.imageUrl ?: clickedItem.id)
                    onAvatarSelected(clickedItem)
                }
            }
        }
    }
}
