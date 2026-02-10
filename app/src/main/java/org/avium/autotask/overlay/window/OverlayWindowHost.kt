package org.avium.autotask.overlay.window

import android.util.Log
import android.view.View
import android.view.WindowManager

class OverlayWindowHost(
    private val windowManager: WindowManager,
    private val logTag: String,
) {
    fun add(
        view: View?,
        params: WindowManager.LayoutParams?,
    ): Boolean {
        if (view == null || params == null) {
            return false
        }
        return try {
            windowManager.addView(view, params)
            true
        } catch (e: Exception) {
            Log.e(logTag, "Failed to add overlay view", e)
            false
        }
    }

    fun update(
        view: View?,
        params: WindowManager.LayoutParams?,
    ) {
        if (view == null || params == null || view.windowToken == null) {
            return
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(logTag, "Failed to update overlay view", e)
        }
    }

    fun remove(view: View?) {
        if (view == null || view.windowToken == null) {
            return
        }
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(logTag, "Failed to remove overlay view", e)
        }
    }

    fun setTouchable(
        view: View?,
        params: WindowManager.LayoutParams?,
        touchable: Boolean,
    ) {
        if (view == null || params == null) {
            return
        }

        val currentlyTouchable = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (currentlyTouchable == touchable) {
            return
        }

        params.flags =
            if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        update(view, params)
    }
}
