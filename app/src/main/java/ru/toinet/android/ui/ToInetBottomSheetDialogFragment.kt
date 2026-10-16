package ru.toinet.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout

import android.os.Build
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

import ru.toinet.android.R

open class ToInetBottomSheetDialogFragment : BottomSheetDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireActivity(), theme)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyNavigationBarColor(dialog)
        dialog.setOnShowListener {
            val bottomSheetView =
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            bottomSheetView?.let {
                it.setBackgroundResource(R.drawable.bottom_sheet_rounded)
                setHeightIfAttached(activity, it)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            applyNavigationBarColor(dialog)
        }

        return dialog
    }

    private fun applyNavigationBarColor(dialog: Dialog) {
        dialog.window?.let { window ->
            val typedValue = TypedValue()
            val resolvedTheme = context?.theme ?: requireContext().theme
            val colorSurfaceContainer = if (resolvedTheme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)) {
                typedValue.data
            } else if (resolvedTheme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)) {
                typedValue.data
            } else {
                Color.BLACK
            }

            window.navigationBarColor = colorSurfaceContainer

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }

            val isLight = ColorUtils.calculateLuminance(colorSurfaceContainer) > 0.5
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = isLight
        }
    }

    private fun setHeightIfAttached(activity: Activity?, bottomSheet: View) {
        val ratio = getHeightRatio()
        if (ratio <= 0f) return

        activity?.let {
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            val height = (displayMetrics.heightPixels * ratio).toInt()
            val layoutParams = bottomSheet.layoutParams
            layoutParams.height = height
            bottomSheet.layoutParams = layoutParams
        }
    }

    open fun getHeightRatio(): Float = 4 / 5f

    @SuppressLint("ClickableViewAccessibility")
    protected fun configureMultilineEditTextScrollEvent(editText: EditText) {
        // need this for scrolling an edittext in a BSDF
        editText.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_UP -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }
}