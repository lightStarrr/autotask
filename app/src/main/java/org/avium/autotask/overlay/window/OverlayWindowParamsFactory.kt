package org.avium.autotask.overlay.window

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

object OverlayWindowParamsFactory {
    private const val BASE_FLAGS =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    fun buildBackgroundLayoutParams(
        touchable: Boolean,
        trustedOverlay: Boolean,
    ): WindowManager.LayoutParams {
        return createParams(
            touchable = touchable,
            type = resolveWindowType(trustedOverlay),
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    fun buildContentLayoutParams(
        touchable: Boolean,
        trustedOverlay: Boolean,
        width: Int,
        height: Int,
    ): WindowManager.LayoutParams {
        return createParams(
            touchable = touchable,
            type = resolveWindowType(trustedOverlay),
            width = width,
            height = height,
        )
    }

    fun buildMiniTouchLayoutParams(
        width: Int,
        height: Int,
        trustedOverlay: Boolean,
    ): WindowManager.LayoutParams {
        return createParams(
            touchable = true,
            type = resolveWindowType(trustedOverlay),
            width = width,
            height = height,
        )
    }

    private fun resolveWindowType(trustedOverlay: Boolean): Int {
        return if (trustedOverlay) {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    private fun createParams(
        touchable: Boolean,
        type: Int,
        width: Int,
        height: Int,
    ): WindowManager.LayoutParams {
        val flags =
            if (touchable) {
                BASE_FLAGS
            } else {
                BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.RGBA_8888,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }
}
