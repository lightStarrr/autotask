package org.avium.autotask

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

object OverlayEffect {
    private const val BASE_FLAGS =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    fun buildBackgroundLayoutParams(touchable: Boolean): WindowManager.LayoutParams {
        return createParams(
            touchable = touchable,
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    fun buildContentLayoutParams(
        touchable: Boolean,
        trustedOverlay: Boolean,
        width: Int,
        height: Int
    ): WindowManager.LayoutParams {
        val windowType = if (trustedOverlay) {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return createParams(
            touchable = touchable,
            type = windowType,
            width = width,
            height = height
        )
    }

    fun buildMiniTouchLayoutParams(width: Int, height: Int): WindowManager.LayoutParams {
        return createParams(
            touchable = true,
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            width = width,
            height = height
        )
    }

    private fun createParams(
        touchable: Boolean,
        type: Int,
        width: Int,
        height: Int
    ): WindowManager.LayoutParams {
        val flags = if (touchable) {
            BASE_FLAGS
        } else {
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.RGBA_8888
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }
}
