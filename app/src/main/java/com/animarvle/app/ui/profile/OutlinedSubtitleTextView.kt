package com.animarvle.app.ui.profile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class OutlinedSubtitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var outlineEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var outlineThicknessDp: Float = 2.0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (outlineEnabled && outlineThicknessDp > 0f) {
            val originalColor = currentTextColor
            val textPaint = paint
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.strokeCap = Paint.Cap.ROUND
            textPaint.strokeWidth = outlineThicknessDp * resources.displayMetrics.density * 2f
            textPaint.color = Color.BLACK
            super.onDraw(canvas)

            textPaint.style = Paint.Style.FILL
            textPaint.color = originalColor
            super.onDraw(canvas)
        } else {
            super.onDraw(canvas)
        }
    }
}
