package org.avium.autotask.util

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InputInjector {
    private const val DEFAULT_TAP_HOLD_MS = 60L
    private const val DEFAULT_DOUBLE_TAP_INTERVAL_MS = 120L
    private const val DEFAULT_SWIPE_DURATION_MS = 300L
    private const val DEFAULT_SWIPE_STEPS = 12

    private val eventInjector: EventInjector by lazy { resolveInjector() }
    private val injectMode: Int by lazy { resolveInjectMode() }

    fun enableTouchPassthrough(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    fun disableTouchPassthrough(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    suspend fun tapThrough(window: Window, x: Float, y: Float, holdMs: Long = DEFAULT_TAP_HOLD_MS): Boolean {
        return injectThroughWindow(window) {
            tap(x, y, holdMs)
        }
    }

    suspend fun doubleTapThrough(
        window: Window,
        x: Float,
        y: Float,
        intervalMs: Long = DEFAULT_DOUBLE_TAP_INTERVAL_MS
    ): Boolean {
        return injectThroughWindow(window) {
            doubleTap(x, y, intervalMs)
        }
    }

    fun tap(x: Float, y: Float, holdMs: Long = DEFAULT_TAP_HOLD_MS): Boolean {
        val downTime = SystemClock.uptimeMillis()
        if (!injectMotion(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)) {
            return false
        }
        val upTime = SystemClock.uptimeMillis().coerceAtLeast(downTime + holdMs.coerceAtLeast(1L))
        val sleepMs = (upTime - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (sleepMs > 0) {
            SystemClock.sleep(sleepMs)
        }
        return injectMotion(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
    }

    fun doubleTap(x: Float, y: Float, intervalMs: Long = DEFAULT_DOUBLE_TAP_INTERVAL_MS): Boolean {
        if (!tap(x, y)) {
            return false
        }
        SystemClock.sleep(intervalMs.coerceAtLeast(1L))
        return tap(x, y)
    }

    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
        steps: Int = DEFAULT_SWIPE_STEPS
    ): Boolean {
        val safeSteps = steps.coerceAtLeast(1)
        val safeDuration = durationMs.coerceAtLeast(1L)
        val downTime = SystemClock.uptimeMillis()
        if (!injectMotion(MotionEvent.ACTION_DOWN, startX, startY, downTime, downTime)) {
            return false
        }

        for (step in 1..safeSteps) {
            val progress = step.toFloat() / safeSteps.toFloat()
            val x = startX + (endX - startX) * progress
            val y = startY + (endY - startY) * progress
            val targetTime = downTime + (safeDuration * progress).toLong()
            val waitMs = (targetTime - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            if (waitMs > 0) {
                SystemClock.sleep(waitMs)
            }
            if (!injectMotion(MotionEvent.ACTION_MOVE, x, y, downTime, SystemClock.uptimeMillis())) {
                return false
            }
        }

        return injectMotion(MotionEvent.ACTION_UP, endX, endY, downTime, SystemClock.uptimeMillis())
    }

    private suspend fun injectThroughWindow(window: Window, action: () -> Boolean): Boolean {
        return try {
            withContext(Dispatchers.Main) {
                enableTouchPassthrough(window)
            }
            withContext(Dispatchers.Default) {
                action()
            }
        } finally {
            withContext(Dispatchers.Main) {
                disableTouchPassthrough(window)
            }
        }
    }

    private fun injectMotion(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long
    ): Boolean {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return try {
            eventInjector.inject(event, injectMode)
        } finally {
            event.recycle()
        }
    }

    private fun resolveInjectMode(): Int {
        HiddenApiAccess.ensureExemptions()
        val modeNames = listOf(
            "INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH",
            "INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT",
            "INJECT_INPUT_EVENT_MODE_ASYNC"
        )
        val classNames = listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager"
        )

        for (className in classNames) {
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue
            for (modeName in modeNames) {
                val modeValue = runCatching {
                    clazz.getDeclaredField(modeName).apply { isAccessible = true }.getInt(null)
                }.getOrNull()
                if (modeValue != null) {
                    return modeValue
                }
            }
        }

        return 2
    }

    private fun resolveInjector(): EventInjector {
        HiddenApiAccess.ensureExemptions()
        resolveInjectorForClass("android.hardware.input.InputManagerGlobal")?.let { return it }
        resolveInjectorForClass("android.hardware.input.InputManager")?.let { return it }
        throw IllegalStateException("No injectable InputManager implementation found")
    }

    private fun resolveInjectorForClass(className: String): EventInjector? {
        return runCatching {
            val clazz = Class.forName(className)
            val instance = clazz.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }.invoke(null)

            val injectMethod = clazz.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply {
                isAccessible = true
            }

            EventInjector { event, mode ->
                (injectMethod.invoke(instance, event, mode) as? Boolean) == true
            }
        }.getOrNull()
    }

    private fun interface EventInjector {
        fun inject(event: InputEvent, mode: Int): Boolean
    }
}
