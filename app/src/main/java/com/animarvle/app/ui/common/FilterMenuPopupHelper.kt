package com.animarvle.app.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.ui.search.PopupItem

object FilterMenuPopupHelper {

    private var activePopup: PopupWindow? = null

    fun showOptionMenu(
        anchor: View,
        options: List<String>,
        selectedOption: String,
        onOptionSelected: (String) -> Unit,
    ) {
        val entries = options.map { PopupItem(it) }
        show(anchor, entries, selectedOption, onOptionSelected)
    }

    fun show(
        anchor: View,
        entries: List<PopupItem>,
        currentSelection: String,
        onSelected: (String) -> Unit,
    ) {
        activePopup?.dismiss()

        val context = anchor.context
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_filter_menu, null)
        val rv = popupView.findViewById<RecyclerView>(R.id.rvPopupOptions)

        val popupWidth = dp(context, 200)

        // Calculate vertical space
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val anchorY = location[1]
        val screenHeight = context.resources.displayMetrics.heightPixels
        val bottomMargin = dp(context, 60)
        val availableBelow = screenHeight - anchorY - anchor.height - bottomMargin
        val availableAbove = anchorY - dp(context, 60)

        val itemH = dp(context, 44)
        val headerH = dp(context, 32)
        val totalContentH = entries.sumOf { if (it.isHeader) headerH else itemH } + dp(context, 12)

        val (popupHeight, yOffset) = if (availableBelow >= dp(context, 200) || availableBelow >= availableAbove) {
            val h = minOf(totalContentH, minOf(dp(context, 340), availableBelow.coerceAtLeast(dp(context, 200))))
            h to 0
        } else {
            val h = minOf(totalContentH, minOf(dp(context, 340), availableAbove.coerceAtLeast(dp(context, 200))))
            h to -(h + anchor.height)
        }

        val popup = PopupWindow(
            popupView,
            popupWidth,
            popupHeight,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(context, 12).toFloat()
        }

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = PopupAdapter(entries, currentSelection) { sel ->
            popup.dismiss()
            onSelected(sel)
        }

        // Scroll immediately to active selection
        val selIndex = entries.indexOfFirst { !it.isHeader && it.title.equals(currentSelection, ignoreCase = true) }
        if (selIndex > 0) {
            rv.scrollToPosition(selIndex)
        }

        activePopup = popup
        popup.showAsDropDown(anchor, 0, yOffset)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class PopupAdapter(
        private val items: List<PopupItem>,
        private val currentSelection: String,
        private val onSelect: (String) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (items[position].isHeader) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val v = inflater.inflate(R.layout.item_filter_popup_header, parent, false)
                HeaderViewHolder(v)
            } else {
                val v = inflater.inflate(R.layout.item_filter_popup_option, parent, false)
                OptionViewHolder(v)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder) {
                holder.tvTitle.text = item.title
            } else if (holder is OptionViewHolder) {
                holder.tvTitle.text = item.title
                val isSelected = item.title.equals(currentSelection, ignoreCase = true)
                holder.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { onSelect(item.title) }
            }
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvHeaderTitle)
        }

        class OptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvOptionTitle)
            val ivCheck: ImageView = view.findViewById(R.id.ivOptionCheck)
        }
    }
}
