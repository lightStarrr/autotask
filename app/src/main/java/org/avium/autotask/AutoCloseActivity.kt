package org.avium.autotask

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display

/**
 * 一个会自动关闭的 Activity，用于在关闭虚拟屏幕前提供过渡
 * 防止虚拟显示器中的应用在关闭时跳转到主显示器
 */
class AutoCloseActivity : Activity() {
    private val tag = "AutoCloseActivity"
    private var initialDisplayId: Int = Display.INVALID_DISPLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDisplay = display
        initialDisplayId = currentDisplay?.displayId ?: Display.INVALID_DISPLAY
        Log.d(tag, "AutoCloseActivity created on display $initialDisplayId")

        // 如果直接在主显示器上创建（不应该发生），立即关闭
        if (initialDisplayId == Display.DEFAULT_DISPLAY) {
            Log.w(tag, "AutoCloseActivity created on default display, closing immediately")
            finish()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // 检测显示器变化
        val currentDisplayId = display?.displayId ?: Display.INVALID_DISPLAY
        Log.d(tag, "Configuration changed, display: $initialDisplayId -> $currentDisplayId")

        // 如果被迁移到主显示器，立即关闭
        if (currentDisplayId != initialDisplayId && currentDisplayId == Display.DEFAULT_DISPLAY) {
            Log.d(tag, "Detected migration to default display, closing")
            finish()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // 双重检查显示器 ID
        val currentDisplayId = display?.displayId ?: Display.INVALID_DISPLAY
        Log.d(tag, "Window attached to display $currentDisplayId")

        if (currentDisplayId == Display.DEFAULT_DISPLAY && initialDisplayId != Display.DEFAULT_DISPLAY) {
            Log.d(tag, "Window attached to default display (migrated), closing")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "AutoCloseActivity destroyed")
    }
}
