package com.animarvle.app.ui.schedule

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemScheduleBinding
import com.animarvle.app.databinding.ItemScheduleCurrentTimeBinding
import com.animarvle.app.domain.ScheduleItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ScheduleRow {
    data class Anime(val item: ScheduleItem) : ScheduleRow()
    data class CurrentTime(val timeStr: String) : ScheduleRow()
}

/** Timeline list for the selected day (PROMPT §7.8) with current time indicator. */
class ScheduleAdapter(
    private val onOpen: (ScheduleItem) -> Unit,
    private val onToggleMyList: (ScheduleItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ANIME = 0
        private const val TYPE_CURRENT_TIME = 1
    }

    private val rows = mutableListOf<ScheduleRow>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var savedMedia = mapOf<Long, com.animarvle.app.ui.mylist.MyListStatus>()

    fun setSavedMedia(map: Map<Long, com.animarvle.app.ui.mylist.MyListStatus>) {
        savedMedia = map
        notifyDataSetChanged()
    }

    fun submit(list: List<ScheduleItem>, isToday: Boolean) {
        rows.clear()
        if (list.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        if (!isToday) {
            rows.addAll(list.map { ScheduleRow.Anime(it) })
        } else {
            val nowSec = System.currentTimeMillis() / 1000
            val currentTimeStr = timeFmt.format(Date())
            var inserted = false

            for (item in list) {
                if (!inserted && item.airingAtEpochSec > nowSec) {
                    rows.add(ScheduleRow.CurrentTime("Current Time - $currentTimeStr"))
                    inserted = true
                }
                rows.add(ScheduleRow.Anime(item))
            }
            if (!inserted) {
                rows.add(ScheduleRow.CurrentTime("Current Time - $currentTimeStr"))
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ScheduleRow.Anime -> TYPE_ANIME
        is ScheduleRow.CurrentTime -> TYPE_CURRENT_TIME
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CURRENT_TIME -> CurrentTimeVH(
                ItemScheduleCurrentTimeBinding.inflate(inflater, parent, false)
            )
            else -> AnimeVH(
                ItemScheduleBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ScheduleRow.Anime -> (holder as AnimeVH).bind(row.item)
            is ScheduleRow.CurrentTime -> (holder as CurrentTimeVH).bind(row.timeStr)
        }
    }

    inner class CurrentTimeVH(
        private val binding: ItemScheduleCurrentTimeBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(text: String) {
            binding.currentTimeText.text = text
        }
    }

    inner class AnimeVH(
        private val binding: ItemScheduleBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScheduleItem) {
            binding.timeLabel.text = timeFmt.format(Date(item.airingAtEpochSec * 1000))
            binding.title.text = item.title
            val epNum = item.episode ?: item.totalEpisodes
            val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val dateStr = dateFmt.format(Date(item.airingAtEpochSec * 1000))

            binding.subtitle.text = if (item.format == "Series" || item.format == "Movie") {
                val formatPart = item.format
                if (epNum != null) {
                    "Episode $epNum · $formatPart · $dateStr"
                } else {
                    "$formatPart · $dateStr"
                }
            } else if (epNum != null) {
                binding.root.context.getString(R.string.schedule_episode_label, epNum)
            } else {
                item.format.orEmpty()
            }
            binding.thumbImage.load(item.coverUrl)
            binding.root.setOnClickListener { onOpen(item) }
            val savedStatus = savedMedia[item.mediaId]
            if (savedStatus != null) {
                binding.myListButton.text = "✓ ${savedStatus.label}"
                binding.myListButton.setTextColor(android.graphics.Color.WHITE)
                binding.myListButton.setBackgroundResource(R.drawable.bg_schedule_my_list_active)
            } else {
                binding.myListButton.setText(R.string.schedule_add_my_list)
                binding.myListButton.setTextColor(android.graphics.Color.parseColor("#DCDFEA"))
                binding.myListButton.setBackgroundResource(R.drawable.bg_schedule_my_list)
            }
            binding.myListButton.setOnClickListener { onToggleMyList(item) }
        }
    }
}