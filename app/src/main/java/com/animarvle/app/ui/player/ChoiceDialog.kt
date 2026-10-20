package com.animarvle.app.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.R
import com.animarvle.app.databinding.DialogChoiceBinding
import com.animarvle.app.databinding.ItemChoiceRowBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

data class ChoiceRow(
    val title: String,
    val subtitle: String? = null,
    val iconRes: Int? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
) {
    val label: String get() = title

    // Backwards-compatible constructor for ChoiceRow(label, selected, enabled)
    constructor(
        label: String,
        selected: Boolean = false,
        enabled: Boolean = true,
    ) : this(
        title = label,
        subtitle = null,
        iconRes = null,
        selected = selected,
        enabled = enabled,
    )
}

data class MenuPage(
    val title: String,
    val rows: List<ChoiceRow>,
    val onPick: (ChoiceDialog.Controller, Int) -> Unit
)

class ChoiceDialog private constructor(
    context: Context,
    private val binding: DialogChoiceBinding,
    val dialog: Dialog
) {
    private val pageStack = ArrayDeque<MenuPage>()
    private val displayMetrics = context.resources.displayMetrics

    inner class Controller {
        /** Swap to Speed / Quality / Audio inside the exact same Dialog window */
        fun navigateTo(title: String, rows: List<ChoiceRow>, onPick: (Controller, Int) -> Unit) {
            val page = MenuPage(title, rows, onPick)
            pageStack.addLast(page)
            renderPage(page, animate = true)
        }

        /** Pop back to Playback Settings, or close if already at root */
        fun navigateBack() {
            if (pageStack.size > 1) {
                pageStack.removeLast()
                renderPage(pageStack.last(), animate = true)
            } else {
                dialog.dismiss()
            }
        }

        fun dismiss() = dialog.dismiss()
    }

    val controller = Controller()

    init {
        val scrollbar = VortexScrollbarDecoration(context)
        val itemHeightDp = 52f // Standard single row height in dp
        binding.dialogList.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            // Clamp at 3.5 rows so 4th item peeks at the bottom just like AniVortex
            maxHeightPx = (3.5f * itemHeightDp * displayMetrics.density).toInt()
            addItemDecoration(scrollbar)
            addOnItemTouchListener(scrollbar)
        }

        if (dialog is ComponentDialog) {
            dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    controller.navigateBack()
                }
            })
        }
    }

    private fun renderPage(page: MenuPage, animate: Boolean) {
        if (animate) {
            val transition = TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade(Fade.IN))
                duration = 160L
                interpolator = FastOutSlowInInterpolator()
            }
            TransitionManager.beginDelayedTransition(binding.cardRoot, transition)
        }

        binding.dialogTitle.text = page.title
        binding.dialogList.adapter = RowAdapter(page.rows) { index ->
            page.onPick(controller, index)
        }

        val selectedIdx = page.rows.indexOfFirst { it.selected }
        binding.dialogList.post {
            if (selectedIdx >= 3) {
                (binding.dialogList.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(selectedIdx - 2, 0)
            } else {
                binding.dialogList.scrollToPosition(0)
            }
            binding.dialogList.invalidateItemDecorations()
        }
    }

    companion object {
        /** Single-page backwards-compatible call */
        fun show(
            context: Context,
            title: String,
            rows: List<ChoiceRow>,
            onDismiss: (() -> Unit)? = null,
            onPick: (Int) -> Unit,
        ): Dialog {
            return showWithSubmenus(
                context = context,
                initialPage = MenuPage(title, rows) { ctrl, index ->
                    ctrl.dismiss()
                    onPick(index)
                },
                onDismiss = onDismiss
            )
        }

        /** Multi-page call for Playback Settings -> Speed / Quality / Audio */
        fun showWithSubmenus(
            context: Context,
            initialPage: MenuPage,
            onDismiss: (() -> Unit)? = null
        ): Dialog {
            val binding = DialogChoiceBinding.inflate(LayoutInflater.from(context), null, false)
            val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val displayMetrics = context.resources.displayMetrics
            val density = displayMetrics.density

            val dialog: Dialog = if (isLandscape) {
                ComponentDialog(context, R.style.Theme_Animarvle_FloatingDialog).apply {
                    setContentView(binding.root)
                    setCanceledOnTouchOutside(true)
                    window?.apply {
                        // Set rounded card drawable on the Window itself so blur clips to the 26dp corners
                        setBackgroundDrawableResource(R.drawable.bg_vortex_glass_card)
                        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                        setWindowAnimations(R.style.Animation_Animarvle_FloatingCard)

                        // Enable localized frosted-glass blur behind the card only (Android 12+)
                        addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            attributes.blurBehindRadius = (30 * density).toInt()
                        }
                        setDimAmount(0.15f)

                        setLayout(
                            (displayMetrics.widthPixels * 0.70f).toInt(),
                            WindowManager.LayoutParams.WRAP_CONTENT
                        )
                        // Float 12dp above the bottom edge to show all 4 rounded corners
                        attributes = attributes.apply {
                            y = (12 * density).toInt()
                        }
                    }
                }
            } else {
                BottomSheetDialog(context).apply {
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    binding.cardRoot.setBackgroundResource(R.drawable.bg_vortex_glass_card)
                    setContentView(binding.root)
                    setOnShowListener {
                        val bottomSheet = findViewById<FrameLayout>(
                            com.google.android.material.R.id.design_bottom_sheet
                        ) ?: return@setOnShowListener
                        bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                        BottomSheetBehavior.from(bottomSheet).apply {
                            state = BottomSheetBehavior.STATE_EXPANDED
                            skipCollapsed = true
                        }
                    }
                }
            }

            val wrapper = ChoiceDialog(context, binding, dialog)
            wrapper.pageStack.addLast(initialPage)
            wrapper.renderPage(initialPage, animate = false)

            dialog.setOnDismissListener { onDismiss?.invoke() }
            dialog.show()
            return dialog
        }
    }
}

private class RowAdapter(
    private val rows: List<ChoiceRow>,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<RowAdapter.Holder>() {

    class Holder(val binding: ItemChoiceRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemChoiceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.rowLabel.text = row.title

        if (row.iconRes != null) {
            // Root menu row with leading icon, optional subtitle value, and chevron
            holder.binding.rowIcon.visibility = View.VISIBLE
            holder.binding.rowIcon.setImageResource(row.iconRes)
            holder.binding.rowRadio.visibility = View.GONE

            if (row.subtitle != null) {
                holder.binding.rowValue.visibility = View.VISIBLE
                holder.binding.rowValue.text = row.subtitle
                holder.binding.rowChevron.visibility = View.VISIBLE
            } else {
                holder.binding.rowValue.visibility = View.GONE
                holder.binding.rowChevron.visibility = View.GONE
            }

            holder.binding.rowLabel.setTextColor(Color.WHITE)
            holder.binding.rowLabel.setTypeface(null, Typeface.NORMAL)
            holder.binding.rowLabel.alpha = if (row.enabled) 1f else 0.4f
        } else {
            // Submenu row with radio button
            holder.binding.rowIcon.visibility = View.GONE
            holder.binding.rowValue.visibility = View.GONE
            holder.binding.rowChevron.visibility = View.GONE
            holder.binding.rowRadio.visibility = View.VISIBLE

            holder.binding.rowLabel.alpha = if (row.enabled) 1f else 0.4f
            if (row.selected) {
                holder.binding.rowLabel.setTextColor(Color.WHITE)
                holder.binding.rowLabel.setTypeface(null, Typeface.BOLD)
                holder.binding.rowRadio.setImageResource(R.drawable.ic_vortex_radio_checked)
            } else {
                holder.binding.rowLabel.setTextColor(Color.parseColor("#99FFFFFF"))
                holder.binding.rowLabel.setTypeface(null, Typeface.NORMAL)
                holder.binding.rowRadio.setImageResource(R.drawable.ic_vortex_radio_unchecked)
            }
        }
        holder.binding.root.setOnClickListener { if (row.enabled) onPick(position) }
    }
}