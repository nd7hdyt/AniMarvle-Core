package com.animarvle.app.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.animarvle.app.R
import com.animarvle.app.databinding.BottomSheetPlaybackSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * AniVortex Playback Settings Glassy Bottom Sheet.
 * Direct entrance to Speed, Quality, and Audio sub-settings.
 */
class PlaybackSettingsBottomSheet(
    private val context: Context,
    private val currentSpeed: String,
    private val currentQuality: String = "",
    private val currentAudio: String = "",
    private val isOffline: Boolean = false,
    private val onDismiss: (() -> Unit)? = null,
    private val onSpeedClick: () -> Unit,
    private val onQualityClick: () -> Unit = {},
    private val onAudioClick: () -> Unit = {},
) {
    fun show(): Dialog {
        val binding = BottomSheetPlaybackSettingsBinding.inflate(LayoutInflater.from(context))
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val displayMetrics = context.resources.displayMetrics

        var isTransitioningToSubmenu = false

        val dialog: Dialog = if (isLandscape) {
            Dialog(context, R.style.Theme_Animarvle_FloatingDialog).apply {
                setContentView(binding.root)
                setCanceledOnTouchOutside(true)
                window?.apply {
                    setBackgroundDrawableResource(R.drawable.bg_vortex_glass_card)
                    setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    setWindowAnimations(R.style.Animation_Animarvle_FloatingCard)

                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        attributes.blurBehindRadius = (30 * displayMetrics.density).toInt()
                    }
                    setDimAmount(0.15f)

                    setLayout(
                        (displayMetrics.widthPixels * 0.70f).toInt(),
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                    attributes = attributes.apply {
                        y = (12 * displayMetrics.density).toInt()
                    }
                }
            }
        } else {
            val bsDialog = BottomSheetDialog(context)
            bsDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            binding.cardRoot.setBackgroundResource(R.drawable.bg_vortex_glass_card)
            bsDialog.setContentView(binding.root)
            bsDialog.setOnShowListener {
                val bottomSheet = bsDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
            bsDialog
        }

        binding.valueSpeed.text = currentSpeed
        binding.valueQuality.text = currentQuality
        binding.valueAudio.text = currentAudio

        if (isOffline) {
            binding.rowQuality.visibility = View.GONE
            binding.rowAudio.visibility = View.GONE
        } else {
            binding.rowQuality.visibility = View.VISIBLE
            binding.rowAudio.visibility = View.VISIBLE
        }

        binding.rowSpeed.setOnClickListener {
            isTransitioningToSubmenu = true
            dialog.window?.setWindowAnimations(0)
            dialog.dismiss()
            onSpeedClick()
        }
        binding.rowQuality.setOnClickListener {
            isTransitioningToSubmenu = true
            dialog.window?.setWindowAnimations(0)
            dialog.dismiss()
            onQualityClick()
        }
        binding.rowAudio.setOnClickListener {
            isTransitioningToSubmenu = true
            dialog.window?.setWindowAnimations(0)
            dialog.dismiss()
            onAudioClick()
        }

        dialog.setOnDismissListener {
            if (!isTransitioningToSubmenu) {
                onDismiss?.invoke()
            }
        }

        dialog.show()
        return dialog
    }
}
