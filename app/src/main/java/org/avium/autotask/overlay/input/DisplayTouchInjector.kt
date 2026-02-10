package org.avium.autotask.overlay.input

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.MotionEvent

class DisplayTouchInjector(
    private val inputManager: InputManager,
) {
    fun inject(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long = SystemClock.uptimeMillis(),
    ): Boolean {
        return InputInjector.injectMotionEvent(
            inputManager = inputManager,
            displayId = displayId,
            action = action,
            x = x,
            y = y,
            downTime = downTime,
        )
    }

    fun inject(
        displayId: Int,
        event: MotionEvent,
        maxWidth: Int,
        maxHeight: Int,
        downTime: Long,
    ): Boolean {
        val x = event.x.coerceIn(0f, maxWidth.toFloat())
        val y = event.y.coerceIn(0f, maxHeight.toFloat())
        return inject(
            displayId = displayId,
            action = event.actionMasked,
            x = x,
            y = y,
            downTime = downTime,
        )
    }
}
