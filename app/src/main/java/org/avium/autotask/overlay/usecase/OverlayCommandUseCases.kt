package org.avium.autotask.overlay.usecase

import org.avium.autotask.overlay.core.OverlayEvent
import org.avium.autotask.overlay.core.OverlayStore
import org.avium.autotask.overlay.launch.TargetLaunchRequest

class StartOverlayUseCase(
    private val store: OverlayStore,
) {
    operator fun invoke(request: TargetLaunchRequest) {
        store.dispatch(OverlayEvent.Command.Start(request))
    }
}

class SetTouchPassthroughUseCase(
    private val store: OverlayStore,
) {
    operator fun invoke(enabled: Boolean) {
        store.dispatch(OverlayEvent.Command.SetTouchPassthrough(enabled))
    }
}

class ToggleOverlaySizeUseCase(
    private val store: OverlayStore,
) {
    operator fun invoke() {
        store.dispatch(OverlayEvent.Command.ToggleSize)
    }
}

class StopOverlayUseCase(
    private val store: OverlayStore,
) {
    operator fun invoke() {
        store.dispatch(OverlayEvent.Command.Stop)
    }
}
