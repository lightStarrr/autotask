package org.avium.autotask.overlay.gesture

import android.view.MotionEvent
import org.avium.autotask.overlay.core.OverlayEvent

class OverlayGestureInterpreter {
    fun asMotion(
        source: OverlayEvent.Touch.Source,
        event: MotionEvent,
    ): OverlayEvent.Touch.Motion {
        return OverlayEvent.Touch.Motion(
            source = source,
            actionMasked = event.actionMasked,
        )
    }
}
