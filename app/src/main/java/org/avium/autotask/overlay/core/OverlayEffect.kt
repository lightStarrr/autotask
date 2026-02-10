package org.avium.autotask.overlay.core

import org.avium.autotask.overlay.launch.TargetLaunchRequest

sealed interface OverlayEffect {
    data class EnsureOverlay(
        val request: TargetLaunchRequest,
    ) : OverlayEffect

    data class ApplyTouchPassthrough(
        val enabled: Boolean,
    ) : OverlayEffect

    data object ToggleOverlaySize : OverlayEffect
    data object StopOverlay : OverlayEffect

    data class Trace(
        val message: String,
    ) : OverlayEffect
}
