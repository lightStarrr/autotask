# Overlay Architecture (v1)

## 1) Module Boundary Overview

```text
OverlayEntryActivity / DebugTriggerActivity
        |
        v
OverlayCommandDispatcher (builds stable protocol intents)
        |
        v
OverlayService (lifecycle + command ingress)
        |
        v
OverlayStore --(state,event)-> OverlayReducer --(effects)-> Effect Executor
        |                                             |
        |                                             +--> OverlayWindowHost / OverlayAnimator
        |                                             +--> TargetIntentResolver + TargetLauncher
        |                                             +--> DisplayTouchInjector
        v
OverlayTraceLogger
```

### Responsibilities
- `overlay/contract`
  - Stable external protocol constants and command dispatching.
- `overlay/core`
  - `OverlayState`, `OverlayEvent`, `OverlayEffect`, `OverlayReducer`, `OverlayStore`.
  - Reducer is pure: no Android framework calls.
- `overlay/service`
  - Android `Service` lifecycle, command parsing, effect execution, and wiring.
- `overlay/window`
  - Window params factory, geometry calculator, and safe window operations host.
- `overlay/launch`
  - Target intent candidate resolution and launch execution.
- `overlay/input`
  - Motion event injection bridge for virtual display.
- `overlay/gesture`
  - MotionEvent -> domain touch event adaptation.
- `overlay/animation`
  - Reusable animator wrapper with start/frame/end/cancel callbacks.
- `overlay/log`
  - State transition trace logging for debugging.

## 2) Event -> State Transition Table

| Event | Previous | Next (core) | Notes |
|---|---|---|---|
| `Command.Start` | any | keep mode, `touchPassthrough=false`, set `targetRequest` | emits `EnsureOverlay` + `ApplyTouchPassthrough(false)` |
| `Command.SetTouchPassthrough(enabled)` | any | update `context.touchPassthrough` | emits `ApplyTouchPassthrough(enabled)` |
| `Command.ToggleSize` | any | unchanged | emits `ToggleOverlaySize` |
| `Command.Stop` | any | unchanged | emits `StopOverlay` |
| `Touch.Motion(BACKGROUND, MOVE)` | non-animating | `touchSubState=HANDLE_DRAGGING` | gesture-only tracking |
| `Touch.Motion(MINI, MOVE)` | non-animating | `touchSubState=MINI_DRAGGING` | gesture-only tracking |
| `Touch.Motion(MASKED_MINI, MOVE)` | non-animating | `touchSubState=MASKED_MINI_DRAGGING` | gesture-only tracking |
| `Touch.Motion(*, UP/CANCEL)` | non-animating | `touchSubState=IDLE` | gesture-only tracking |
| `System.ModeChanged(mode)` | any | `mode=mode` | emitted from service when visual mode flips |
| `System.DockSideChanged(side)` | any | update `context.dockSide` | emitted when snapping side changes |
| `System.VirtualDisplayReady(ready)` | any | update readiness flag | lifecycle signal |
| `System.ActiveTransitionChanged(name?)` | any | set `touchSubState=ANIMATING` when non-null else `IDLE` | keeps animation gating in state |
| `System.PassthroughChanged(enabled)` | any | mirrors effective passthrough | sync after effect execution |

### Touch Sub-States
- `IDLE`
- `HANDLE_DRAGGING`
- `MINI_DRAGGING`
- `MASKED_MINI_DRAGGING`
- `ANIMATING`

## 3) Effect Catalog and Execution Order

### Effect Types
- `EnsureOverlay(request)`
- `ApplyTouchPassthrough(enabled)`
- `ToggleOverlaySize`
- `StopOverlay`
- `Trace(message)`

### Execution Mapping (Service)
1. `EnsureOverlay`
   - sync local target fields
   - set passthrough baseline
   - call `ensureOverlay()`
2. `ApplyTouchPassthrough`
   - call `setTouchPassthroughInternal()`
3. `ToggleOverlaySize`
   - call `toggleWindowSize()`
4. `StopOverlay`
   - call `stopVirtualDisplayAndService()`
5. `Trace`
   - log via `OverlayTraceLogger`

### Core Runtime Flow
1. Command intent arrives in `OverlayService`.
2. Service invokes command use case.
3. Use case dispatches domain event to store.
4. Store runs reducer and updates state snapshot.
5. Store executes generated effects.
6. Service executes effects by invoking framework operations.

## 4) Stable Protocol Definition (for future external integration)

## Protocol Version
- `EXTRA_PROTOCOL_VERSION = 1`

## Actions
- `org.avium.autotask.overlay.action.START`
- `org.avium.autotask.overlay.action.STOP`
- `org.avium.autotask.overlay.action.TOGGLE_SIZE`
- `org.avium.autotask.overlay.action.SET_TOUCH_PASSTHROUGH`

## Extras
- `org.avium.autotask.overlay.extra.QUESTION`
- `org.avium.autotask.overlay.extra.TARGET_PACKAGE`
- `org.avium.autotask.overlay.extra.TARGET_ACTIVITY`
- `org.avium.autotask.overlay.extra.TOUCH_PASSTHROUGH`
- `org.avium.autotask.overlay.extra.PROTOCOL_VERSION`

## Dispatcher API
Use `OverlayCommandDispatcher` as the single intent builder/entry point:
- `start(context, question, targetPackage, targetActivity)`
- `stop(context)`
- `toggleSize(context)`
- `setTouchPassthrough(context, enabled)`

## 5) Compatibility Notes

- This refactor intentionally moves from old action/extra naming to the stable v1 overlay protocol.
- `MainActivity` is replaced by `OverlayEntryActivity`, and `FullScreenOverlayService` is replaced by `OverlayService` in manifest wiring.
- Internal behavior goals remain unchanged (drag thresholds, animation durations, launch fallback order, touch injection flow).
- No state persistence is added in this iteration.
- Current contract is treated as the baseline for future external integrations.
