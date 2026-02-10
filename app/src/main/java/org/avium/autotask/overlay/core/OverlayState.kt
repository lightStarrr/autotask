package org.avium.autotask.overlay.core

import org.avium.autotask.overlay.launch.TargetLaunchRequest

enum class OverlayMode {
    LARGE,
    MINI,
    MINI_MASKED,
}

enum class OverlayTouchSubState {
    IDLE,
    HANDLE_DRAGGING,
    MINI_DRAGGING,
    MASKED_MINI_DRAGGING,
    ANIMATING,
}

enum class OverlayDockSide {
    LEFT,
    RIGHT,
}

data class OverlayRuntimeContext(
    val touchPassthrough: Boolean = false,
    val dockSide: OverlayDockSide = OverlayDockSide.RIGHT,
    val targetRequest: TargetLaunchRequest? = null,
    val virtualDisplayReady: Boolean = false,
    val activeTransition: String? = null,
)

data class OverlayState(
    val mode: OverlayMode = OverlayMode.LARGE,
    val touchSubState: OverlayTouchSubState = OverlayTouchSubState.IDLE,
    val context: OverlayRuntimeContext = OverlayRuntimeContext(),
)
