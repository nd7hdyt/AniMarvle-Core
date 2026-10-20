package com.animarvle.app.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

class VortexScrollbarDecoration(context: Context) :
    RecyclerView.ItemDecoration(),
    RecyclerView.OnItemTouchListener {

    private val density = context.resources.displayMetrics.density
    private val thumbWidth = 3.5f * density
    private val thumbCornerRadius = 2f * density
    private val marginEnd = 6f * density
    private val bottomInset = 8f * density // Keeps thumb inside rounded bottom-right corner
    private val touchTargetSlop = 28f * density
    private val minThumbHeight = 32f * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt() // #B3FFFFFF
        style = Paint.Style.FILL
    }

    private val thumbRect = RectF()
    private var isDragging = false
    private var dragStartY = 0f
    private var dragStartScrollOffset = 0

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter ?: return
        // Since 3.5 rows are visible, any list with 4+ items (Quality, Speed, Audio) needs the scrollbar
        if (adapter.itemCount <= 3) return

        val range = parent.computeVerticalScrollRange()
        val extent = parent.computeVerticalScrollExtent()
        val offset = parent.computeVerticalScrollOffset()

        if (range <= extent || range <= 0) return

        val trackTop = parent.paddingTop.toFloat()
        val trackHeight = (parent.height - parent.paddingTop - parent.paddingBottom - bottomInset)
        if (trackHeight <= 0f) return

        val calculatedThumbHeight = (extent.toFloat() / range.toFloat()) * trackHeight
        val thumbHeight = max(calculatedThumbHeight, minThumbHeight).coerceAtMost(trackHeight)

        val maxScrollOffset = (range - extent).toFloat()
        val scrollFraction = (offset.toFloat() / maxScrollOffset).coerceIn(0f, 1f)
        val scrollableTrackSpace = trackHeight - thumbHeight

        val thumbTop = trackTop + (scrollFraction * scrollableTrackSpace)
        val thumbLeft = parent.width - marginEnd - thumbWidth
        val thumbRight = parent.width - marginEnd

        thumbRect.set(thumbLeft, thumbTop, thumbRight, thumbTop + thumbHeight)
        c.drawRoundRect(thumbRect, thumbCornerRadius, thumbCornerRadius, thumbPaint)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        val adapter = rv.adapter ?: return false
        if (adapter.itemCount <= 3) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitLeft = rv.width - touchTargetSlop
                if (e.x >= hitLeft && e.y >= thumbRect.top - 12f * density && e.y <= thumbRect.bottom + 12f * density) {
                    isDragging = true
                    dragStartY = e.y
                    dragStartScrollOffset = rv.computeVerticalScrollOffset()
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!isDragging) return

        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val range = rv.computeVerticalScrollRange()
                val extent = rv.computeVerticalScrollExtent()
                val trackHeight = (rv.height - rv.paddingTop - rv.paddingBottom - bottomInset)
                val scrollableTrackSpace = trackHeight - thumbRect.height()

                if (scrollableTrackSpace > 0 && range > extent) {
                    val deltaY = e.y - dragStartY
                    val scrollDelta = ((deltaY / scrollableTrackSpace) * (range - extent)).toInt()
                    val currentOffset = rv.computeVerticalScrollOffset()
                    val targetOffset = (dragStartScrollOffset + scrollDelta).coerceIn(0, range - extent)
                    rv.scrollBy(0, targetOffset - currentOffset)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                rv.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
}
