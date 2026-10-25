package com.animarvle.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.animarvle.app.R

object VortexBlurHelper {

    fun applyGlassEffect(view: View, radiusDp: Float = 18f) {
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                val rPx = radiusDp * v.resources.displayMetrics.density
                outline.setRoundRect(0, 0, v.width, v.height, rPx)
            }
        }
    }

    fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun findTextureView(view: View?): TextureView? {
        if (view == null) return null
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Captures the exact screen region behind [cardView] (including ExoPlayer TextureView + HUD seekbar),
     * applies a fast downscaled + hardware Gaussian blur, tints with translucent obsidian (#B814151A),
     * clips to 26dp rounded corners, and sets it as [cardView]'s background.
     */
    fun applyCardBackdropBlur(context: Context, cardView: View) {
        val activity = findActivity(context) ?: return
        val rootView = activity.window?.decorView?.rootView ?: return
        val stageView = activity.findViewById<View>(R.id.stage) ?: rootView

        if (cardView.width <= 0 || cardView.height <= 0) return

        val cardLoc = IntArray(2)
        val stageLoc = IntArray(2)
        cardView.getLocationOnScreen(cardLoc)
        stageView.getLocationOnScreen(stageLoc)

        val cropX = (cardLoc[0] - stageLoc[0]).coerceAtLeast(0)
        val cropY = (cardLoc[1] - stageLoc[1]).coerceAtLeast(0)
        val cropW = cardView.width.coerceAtMost(stageView.width - cropX)
        val cropH = cardView.height.coerceAtMost(stageView.height - cropY)
        if (cropW <= 0 || cropH <= 0) return

        // Downscale by 8x for ultra-fast, ultra-smooth frosted glass blur
        val scaleFactor = 8
        val smallW = (cropW / scaleFactor).coerceAtLeast(16)
        val smallH = (cropH / scaleFactor).coerceAtLeast(16)

        val smallBmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(smallBmp)
        canvas.scale(1f / scaleFactor, 1f / scaleFactor)
        canvas.translate(-cropX.toFloat(), -cropY.toFloat())

        // 1. Draw the live video frame from TextureView or the general stage/root view
        val textureView = findTextureView(stageView)
        if (textureView != null && textureView.isAvailable) {
            val videoBmp = textureView.getBitmap(
                (textureView.width / scaleFactor).coerceAtLeast(16),
                (textureView.height / scaleFactor).coerceAtLeast(16)
            )
            if (videoBmp != null) {
                val tvLoc = IntArray(2)
                textureView.getLocationOnScreen(tvLoc)
                val tvX = (tvLoc[0] - stageLoc[0]).toFloat()
                val tvY = (tvLoc[1] - stageLoc[1]).toFloat()
                val dstRect = RectF(tvX, tvY, tvX + textureView.width, tvY + textureView.height)
                canvas.drawBitmap(videoBmp, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
                videoBmp.recycle()
            }
        } else {
            // General UI views (posters, RecyclerView, lists, backgrounds)
            stageView.draw(canvas)
        }

        // 2. Draw the player HUD controls (seekbar, 23:59 duration, Skip 90s) over the video frame
        val controlsOverlay = activity.findViewById<View>(R.id.controlsOverlay)
        if (controlsOverlay != null && controlsOverlay.visibility == View.VISIBLE) {
            controlsOverlay.draw(canvas)
        }

        // 3. Apply multi-pass box/stack blur on the small bitmap + dark glass tint
        val blurredSmall = fastStackBlur(smallBmp, radius = 10)
        smallBmp.recycle()

        // 4. Create rounded-corner drawable matching 26dp card radius
        val density = context.resources.displayMetrics.density
        val cornerRadius = (26f * density) / scaleFactor
        val finalSmall = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val finalCanvas = Canvas(finalSmall)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, smallW.toFloat(), smallH.toFloat())

        finalCanvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        finalCanvas.drawBitmap(blurredSmall, 0f, 0f, paint)
        blurredSmall.recycle()

        // Apply translucent warm-obsidian tint (#B8161618) inside the rounded shape
        paint.color = Color.parseColor("#B8161618")
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        finalCanvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        cardView.background = BitmapDrawable(context.resources, finalSmall).apply {
            isFilterBitmap = true
        }
    }

    /**
     * Renders a frosted-glass blur backdrop for BottomSheets (matching AniVortex design language):
     * Captures the screen behind the bottom sheet, applies stack blur with dark obsidian glass tint,
     * and rounds only the top corners.
     */
    fun applyBottomSheetBlur(
        activity: Activity,
        sheetView: View,
        topCornerRadiusDp: Float = 24f
    ) {
        val rootView = activity.window?.decorView?.rootView ?: return
        if (sheetView.width <= 0 || sheetView.height <= 0) return

        val sheetLoc = IntArray(2)
        val rootLoc = IntArray(2)
        sheetView.getLocationOnScreen(sheetLoc)
        rootView.getLocationOnScreen(rootLoc)

        val cropX = (sheetLoc[0] - rootLoc[0]).coerceAtLeast(0)
        val cropY = (sheetLoc[1] - rootLoc[1]).coerceAtLeast(0)
        val cropW = sheetView.width.coerceAtMost(rootView.width - cropX)
        val cropH = sheetView.height.coerceAtMost(rootView.height - cropY)
        if (cropW <= 0 || cropH <= 0) return

        val scaleFactor = 8
        val smallW = (cropW / scaleFactor).coerceAtLeast(16)
        val smallH = (cropH / scaleFactor).coerceAtLeast(16)

        val smallBmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(smallBmp)
        canvas.scale(1f / scaleFactor, 1f / scaleFactor)
        canvas.translate(-cropX.toFloat(), -cropY.toFloat())
        rootView.draw(canvas)

        val blurredSmall = fastStackBlur(smallBmp, radius = 12)
        smallBmp.recycle()

        val density = sheetView.resources.displayMetrics.density
        val rPx = (topCornerRadiusDp * density) / scaleFactor

        val finalSmall = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val finalCanvas = Canvas(finalSmall)

        // Path with rounded top corners only
        val path = android.graphics.Path().apply {
            val radii = floatArrayOf(rPx, rPx, rPx, rPx, 0f, 0f, 0f, 0f)
            addRoundRect(RectF(0f, 0f, smallW.toFloat(), smallH.toFloat()), radii, android.graphics.Path.Direction.CW)
        }

        finalCanvas.save()
        finalCanvas.clipPath(path)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        finalCanvas.drawBitmap(blurredSmall, 0f, 0f, paint)
        blurredSmall.recycle()

        // Deep OLED obsidian glass tint (#E60E0E12)
        paint.color = Color.parseColor("#E60E0E12")
        finalCanvas.drawRect(0f, 0f, smallW.toFloat(), smallH.toFloat(), paint)

        // Subtle top border highlight line
        paint.color = Color.parseColor("#33FFFFFF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        finalCanvas.drawPath(path, paint)

        finalCanvas.restore()

        sheetView.background = BitmapDrawable(sheetView.resources, finalSmall).apply {
            isFilterBitmap = true
        }
    }

    /** Fast, dependency-free Gaussian-approximating stack blur */
    private fun fastStackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi = 0
        var yw = 0
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = (idx / divsum)
        }

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }
        x = 0
        while (x < w) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = maxOf(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = minOf(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * Draws the curved dark arch vignette at scroll=0 (matching 1000297221.jpg) where darkness
     * wraps deeper down around the top-left logo and top-right search icon and arches up in the center.
     * On scroll > 0 (matching 1000297223.jpg), locks the upper toolbar to solid #FF000000 and
     * blurs/feathers the bottom lip so scrolling cards never bleed through the header icons.
     */
    fun applyHeaderScrollBlur(
        sourceView: View,
        headerBar: View,
        scrollFraction: Float
    ) {
        val w = headerBar.width
        val h = headerBar.height
        if (w <= 0 || h <= 0) return

        val f = scrollFraction.coerceIn(0f, 1f)

        // Downsampled canvas for smooth gradients & blur
        val scaleFactor = 8
        val smallW = (w / scaleFactor).coerceAtLeast(32)
        val smallH = (h / scaleFactor).coerceAtLeast(16)

        val outBmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)

        // 1. If scrolling, capture and blur the feed underneath first
        if (f > 0.01f && sourceView.width > 0 && sourceView.height > 0) {
            val barLoc = IntArray(2)
            val srcLoc = IntArray(2)
            headerBar.getLocationOnScreen(barLoc)
            sourceView.getLocationOnScreen(srcLoc)

            val cropX = (barLoc[0] - srcLoc[0]).coerceAtLeast(0)
            val cropY = (barLoc[1] - srcLoc[1]).coerceAtLeast(0)

            val rawSmall = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
            val rawCanvas = Canvas(rawSmall)
            rawCanvas.scale(1f / scaleFactor, 1f / scaleFactor)
            rawCanvas.translate(-cropX.toFloat(), -cropY.toFloat())
            sourceView.draw(rawCanvas)

            val blurred = fastStackBlur(rawSmall, radius = 12)
            rawSmall.recycle()

            val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (f * 255).toInt()
            }
            canvas.drawBitmap(blurred, 0f, 0f, blurPaint)
            blurred.recycle()
        }

        // 2. Always draw the Curved Pre-Scroll Arch Vignette (strongest at top-left & top-right corners,
        // arching upward in the center so the logo & search icon sit in deep curved dark pockets)
        val topSpinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, smallH * 0.65f,
                intArrayOf(
                    Color.argb(235, 0, 0, 0),
                    Color.argb(130, 0, 0, 0),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, smallW.toFloat(), smallH.toFloat(), topSpinePaint)

        // Left curved pocket (behind Logo)
        val cornerRadius = smallW * 0.56f
        val leftPocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                0f, smallH * 0.12f, cornerRadius,
                intArrayOf(
                    Color.argb(245, 0, 0, 0),
                    Color.argb(185, 0, 0, 0),
                    Color.argb(80, 0, 0, 0),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.42f, 0.75f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.save()
        canvas.scale(1f, (smallH.toFloat() / cornerRadius) * 0.95f, 0f, 0f)
        canvas.drawCircle(0f, smallH * 0.12f, cornerRadius, leftPocketPaint)
        canvas.restore()

        // Right curved pocket (behind Search icon)
        val rightPocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                smallW.toFloat(), smallH * 0.12f, cornerRadius,
                intArrayOf(
                    Color.argb(245, 0, 0, 0),
                    Color.argb(185, 0, 0, 0),
                    Color.argb(80, 0, 0, 0),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.42f, 0.75f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.save()
        canvas.scale(1f, (smallH.toFloat() / cornerRadius) * 0.95f, smallW.toFloat(), 0f)
        canvas.drawCircle(smallW.toFloat(), smallH * 0.12f, cornerRadius, rightPocketPaint)
        canvas.restore()

        // 3. On-scroll solid black upper mask + feathered bottom shadow (matching 1000297223.jpg)
        if (f > 0.01f) {
            val solidTopAlpha = (f * 255).toInt().coerceIn(0, 255)
            val midMaskAlpha = (f * 245).toInt().coerceIn(0, 250)
            val lowMaskAlpha = (f * 165).toInt().coerceIn(0, 180)

            val scrollMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, smallH.toFloat(),
                    intArrayOf(
                        Color.argb(solidTopAlpha, 0, 0, 0), // 100% solid black at top
                        Color.argb(midMaskAlpha, 0, 0, 0),  // Solid behind logo/capsule/search
                        Color.argb(lowMaskAlpha, 4, 4, 6),  // Soft dark blur zone
                        Color.argb(0, 0, 0, 0)              // Feathered bottom lip
                    ),
                    floatArrayOf(0f, 0.68f, 0.86f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, smallW.toFloat(), smallH.toFloat(), scrollMaskPaint)
        }

        headerBar.background = BitmapDrawable(headerBar.resources, outBmp).apply {
            isFilterBitmap = true
        }
    }

    /**
     * Renders a clean cinematic header for DetailsFragment (matching 1:1 with AniVortex reference):
     * At scroll=0: subtle linear top scrim from status bar top down into transparency (no heavy radial domes).
     * On scroll > 0: smooth obsidian/solid black mask (#FF000000) over status bar & toolbar with a tight
     * feathered bottom lip, allowing content to cleanly slide underneath.
     */
    fun applyDetailsHeaderScroll(
        headerBar: View,
        scrollFraction: Float
    ) {
        val w = headerBar.width
        val h = headerBar.height
        if (w <= 0 || h <= 0) return

        val f = scrollFraction.coerceIn(0f, 1f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. Pre-scroll subtle linear scrim (status bar legibility)
        val preScrollPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    Color.argb(120, 0, 0, 0), // 47% black at very top
                    Color.argb(60, 0, 0, 0),  // 24% behind toolbar icons
                    Color.argb(0, 0, 0, 0)    // Transparent at bottom
                ),
                floatArrayOf(0f, 0.65f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), preScrollPaint)

        // 2. On scroll: smooth solid black fill across status bar + toolbar with tight bottom lip
        if (f > 0.01f) {
            val solidAlpha = (f * 255).toInt().coerceIn(0, 255)
            val lipAlpha = (f * 180).toInt().coerceIn(0, 180)
            val scrollPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(
                        Color.argb(solidAlpha, 10, 10, 14), // Solid dark obsidian across status bar
                        Color.argb(solidAlpha, 10, 10, 14), // Solid behind toolbar
                        Color.argb(lipAlpha, 10, 10, 14),   // Soft lip start
                        Color.argb(0, 0, 0, 0)              // Feathered bottom lip
                    ),
                    floatArrayOf(0f, 0.75f, 0.90f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrollPaint)
        }

        headerBar.background = BitmapDrawable(headerBar.resources, bmp).apply {
            isFilterBitmap = true
        }
    }

    /**
     * Renders the 1:1 AniVortex Bottom Navigation backdrop (1000297223.jpg):
     * Soft blurred dark feather at the top lip transitioning into a deep pitch-black base
     * so the strong white tab icons and labels pop with maximum contrast.
     */
    fun applyBottomNavBlur(
        sourceView: View,
        bottomNavContainer: View
    ) {
        if (bottomNavContainer.width <= 0 || bottomNavContainer.height <= 0 ||
            sourceView.width <= 0 || sourceView.height <= 0
        ) return

        val barLoc = IntArray(2)
        val srcLoc = IntArray(2)
        bottomNavContainer.getLocationOnScreen(barLoc)
        sourceView.getLocationOnScreen(srcLoc)

        val cropX = (barLoc[0] - srcLoc[0]).coerceAtLeast(0)
        val cropY = (barLoc[1] - srcLoc[1]).coerceAtLeast(0)
        val cropW = bottomNavContainer.width.coerceAtMost(sourceView.width - cropX)
        val cropH = bottomNavContainer.height.coerceAtMost(sourceView.height - cropY)
        if (cropW <= 0 || cropH <= 0) return

        val scaleFactor = 10
        val smallW = (cropW / scaleFactor).coerceAtLeast(16)
        val smallH = (cropH / scaleFactor).coerceAtLeast(8)

        val smallBmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(smallBmp)
        canvas.scale(1f / scaleFactor, 1f / scaleFactor)
        canvas.translate(-cropX.toFloat(), -cropY.toFloat())
        sourceView.draw(canvas)

        val blurred = fastStackBlur(smallBmp, radius = 10)
        smallBmp.recycle()

        val finalCanvas = Canvas(blurred)
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, smallH.toFloat(),
                intArrayOf(
                    Color.argb(0, 0, 0, 0),     // Seamless top feather edge
                    Color.argb(210, 0, 0, 0),   // Dark glass transition above icons
                    Color.argb(250, 0, 0, 0),   // Deep black behind icons
                    Color.argb(255, 0, 0, 0)    // Pure OLED black behind labels & indicator
                ),
                floatArrayOf(0f, 0.22f, 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        finalCanvas.drawRect(0f, 0f, smallW.toFloat(), smallH.toFloat(), gradientPaint)

        bottomNavContainer.background = BitmapDrawable(bottomNavContainer.resources, blurred).apply {
            isFilterBitmap = true
        }
    }
}
