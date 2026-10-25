package com.animarvle.app.ui.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.animarvle.app.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Replicates the Material 3 Expressive morphing white polygon loading & reload indicator
 * used by AniVortex when buffering, seeking, or pulling to reload across the app.
 *
 * Smoothly rotates and transitions between:
 * Circle -> Rounded Pentagon -> 8-point Soft Star Burst -> Rounded Diamond -> Soft Pill -> Circle.
 */
class VortexMorphLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    var indicatorSizeDp: Float = 30f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var indicatorColor: Int = Color.WHITE
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    private val morphPath = Path()
    private var animProgress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            animProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.VortexMorphLoadingView, defStyleAttr, 0)
            try {
                if (a.hasValue(R.styleable.VortexMorphLoadingView_indicatorSize)) {
                    val sizePx = a.getDimension(R.styleable.VortexMorphLoadingView_indicatorSize, 0f)
                    if (sizePx > 0f) {
                        indicatorSizeDp = sizePx / resources.displayMetrics.density
                    }
                }
                indicatorColor = a.getColor(R.styleable.VortexMorphLoadingView_indicatorColor, Color.WHITE)
            } finally {
                a.recycle()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && !animator.isRunning) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            if (!animator.isRunning) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSizePx = (indicatorSizeDp * resources.displayMetrics.density).toInt()
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val w = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(defaultSizePx, widthSize)
            else -> defaultSizePx
        }
        val h = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(defaultSizePx, heightSize)
            else -> defaultSizePx
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val targetPx = indicatorSizeDp * resources.displayMetrics.density
        val maxBound = min(width, height).toFloat()
        val effectiveSize = if (targetPx > 0f) min(targetPx, maxBound) else maxBound
        val baseRadius = (effectiveSize / 2f) / 1.16f
        if (baseRadius <= 0f) return

        val p = animProgress.coerceIn(0f, 1f)
        val k = min(3, (p * 4f).toInt())
        val u = (p * 4f) - k
        val s = (1f - cos(Math.PI.toFloat() * u)) / 2f

        var w5 = 0f
        var w8 = 0f
        var w4 = 0f
        var w2 = 0f

        when (k) {
            0 -> {
                // Circle -> Rounded Pentagon
                w5 = 0.15f * s
            }
            1 -> {
                // Rounded Pentagon -> 8-point Soft Star Burst
                w5 = 0.15f * (1f - s)
                w8 = 0.12f * s
            }
            2 -> {
                // 8-point Soft Star Burst -> Rounded Diamond
                w8 = 0.12f * (1f - s)
                w4 = 0.14f * s
            }
            3 -> {
                // Rounded Diamond -> Soft Pill -> Circle
                w4 = 0.14f * (1f - s)
                w2 = 0.10f * sin(Math.PI.toFloat() * u)
            }
        }

        val twoPi = 2f * Math.PI.toFloat()
        val rotationRad = p * twoPi * 1.5f

        morphPath.reset()
        val steps = 96
        for (i in 0 until steps) {
            val theta = (i.toFloat() / steps) * twoPi
            val r = baseRadius * (
                1f +
                w5 * cos(5f * (theta - rotationRad)) +
                w8 * cos(8f * (theta - rotationRad * 0.8f)) +
                w4 * cos(4f * (theta - rotationRad * 1.1f)) +
                w2 * cos(2f * (theta - rotationRad * 0.7f))
            )
            val x = cx + r * cos(theta)
            val y = cy + r * sin(theta)
            if (i == 0) {
                morphPath.moveTo(x, y)
            } else {
                morphPath.lineTo(x, y)
            }
        }
        morphPath.close()
        canvas.drawPath(morphPath, paint)
    }
}
