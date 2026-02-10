package org.avium.autotask.overlay.core

class OverlayStore(
    initialState: OverlayState,
    private val reducer: OverlayReducer,
    private val effectExecutor: (OverlayEffect) -> Unit,
    private val onStateChanged: (OverlayState, OverlayEvent) -> Unit,
) {
    private val lock = Any()
    private var state: OverlayState = initialState

    fun snapshot(): OverlayState = synchronized(lock) { state }

    fun dispatch(event: OverlayEvent) {
        val reduction = synchronized(lock) {
            val nextReduction = reducer.reduce(state, event)
            state = nextReduction.state
            nextReduction
        }

        onStateChanged(reduction.state, event)
        reduction.effects.forEach(effectExecutor)
    }
}
