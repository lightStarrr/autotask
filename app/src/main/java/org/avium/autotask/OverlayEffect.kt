package org.avium.autotask

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

object OverlayEffect {
    fun buildLayoutParams(
        touchPassthrough: Boolean,
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.MATCH_PARENT
    ): WindowManager.LayoutParams {
        // 基本标志：始终保持可交互以支持拖动和点击
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE // 不获取焦点，避免影响其他应用

        val type =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.RGBA_8888
        ).apply {
            gravity = Gravity.CENTER
        }
    }
}
