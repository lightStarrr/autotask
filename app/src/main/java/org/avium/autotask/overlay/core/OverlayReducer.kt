package org.avium.autotask.overlay.core

import android.view.MotionEvent

data class OverlayReduction(
    val state: OverlayState,
    val effects: List<OverlayEffect> = emptyList(),
)

class OverlayReducer {
    fun reduce(
        previous: OverlayState,
        event: OverlayEvent,
    ): OverlayReduction {
        return when (event) {
            is OverlayEvent.Command.Start -> {
                val nextState =
                    previous.copy(
                        context =
                            previous.context.copy(
                                targetRequest = event.request,
                                touchPassthrough = false,
                            ),
                    )
                OverlayReduction(
                    state = nextState,
                    effects =
                        listOf(
                            OverlayEffect.EnsureOverlay(event.request),
                            OverlayEffect.ApplyTouchPassthrough(false),
                        ),
                )
            }

            is OverlayEvent.Command.SetTouchPassthrough -> {
                OverlayReduction(
                    state = previous.copy(context = previous.context.copy(touchPassthrough = event.enabled)),
                    effects = listOf(OverlayEffect.ApplyTouchPassthrough(event.enabled)),
                )
            }

            OverlayEvent.Command.ToggleSize -> {
                OverlayReduction(state = previous, effects = listOf(OverlayEffect.ToggleOverlaySize))
            }

            OverlayEvent.Command.Stop -> {
                OverlayReduction(state = previous, effects = listOf(OverlayEffect.StopOverlay))
            }

            is OverlayEvent.Touch.Motion -> {
                OverlayReduction(state = previous.copy(touchSubState = mapTouchSubState(previous, event)))
            }

            is OverlayEvent.System.ModeChanged -> {
                OverlayReduction(state = previous.copy(mode = event.mode))
            }

            is OverlayEvent.System.TouchSubStateChanged -> {
                OverlayReduction(state = previous.copy(touchSubState = event.subState))
            }

            is OverlayEvent.System.DockSideChanged -> {
                OverlayReduction(
                    state = previous.copy(context = previous.context.copy(dockSide = event.dockSide)),
                )
            }

            is OverlayEvent.System.VirtualDisplayReady -> {
                OverlayReduction(
                    state = previous.copy(context = previous.context.copy(virtualDisplayReady = event.ready)),
                )
            }

            is OverlayEvent.System.ActiveTransitionChanged -> {
                OverlayReduction(
                    state =
                        previous.copy(
                            context = previous.context.copy(activeTransition = event.transition),
                            touchSubState =
                                if (event.transition == null) {
                                    OverlayTouchSubState.IDLE
                                } else {
                                    OverlayTouchSubState.ANIMATING
                                },
                        ),
                )
            }

            is OverlayEvent.System.PassthroughChanged -> {
                OverlayReduction(
                    state = previous.copy(context = previous.context.copy(touchPassthrough = event.enabled)),
                )
            }

            OverlayEvent.System.WindowAttached,
            OverlayEvent.System.SurfaceDestroyed,
            is OverlayEvent.Animation.Started,
            is OverlayEvent.Animation.Ended,
            is OverlayEvent.Animation.Cancelled,
            -> OverlayReduction(previous)
        }
    }

    private fun mapTouchSubState(
        previous: OverlayState,
        event: OverlayEvent.Touch.Motion,
    ): OverlayTouchSubState {
        if (previous.touchSubState == OverlayTouchSubState.ANIMATING) {
            return OverlayTouchSubState.ANIMATING
        }

        return when (event.source) {
            OverlayEvent.Touch.Source.BACKGROUND -> {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    OverlayTouchSubState.HANDLE_DRAGGING
                } else {
                    OverlayTouchSubState.IDLE
                }
            }

            OverlayEvent.Touch.Source.CONTENT -> OverlayTouchSubState.IDLE

            OverlayEvent.Touch.Source.MINI -> {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    OverlayTouchSubState.MINI_DRAGGING
                } else {
                    OverlayTouchSubState.IDLE
                }
            }

            OverlayEvent.Touch.Source.MASKED_MINI -> {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    OverlayTouchSubState.MASKED_MINI_DRAGGING
                } else {
                    OverlayTouchSubState.IDLE
                }
            }
        }
    }
}
