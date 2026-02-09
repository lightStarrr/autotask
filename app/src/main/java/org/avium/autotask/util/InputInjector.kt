package org.avium.autotask.util

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method

@SuppressLint("DiscouragedPrivateApi", "BlockedPrivateApi")
object InputInjector {
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    init {
        try {
            injectInputEventMethod = InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            injectInputEventMethod?.isAccessible = true

            setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
            setDisplayIdMethod?.isAccessible = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun injectMotionEvent(
        inputManager: InputManager,
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long = SystemClock.uptimeMillis()
    ): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            x,
            y,
            0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                setDisplayIdMethod?.invoke(this, displayId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return try {
            val result = injectInputEventMethod?.invoke(
                inputManager,
                event,
                0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
            ) as? Boolean ?: false
            event.recycle()
            result
        } catch (e: Exception) {
            e.printStackTrace()
            event.recycle()
            false
        }
    }
}
