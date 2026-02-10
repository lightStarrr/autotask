package org.avium.autotask.overlay.log

import android.util.Log
import org.avium.autotask.overlay.core.OverlayEvent
import org.avium.autotask.overlay.core.OverlayState

class OverlayTraceLogger(
    private val enabled: Boolean,
) {
    fun log(
        state: OverlayState,
        event: OverlayEvent,
    ) {
        if (!enabled) {
            return
        }

        Log.d(
            TAG,
            "event=${event::class.simpleName} mode=${state.mode} touch=${state.touchSubState} dock=${state.context.dockSide} " +
                "passthrough=${state.context.touchPassthrough} displayReady=${state.context.virtualDisplayReady} transition=${state.context.activeTransition}",
        )
    }

    fun logEffect(message: String) {
        if (enabled) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "OverlayTrace"
    }
}
