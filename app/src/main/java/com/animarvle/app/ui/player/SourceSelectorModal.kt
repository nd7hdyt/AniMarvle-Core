package com.animarvle.app.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding

/**
 * Floating Obsidian Glass Source Modal matching Resident Evil / Oppenheimer reference UI:
 * - Status dot (🟢 Ready / ⚪ Available on-demand / 🔴 Failed)
 * - Country / Language flag emoji (🇺🇸, 🇯🇵, 🇰🇷, 🇹🇷, 🌐)
 * - Clean branded server name (Halo, Nebula, Prism (multi-audio), Solara, Onyx, Kitsune, Velvet)
 * - Selection badge (green circled checkmark)
 * - Positioned floating in the top-right corner during landscape video playback
 */
object SourceSelectorModal {

    fun show(
        context: Context,
        servers: List<ServerModalItem>,
        onDismiss: () -> Unit = {},
        onSelectServer: (ServerModalItem) -> Unit
    ) {
        if (servers.isEmpty()) return

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (isLandscape) {
                setGravity(Gravity.TOP or Gravity.END)
                attributes = attributes.apply {
                    x = dpToPx(context, 16)
                    y = dpToPx(context, 16)
                }
            } else {
                setGravity(Gravity.CENTER)
            }
        }

        val cardWidth = dpToPx(context, 310)
        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(context, 18).toFloat()
                setColor(Color.parseColor("#E6121318")) // Deep Obsidian Glass (90% opacity)
                setStroke(dpToPx(context, 1), Color.parseColor("#26FFFFFF"))
            }
            setPadding(dpToPx(context, 12))
        }

        // Header Row: "‹   Source"
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(context, 8), dpToPx(context, 8), dpToPx(context, 8), dpToPx(context, 10))
        }

        val backLabel = TextView(context).apply {
            text = "‹   Source"
            setTextColor(Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(backLabel)
        rootCard.addView(headerRow)

        // Thin Divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(context, 1)
            ).apply {
                bottomMargin = dpToPx(context, 6)
            }
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
        }
        rootCard.addView(divider)

        // Scrollable Server List (max height 320dp to prevent screen overflow)
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        servers.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(context, 48)
                ).apply {
                    bottomMargin = dpToPx(context, 4)
                }
                setPadding(dpToPx(context, 12), 0, dpToPx(context, 12), 0)

                if (item.isSelected) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(context, 12).toFloat()
                        setColor(Color.parseColor("#1FFFFFFF")) // Highlighted glass row
                    }
                } else {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(context, 12).toFloat()
                        setColor(Color.TRANSPARENT)
                    }
                }

                setOnClickListener {
                    dialog.dismiss()
                    onSelectServer(item)
                }
            }

            // 1. Status Dot
            val statusDot = View(context).apply {
                val dotSize = dpToPx(context, 8)
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = dpToPx(context, 12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    val dotColor = when (item.status) {
                        ServerHealthStatus.READY -> Color.parseColor("#2ECC71")
                        ServerHealthStatus.AVAILABLE -> Color.parseColor("#7F8C8D")
                        ServerHealthStatus.FAILED -> Color.parseColor("#E74C3C")
                    }
                    setColor(dotColor)
                }
            }
            row.addView(statusDot)

            // 2. Country / Language Flag Emoji
            val flagView = TextView(context).apply {
                text = item.flagEmoji
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(context, 12)
                }
            }
            row.addView(flagView)

            // 3. Branded Server Name
            val nameView = TextView(context).apply {
                text = item.displayName
                setTextColor(if (item.status == ServerHealthStatus.FAILED) Color.parseColor("#80FFFFFF") else Color.WHITE)
                textSize = 15f
                paint.isFakeBoldText = item.isSelected
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)

            // 4. Right Checkmark Circle for Selected Server
            if (item.isSelected) {
                val checkBadge = TextView(context).apply {
                    text = "✓"
                    textSize = 12f
                    setTextColor(Color.parseColor("#2ECC71"))
                    gravity = Gravity.CENTER
                    val badgeSize = dpToPx(context, 22)
                    layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dpToPx(context, 2), Color.parseColor("#2ECC71"))
                    }
                }
                row.addView(checkBadge)
            }

            listContainer.addView(row)
        }

        scrollView.addView(listContainer)
        rootCard.addView(scrollView)

        dialog.setContentView(rootCard)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}

