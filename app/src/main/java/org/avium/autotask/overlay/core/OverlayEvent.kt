package org.avium.autotask.overlay.core

import org.avium.autotask.overlay.launch.TargetLaunchRequest

sealed interface OverlayEvent {
    sealed interface Command : OverlayEvent {
        data class Start(
            val request: TargetLaunchRequest,
        ) : Command

        data class SetTouchPassthrough(
            val enabled: Boolean,
        ) : Command

        data object ToggleSize : Command

        data object Stop : Command
    }

    sealed interface Touch : OverlayEvent {
        val actionMasked: Int

        enum class Source {
            BACKGROUND,
            CONTENT,
            MINI,
            MASKED_MINI,
        }

        data class Motion(
            val source: Source,
            override val actionMasked: Int,
        ) : Touch
    }

    sealed interface System : OverlayEvent {
        data class ModeChanged(
            val mode: OverlayMode,
        ) : System

        data class TouchSubStateChanged(
            val subState: OverlayTouchSubState,
        ) : System

        data class DockSideChanged(
            val dockSide: OverlayDockSide,
        ) : System

        data class VirtualDisplayReady(
            val ready: Boolean,
        ) : System

        data class ActiveTransitionChanged(
            val transition: String?,
        ) : System

        data class PassthroughChanged(
            val enabled: Boolean,
        ) : System

        data object WindowAttached : System
        data object SurfaceDestroyed : System
    }

    sealed interface Animation : OverlayEvent {
        data class Started(
            val transition: String,
        ) : Animation

        data class Ended(
            val transition: String,
        ) : Animation

        data class Cancelled(
            val transition: String,
        ) : Animation
    }
}
