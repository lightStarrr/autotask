package org.avium.autotask.overlay.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import org.avium.autotask.R
import org.avium.autotask.overlay.animation.OverlayAnimator
import org.avium.autotask.overlay.contract.OverlayContract
import org.avium.autotask.overlay.core.OverlayDockSide
import org.avium.autotask.overlay.core.OverlayEffect
import org.avium.autotask.overlay.core.OverlayEvent
import org.avium.autotask.overlay.core.OverlayMode
import org.avium.autotask.overlay.core.OverlayReducer
import org.avium.autotask.overlay.core.OverlayState
import org.avium.autotask.overlay.core.OverlayStore
import org.avium.autotask.overlay.core.OverlayTouchSubState
import org.avium.autotask.overlay.gesture.OverlayGestureInterpreter
import org.avium.autotask.overlay.input.DisplayTouchInjector
import org.avium.autotask.overlay.launch.TargetIntentResolver
import org.avium.autotask.overlay.launch.TargetLaunchRequest
import org.avium.autotask.overlay.launch.TargetLauncher
import org.avium.autotask.overlay.log.OverlayTraceLogger
import org.avium.autotask.overlay.usecase.SetTouchPassthroughUseCase
import org.avium.autotask.overlay.usecase.StartOverlayUseCase
import org.avium.autotask.overlay.usecase.StopOverlayUseCase
import org.avium.autotask.overlay.usecase.ToggleOverlaySizeUseCase
import org.avium.autotask.overlay.window.OverlayGeometryCalculator
import org.avium.autotask.overlay.window.OverlayWindowHost
import org.avium.autotask.overlay.window.OverlayWindowParamsFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class OverlayService : Service() {
    private val tag = "OverlayService"
    private val traceLogger = OverlayTraceLogger(enabled = true)
    private val gestureInterpreter = OverlayGestureInterpreter()
    private val overlayAnimator = OverlayAnimator()

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var inputManager: InputManager
    private lateinit var activityManager: ActivityManager
    private lateinit var displayTouchInjector: DisplayTouchInjector
    private lateinit var targetIntentResolver: TargetIntentResolver
    private lateinit var targetLauncher: TargetLauncher
    private lateinit var geometryCalculator: OverlayGeometryCalculator
    private lateinit var windowHost: OverlayWindowHost

    private lateinit var overlayStore: OverlayStore
    private lateinit var startOverlayUseCase: StartOverlayUseCase
    private lateinit var setTouchPassthroughUseCase: SetTouchPassthroughUseCase
    private lateinit var toggleOverlaySizeUseCase: ToggleOverlaySizeUseCase
    private lateinit var stopOverlayUseCase: StopOverlayUseCase

    private var backgroundLayer: FrameLayout? = null
    private var backgroundParams: WindowManager.LayoutParams? = null

    private var contentLayer: FrameLayout? = null
    private var contentParams: WindowManager.LayoutParams? = null
    private var contentContainer: FrameLayout? = null
    private var textureView: TextureView? = null
    private var bottomHandleBar: View? = null

    private var miniTouchLayer: FrameLayout? = null
    private var miniTouchParams: WindowManager.LayoutParams? = null
    private var miniMaskView: View? = null
    private var miniHandleView: View? = null

    private var handleBarWidthPx = 0
    private var handleBarHeightPx = 0
    private var handleBarMarginPx = 0

    private var virtualDisplay: VirtualDisplay? = null

    private var windowMode = WindowMode.LARGE
    private var dockSide = DockSide.RIGHT
    private var isAnimating = false

    private var touchPassthrough = false
    private var touchDownTime = 0L

    private var screenWidth = 0
    private var screenHeight = 0
    private var topInset = 0
    private var bottomInset = 0

    private var largeWindowWidth = 0
    private var largeWindowHeight = 0
    private var largeWindowX = 0
    private var largeWindowY = 0
    private var largeCornerRadiusPx = 0

    private var miniSize = 0
    private var miniHeight = 0
    private var miniScale = 1f
    private var miniClipRect: Rect = Rect()
    private var miniCornerRadiusPx = 0

    private var hasMiniPosition = false
    private var lastMiniVisibleX = 0
    private var lastMiniVisibleY = 0

    private var backgroundDownRawX = 0f
    private var backgroundDownRawY = 0f

    private var dragStartY = 0f
    private var dragDistance = 0f
    private var previewDragDistance = 0f
    private var isDraggingHandleBar = false
    private var isHandleDragDetected = false

    private var miniDownRawX = 0f
    private var miniDownRawY = 0f
    private var miniStartX = 0
    private var miniStartY = 0
    private var isMiniDragging = false

    private var question: String? = null
    private var targetPackage: String = OverlayContract.DEFAULT_TARGET_PACKAGE
    private var targetActivity: String? = null

    private val contentOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (windowMode == WindowMode.LARGE) {
                outline.setRoundRect(0, 0, view.width, view.height, largeCornerRadiusPx.toFloat())
                return
            }
            if (miniClipRect.isEmpty) {
                outline.setRect(0, 0, view.width, view.height)
                return
            }
            outline.setRoundRect(miniClipRect, miniCornerRadiusPx.toFloat())
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        displayTouchInjector = DisplayTouchInjector(inputManager)
        targetIntentResolver = TargetIntentResolver(packageManager, tag)
        targetLauncher = TargetLauncher(this, tag)
        geometryCalculator = OverlayGeometryCalculator()
        windowHost = OverlayWindowHost(windowManager, tag)

        overlayStore =
            OverlayStore(
                initialState = OverlayState(),
                reducer = OverlayReducer(),
                effectExecutor = ::executeOverlayEffect,
                onStateChanged = { state, event -> traceLogger.log(state, event) },
            )
        startOverlayUseCase = StartOverlayUseCase(overlayStore)
        setTouchPassthroughUseCase = SetTouchPassthroughUseCase(overlayStore)
        toggleOverlaySizeUseCase = ToggleOverlaySizeUseCase(overlayStore)
        stopOverlayUseCase = StopOverlayUseCase(overlayStore)
        overlayStore.dispatch(OverlayEvent.System.DockSideChanged(OverlayDockSide.RIGHT))
        overlayStore.dispatch(OverlayEvent.System.PassthroughChanged(false))

        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OverlayContract.ACTION_START -> {
                val request =
                    TargetLaunchRequest(
                        question = intent.getStringExtra(OverlayContract.EXTRA_QUESTION),
                        targetPackage =
                            intent.getStringExtra(OverlayContract.EXTRA_TARGET_PACKAGE)
                                ?: OverlayContract.DEFAULT_TARGET_PACKAGE,
                        targetActivity = intent.getStringExtra(OverlayContract.EXTRA_TARGET_ACTIVITY),
                    )
                startOverlayUseCase(request)
            }

            OverlayContract.ACTION_SET_TOUCH_PASSTHROUGH -> {
                val enabled = intent.getBooleanExtra(OverlayContract.EXTRA_TOUCH_PASSTHROUGH, false)
                setTouchPassthroughUseCase(enabled)
            }

            OverlayContract.ACTION_TOGGLE_SIZE -> toggleOverlaySizeUseCase()
            OverlayContract.ACTION_STOP -> stopOverlayUseCase()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        overlayAnimator.cancel()

        removeViewImmediateSafely(miniTouchLayer)
        removeViewImmediateSafely(contentLayer)
        removeViewImmediateSafely(backgroundLayer)

        miniTouchLayer = null
        miniTouchParams = null
        miniMaskView = null
        miniHandleView = null

        contentLayer = null
        contentParams = null
        contentContainer = null
        textureView = null
        bottomHandleBar = null

        backgroundLayer = null
        backgroundParams = null

        virtualDisplay?.release()
        virtualDisplay = null
        overlayStore.dispatch(OverlayEvent.System.VirtualDisplayReady(false))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun executeOverlayEffect(effect: OverlayEffect) {
        when (effect) {
            is OverlayEffect.EnsureOverlay -> {
                question = effect.request.question
                targetPackage = effect.request.targetPackage
                targetActivity = effect.request.targetActivity
                touchPassthrough = false
                ensureOverlay()
            }

            is OverlayEffect.ApplyTouchPassthrough -> {
                setTouchPassthroughInternal(effect.enabled)
            }

            OverlayEffect.ToggleOverlaySize -> {
                toggleWindowSize()
            }

            OverlayEffect.StopOverlay -> {
                stopVirtualDisplayAndService()
            }

            is OverlayEffect.Trace -> {
                traceLogger.logEffect(effect.message)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureOverlay() {
        if (backgroundLayer != null && contentLayer != null && miniTouchLayer != null) {
            launchTargetOnDisplay()
            return
        }

        calculateGeometry()
        val trustedOverlay = hasInternalSystemWindowPermission()

        backgroundParams = OverlayWindowParamsFactory.buildBackgroundLayoutParams(
            touchable = true,
            trustedOverlay = trustedOverlay
        )
        backgroundLayer = FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, event ->
                handleBackgroundTouch(event)
                true
            }
        }

        contentParams = OverlayWindowParamsFactory.buildContentLayoutParams(
            touchable = true,
            trustedOverlay = trustedOverlay,
            width = largeWindowWidth,
            height = largeWindowHeight
        ).apply {
            x = largeWindowX
            y = largeWindowY
        }

        contentLayer = FrameLayout(this).apply {
            pivotX = largeWindowWidth.toFloat()
            pivotY = largeWindowHeight.toFloat()
            scaleX = 1f
            scaleY = 1f
            clipBounds = null
            clipToOutline = false
            outlineProvider = contentOutlineProvider
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, event ->
                handleContentTouch(event)
                true
            }
        }

        contentContainer = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        textureView = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    attachVirtualDisplay(Surface(surfaceTexture), width, height)
                    applyTextureTransformForState()
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    applyTextureTransformForState()
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                    overlayStore.dispatch(OverlayEvent.System.SurfaceDestroyed)
                    overlayStore.dispatch(OverlayEvent.System.VirtualDisplayReady(false))
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit
            }
        }

        contentContainer?.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        contentLayer?.addView(
            contentContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        handleBarHeightPx = dpToPx(HANDLE_BAR_HEIGHT_DP)
        handleBarMarginPx = dpToPx(HANDLE_BAR_MARGIN_DP)
        handleBarWidthPx = dpToPx(HANDLE_BAR_WIDTH_DP)

        bottomHandleBar = View(this).apply {
            setBackgroundResource(R.drawable.bottom_handle_bar)
            visibility = View.VISIBLE
        }
        backgroundLayer?.addView(
            bottomHandleBar,
            FrameLayout.LayoutParams(handleBarWidthPx, handleBarHeightPx).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = 0
                topMargin = 0
            }
        )

        miniTouchParams = OverlayWindowParamsFactory.buildMiniTouchLayoutParams(
            width = miniSize,
            height = miniHeight,
            trustedOverlay = trustedOverlay
        ).apply {
            val defaultMini = resolveMiniTargetPosition()
            x = defaultMini.first
            y = defaultMini.second
        }

        miniTouchLayer = FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
            visibility = View.GONE
            setOnTouchListener { _, event ->
                handleMiniTouch(event)
                true
            }
        }

        miniMaskView = View(this).apply {
            background = createMiniMaskDrawable()
            visibility = View.GONE
        }

        miniHandleView = View(this).apply {
            setBackgroundResource(R.drawable.edge_handle_vertical)
            visibility = View.GONE
        }

        miniTouchLayer?.addView(
            miniMaskView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        miniTouchLayer?.addView(
            miniHandleView,
            FrameLayout.LayoutParams(dpToPx(MINI_HANDLE_WIDTH_DP), dpToPx(MINI_HANDLE_HEIGHT_DP))
        )

        updateMiniHandlePosition()

        if (
            !windowHost.add(backgroundLayer, backgroundParams) ||
            !windowHost.add(contentLayer, contentParams) ||
            !windowHost.add(miniTouchLayer, miniTouchParams)
        ) {
            return
        }
        overlayStore.dispatch(OverlayEvent.System.WindowAttached)

        applyLargeVisualState(immediate = true)
        launchTargetOnDisplay()
    }

    private fun calculateGeometry() {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val systemBarInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())

        val geometry =
            geometryCalculator.calculate(
                screenWidth = bounds.width(),
                screenHeight = bounds.height(),
                topInset = systemBarInsets.top,
                bottomInset = systemBarInsets.bottom,
                dpToPx = ::dpToPx,
                virtualAspect = VIRTUAL_ASPECT,
                largeWidthRatio = LARGE_WIDTH_RATIO,
                maxLargeHeightRatio = MAX_LARGE_HEIGHT_RATIO,
                miniWidthRatio = MINI_WIDTH_RATIO,
                miniMinSizeDp = MINI_MIN_SIZE_DP,
                miniCornerRadiusDp = MINI_CORNER_RADIUS_DP,
            )

        screenWidth = geometry.screenWidth
        screenHeight = geometry.screenHeight
        topInset = geometry.topInset
        bottomInset = geometry.bottomInset
        largeWindowWidth = geometry.largeWindowWidth
        largeWindowHeight = geometry.largeWindowHeight
        largeWindowX = geometry.largeWindowX
        largeWindowY = geometry.largeWindowY
        largeCornerRadiusPx = dpToPx(LARGE_CORNER_RADIUS_DP)
        miniSize = geometry.miniSize
        miniHeight = geometry.miniHeight
        miniScale = geometry.miniScale
        miniClipRect = Rect(geometry.miniClipRect)
        miniCornerRadiusPx = geometry.miniCornerRadiusPx
    }

    private fun attachVirtualDisplay(surface: Surface, width: Int, height: Int) {
        val densityDpi = resources.displayMetrics.densityDpi
        if (virtualDisplay == null) {
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            virtualDisplay = displayManager.createVirtualDisplay(
                "AutoTask@${SystemClock.uptimeMillis()}",
                width,
                height,
                densityDpi,
                surface,
                flags
            )
            overlayStore.dispatch(OverlayEvent.System.VirtualDisplayReady(true))
            launchTargetOnDisplay()
        } else {
            virtualDisplay?.surface = surface
            overlayStore.dispatch(OverlayEvent.System.VirtualDisplayReady(true))
        }
    }

    private fun launchTargetOnDisplay() {
        val display = virtualDisplay?.display ?: return
        val request =
            TargetLaunchRequest(
                question = question,
                targetPackage = targetPackage,
                targetActivity = targetActivity,
            )
        val candidates = targetIntentResolver.resolveCandidates(request)
        targetLauncher.launchOnDisplay(display.displayId, request, candidates)
    }

    private fun stopVirtualDisplayAndService() {
        if (targetPackage != OverlayContract.DEFAULT_TARGET_PACKAGE) {
            try {
                Log.d(tag, "Force stopping package: $targetPackage")
                val method = activityManager.javaClass.getMethod("forceStopPackage", String::class.java)
                method.invoke(activityManager, targetPackage)
                Log.d(tag, "Successfully force stopped $targetPackage")
            } catch (e: Exception) {
                Log.e(tag, "Failed to force stop package", e)
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 500)
    }

    private fun setTouchPassthroughInternal(enabled: Boolean) {
        touchPassthrough = enabled
        overlayStore.dispatch(OverlayEvent.System.PassthroughChanged(enabled))
    }

    private fun updateWindowMode(next: WindowMode) {
        if (windowMode == next) {
            return
        }
        windowMode = next
        overlayStore.dispatch(
            OverlayEvent.System.ModeChanged(
                mode = next.toCoreMode(),
            ),
        )
    }

    private fun updateDockSide(next: DockSide) {
        if (dockSide == next) {
            return
        }
        dockSide = next
        overlayStore.dispatch(
            OverlayEvent.System.DockSideChanged(
                dockSide = next.toCoreDockSide(),
            ),
        )
    }

    private fun handleBackgroundTouch(event: MotionEvent) {
        overlayStore.dispatch(
            gestureInterpreter.asMotion(
                source = OverlayEvent.Touch.Source.BACKGROUND,
                event = event,
            ),
        )
        if (windowMode != WindowMode.LARGE || isAnimating) {
            return
        }

        if (isDraggingHandleBar || isTouchOnBottomHandle(event)) {
            handleLargeWindowDrag(event)
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                backgroundDownRawX = event.rawX
                backgroundDownRawY = event.rawY
            }

            MotionEvent.ACTION_UP -> {
                val deltaX = abs(event.rawX - backgroundDownRawX)
                val deltaY = abs(event.rawY - backgroundDownRawY)
                if (deltaX < TOUCH_SLOP_PX && deltaY < TOUCH_SLOP_PX) {
                    val target = resolveMiniTargetPosition()
                    animateLargeToMini(target.first, target.second)
                }
            }
        }
    }

    private fun handleContentTouch(event: MotionEvent) {
        overlayStore.dispatch(
            gestureInterpreter.asMotion(
                source = OverlayEvent.Touch.Source.CONTENT,
                event = event,
            ),
        )
        if (windowMode != WindowMode.LARGE || isAnimating) {
            return
        }

        if (!touchPassthrough) {
            injectTouchToVirtualDisplay(event)
        }
    }

    private fun injectTouchToVirtualDisplay(event: MotionEvent) {
        val display = virtualDisplay?.display ?: return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = SystemClock.uptimeMillis()
                displayTouchInjector.inject(
                    displayId = display.displayId,
                    event = event,
                    maxWidth = largeWindowWidth,
                    maxHeight = largeWindowHeight,
                    downTime = touchDownTime,
                )
            }

            MotionEvent.ACTION_MOVE -> {
                displayTouchInjector.inject(
                    displayId = display.displayId,
                    event = event,
                    maxWidth = largeWindowWidth,
                    maxHeight = largeWindowHeight,
                    downTime = touchDownTime,
                )
            }

            MotionEvent.ACTION_UP -> {
                displayTouchInjector.inject(
                    displayId = display.displayId,
                    event = event,
                    maxWidth = largeWindowWidth,
                    maxHeight = largeWindowHeight,
                    downTime = touchDownTime,
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                displayTouchInjector.inject(
                    displayId = display.displayId,
                    event = event,
                    maxWidth = largeWindowWidth,
                    maxHeight = largeWindowHeight,
                    downTime = touchDownTime,
                )
            }
        }
    }

    private fun isTouchOnBottomHandle(event: MotionEvent): Boolean {
        val handleBar = bottomHandleBar ?: return false
        if (handleBar.visibility != View.VISIBLE) {
            return false
        }

        val location = IntArray(2)
        handleBar.getLocationOnScreen(location)

        val padding = dpToPx(HANDLE_TOUCH_PADDING_DP).toFloat()
        val left = location[0] - padding
        val right = location[0] + handleBar.width + padding
        val top = location[1] - padding
        val bottom = location[1] + handleBar.height + padding

        return event.rawX in left..right && event.rawY in top..bottom
    }

    private fun handleLargeWindowDrag(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.rawY
                dragDistance = 0f
                previewDragDistance = 0f
                isDraggingHandleBar = true
                isHandleDragDetected = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingHandleBar) return
                dragDistance = (dragStartY - event.rawY).coerceAtLeast(0f)
                if (!isHandleDragDetected && dragDistance > TOUCH_SLOP_PX) {
                    isHandleDragDetected = true
                    overlayStore.dispatch(
                        OverlayEvent.System.TouchSubStateChanged(
                            subState = OverlayTouchSubState.HANDLE_DRAGGING,
                        ),
                    )
                }
                if (isHandleDragDetected) {
                    previewDragDistance = if (previewDragDistance == 0f) {
                        dragDistance
                    } else {
                        previewDragDistance + (dragDistance - previewDragDistance) * DRAG_SMOOTHING_ALPHA
                    }
                    updateLargeDragPreview(previewDragDistance)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isHandleDragDetected && dragDistance >= miniTriggerDistance()) {
                    val target = resolveMiniTargetPosition()
                    animateLargeToMini(target.first, target.second)
                } else {
                    animateBackToLargeFromCurrent()
                }
                isDraggingHandleBar = false
                isHandleDragDetected = false
                dragDistance = 0f
                previewDragDistance = 0f
                overlayStore.dispatch(
                    OverlayEvent.System.TouchSubStateChanged(
                        subState = OverlayTouchSubState.IDLE,
                    ),
                )
            }
        }
    }

    private fun updateLargeDragPreview(distance: Float) {
        val trigger = miniTriggerDistance()
        val progress = (distance / trigger).coerceIn(0f, 1f)
        val dampedProgress = applyDragDamping(progress)
        val overshoot = (distance - trigger).coerceAtLeast(0f) * DRAG_OVERSHOOT_RESISTANCE
        val effectiveDistance = trigger * dampedProgress + overshoot

        val scale = lerpFloat(1f, miniScale, dampedProgress)
        val scaledWidth = largeWindowWidth * scale
        val scaledHeight = largeWindowHeight * scale

        val x = (largeWindowX + (largeWindowWidth - scaledWidth) / 2f).roundToInt()
        val startBottom = largeWindowY + largeWindowHeight.toFloat()
        val y = (startBottom - effectiveDistance - scaledHeight).roundToInt()
        val clip = interpolateRect(fullClipRect(), miniClipRect, dampedProgress)

        applyContentWindowFrame(x, y, scale, clip)
    }

    private fun applyDragDamping(progress: Float): Float {
        return 1f - (1f - progress).pow(DRAG_DAMPING_POWER)
    }

    private fun handleMiniTouch(event: MotionEvent) {
        when (windowMode) {
            WindowMode.MINI -> {
                overlayStore.dispatch(
                    gestureInterpreter.asMotion(
                        source = OverlayEvent.Touch.Source.MINI,
                        event = event,
                    ),
                )
                handleMiniWindowTouch(event)
            }

            WindowMode.MINI_MASKED -> {
                overlayStore.dispatch(
                    gestureInterpreter.asMotion(
                        source = OverlayEvent.Touch.Source.MASKED_MINI,
                        event = event,
                    ),
                )
                handleMaskedMiniTouch(event)
            }

            WindowMode.LARGE -> Unit
        }
    }

    private fun handleMiniWindowTouch(event: MotionEvent) {
        val params = miniTouchParams ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                miniDownRawX = event.rawX
                miniDownRawY = event.rawY
                miniStartX = params.x
                miniStartY = params.y
                isMiniDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - miniDownRawX
                val deltaY = event.rawY - miniDownRawY
                if (!isMiniDragging && (abs(deltaX) > TOUCH_SLOP_PX || abs(deltaY) > TOUCH_SLOP_PX)) {
                    isMiniDragging = true
                    overlayStore.dispatch(
                        OverlayEvent.System.TouchSubStateChanged(
                            subState = OverlayTouchSubState.MINI_DRAGGING,
                        ),
                    )
                }
                if (!isMiniDragging) {
                    return
                }

                val nextX = (miniStartX + deltaX).roundToInt().coerceIn(
                    -miniSize / 2,
                    screenWidth - miniSize / 2
                )
                val nextY = clampMiniY((miniStartY + deltaY).roundToInt())
                applyMiniFrame(nextX, nextY)
                applyContentWindowFrame(nextX, nextY, miniScale, miniClipRect)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isMiniDragging) {
                    animateMiniToLarge()
                    return
                }

                val currentX = params.x
                val currentY = clampMiniY(params.y)
                val maskThreshold = miniMaskTriggerDistance()
                when {
                    currentX <= -maskThreshold -> {
                        updateDockSide(DockSide.LEFT)
                        animateMiniToMasked(currentY)
                    }

                    currentX >= (screenWidth - miniSize + maskThreshold) -> {
                        updateDockSide(DockSide.RIGHT)
                        animateMiniToMasked(currentY)
                    }

                    else -> {
                        updateDockSide(resolveDockSide(currentX))
                        val snapX = if (dockSide == DockSide.LEFT) 0 else screenWidth - miniSize
                        animateMiniToDocked(snapX, currentY)
                    }
                }
                overlayStore.dispatch(
                    OverlayEvent.System.TouchSubStateChanged(
                        subState = OverlayTouchSubState.IDLE,
                    ),
                )
            }
        }
    }

    private fun handleMaskedMiniTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                miniDownRawX = event.rawX
                miniDownRawY = event.rawY
                isMiniDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - miniDownRawX
                val deltaY = event.rawY - miniDownRawY
                if (!isMiniDragging && (abs(deltaX) > TOUCH_SLOP_PX || abs(deltaY) > TOUCH_SLOP_PX)) {
                    isMiniDragging = true
                    overlayStore.dispatch(
                        OverlayEvent.System.TouchSubStateChanged(
                            subState = OverlayTouchSubState.MASKED_MINI_DRAGGING,
                        ),
                    )
                }
                if (!isMiniDragging) {
                    return
                }

                val params = miniTouchParams ?: return
                val anchoredX = if (dockSide == DockSide.LEFT) {
                    -miniSize / 2
                } else {
                    screenWidth - miniSize / 2
                }
                val nextY = clampMiniY((params.y + deltaY).roundToInt())
                miniDownRawY = event.rawY
                applyMiniFrame(anchoredX, nextY)
                applyContentWindowFrame(anchoredX, nextY, miniScale, miniClipRect)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isMiniDragging) {
                    animateMaskedToMini()
                    return
                }

                val deltaX = event.rawX - miniDownRawX
                val inwardRestore = if (dockSide == DockSide.LEFT) {
                    deltaX > miniSize * MASK_INWARD_RESTORE_RATIO
                } else {
                    deltaX < -miniSize * MASK_INWARD_RESTORE_RATIO
                }

                if (inwardRestore) {
                    animateMaskedToMini()
                } else {
                    val currentY = clampMiniY(miniTouchParams?.y ?: 0)
                    animateMaskedStay(currentY)
                }
                overlayStore.dispatch(
                    OverlayEvent.System.TouchSubStateChanged(
                        subState = OverlayTouchSubState.IDLE,
                    ),
                )
            }
        }
    }

    private fun resolveDockSide(currentX: Int): DockSide {
        val center = currentX + miniSize / 2
        return if (center < screenWidth / 2) DockSide.LEFT else DockSide.RIGHT
    }

    private fun resolveMiniTargetPosition(): Pair<Int, Int> {
        if (!hasMiniPosition) {
            val firstX = screenWidth - miniSize
            val firstY = clampMiniY((screenHeight * FIRST_MINI_Y_RATIO).roundToInt())
            updateDockSide(DockSide.RIGHT)
            return firstX to firstY
        }

        val x = if (lastMiniVisibleX <= 0) {
            updateDockSide(DockSide.LEFT)
            0
        } else {
            updateDockSide(DockSide.RIGHT)
            screenWidth - miniSize
        }
        return x to clampMiniY(lastMiniVisibleY)
    }

    private fun rememberMiniVisiblePosition(x: Int, y: Int) {
        val snappedX = if (x <= screenWidth / 2) 0 else screenWidth - miniSize
        lastMiniVisibleX = snappedX
        lastMiniVisibleY = clampMiniY(y)
        hasMiniPosition = true
        updateDockSide(if (snappedX == 0) DockSide.LEFT else DockSide.RIGHT)
    }

    private fun animateLargeToMini(targetX: Int, targetY: Int) {
        val clampedY = clampMiniY(targetY)
        animateContentWindow(
            targetX = targetX,
            targetY = clampedY,
            targetScale = miniScale,
            targetClip = miniClipRect,
            durationMs = SIZE_SWITCH_DURATION,
            onStart = {
                setContentTouchable(false)
                setBackgroundTouchable(false)
                setMiniTouchable(false)
                miniTouchLayer?.visibility = View.GONE
                bottomHandleBar?.visibility = View.GONE
                clearMaskedVisuals()
            },
            onEnd = {
                updateWindowMode(WindowMode.MINI)
                rememberMiniVisiblePosition(targetX, clampedY)
                applyMiniFrame(targetX, clampedY)
                setMiniVisualState(masked = false)
            }
        )
    }

    private fun animateMiniToLarge() {
        animateContentWindow(
            targetX = largeWindowX,
            targetY = largeWindowY,
            targetScale = 1f,
            targetClip = null,
            durationMs = SIZE_SWITCH_DURATION,
            onStart = {
                setMiniTouchable(false)
                miniTouchLayer?.visibility = View.GONE
                clearMaskedVisuals()
                setBackgroundTouchable(true)
            },
            onEnd = {
                updateWindowMode(WindowMode.LARGE)
                applyLargeVisualState(immediate = false)
            }
        )
    }

    private fun animateBackToLargeFromCurrent() {
        animateContentWindow(
            targetX = largeWindowX,
            targetY = largeWindowY,
            targetScale = 1f,
            targetClip = null,
            durationMs = SNAP_DURATION,
            onStart = {
                setContentTouchable(false)
                setBackgroundTouchable(true)
                setMiniTouchable(false)
                miniTouchLayer?.visibility = View.GONE
                clearMaskedVisuals()
            },
            onEnd = {
                updateWindowMode(WindowMode.LARGE)
                applyLargeVisualState(immediate = false)
            }
        )
    }

    private fun animateMiniToDocked(targetX: Int, targetY: Int) {
        val clampedY = clampMiniY(targetY)
        animateMiniPair(
            targetX = targetX,
            targetY = clampedY,
            durationMs = SNAP_DURATION,
            onStart = {
                updateWindowMode(WindowMode.MINI)
                setMiniVisualState(masked = false)
            },
            onEnd = {
                rememberMiniVisiblePosition(targetX, clampedY)
                updateWindowMode(WindowMode.MINI)
                setMiniVisualState(masked = false)
            }
        )
    }

    private fun animateMiniToMasked(targetY: Int) {
        val clampedY = clampMiniY(targetY)
        val targetX = if (dockSide == DockSide.LEFT) {
            -miniSize / 2
        } else {
            screenWidth - miniSize / 2
        }

        val visibleX = if (dockSide == DockSide.LEFT) 0 else screenWidth - miniSize
        rememberMiniVisiblePosition(visibleX, clampedY)

        animateMiniPair(
            targetX = targetX,
            targetY = clampedY,
            durationMs = MASK_DURATION,
            onStart = {
                updateWindowMode(WindowMode.MINI)
                setMiniVisualState(masked = false)
            },
            onEnd = {
                updateWindowMode(WindowMode.MINI_MASKED)
                setMiniVisualState(masked = true)
            }
        )
    }

    private fun animateMaskedToMini() {
        val targetX = if (dockSide == DockSide.LEFT) 0 else screenWidth - miniSize
        val targetY = clampMiniY(miniTouchParams?.y ?: resolveMiniTargetPosition().second)

        animateMiniPair(
            targetX = targetX,
            targetY = targetY,
            durationMs = MASK_DURATION,
            onStart = {
                updateWindowMode(WindowMode.MINI)
                setMiniVisualState(masked = false)
            },
            onEnd = {
                updateWindowMode(WindowMode.MINI)
                rememberMiniVisiblePosition(targetX, targetY)
                setMiniVisualState(masked = false)
            }
        )
    }

    private fun animateMaskedStay(targetY: Int) {
        val clampedY = clampMiniY(targetY)
        val anchoredX = if (dockSide == DockSide.LEFT) {
            -miniSize / 2
        } else {
            screenWidth - miniSize / 2
        }

        animateMiniPair(
            targetX = anchoredX,
            targetY = clampedY,
            durationMs = SNAP_DURATION,
            onStart = {
                updateWindowMode(WindowMode.MINI_MASKED)
                setMiniVisualState(masked = true)
            },
            onEnd = {
                updateWindowMode(WindowMode.MINI_MASKED)
                setMiniVisualState(masked = true)
            }
        )
    }

    private fun animateMiniPair(
        targetX: Int,
        targetY: Int,
        durationMs: Long,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) {
        val startMini = miniTouchParams ?: return
        val startContent = contentParams ?: return
        val contentRoot = contentLayer ?: return
        val startX = startMini.x
        val startY = startMini.y
        val startContentX = contentXToVisualX(startContent.x, contentRoot.scaleX)
        val startContentY = contentYToVisualY(startContent.y, contentRoot.scaleY)

        animateCore(
            durationMs = durationMs,
            onStart = {
                onStart()
                setContentTouchable(false)
                setBackgroundTouchable(false)
                setMiniTouchable(true)
                miniTouchLayer?.visibility = View.VISIBLE
            },
            onFrame = { progress ->
                val currentX = lerpInt(startX, targetX, progress)
                val currentY = lerpInt(startY, targetY, progress)

                applyMiniFrame(currentX, currentY)
                applyContentWindowFrame(
                    x = lerpInt(startContentX, targetX, progress),
                    y = lerpInt(startContentY, targetY, progress),
                    scale = miniScale,
                    clip = miniClipRect
                )
            },
            onEnd = {
                applyMiniFrame(targetX, targetY)
                applyContentWindowFrame(targetX, targetY, miniScale, miniClipRect)
                onEnd()
            }
        )
    }

    private fun animateContentWindow(
        targetX: Int,
        targetY: Int,
        targetScale: Float,
        targetClip: Rect?,
        durationMs: Long,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) {
        val params = contentParams ?: return
        val root = contentLayer ?: return

        val startScale = root.scaleX
        val startX = contentXToVisualX(params.x, startScale)
        val startY = contentYToVisualY(params.y, startScale)

        val fullClip = fullClipRect()
        val startClip = Rect(root.clipBounds ?: fullClip)
        val endClip = Rect(targetClip ?: fullClip)

        animateCore(
            durationMs = durationMs,
            onStart = onStart,
            onFrame = { progress ->
                val currentX = lerpInt(startX, targetX, progress)
                val currentY = lerpInt(startY, targetY, progress)
                val currentScale = lerpFloat(startScale, targetScale, progress)
                val currentClip = interpolateRect(startClip, endClip, progress)
                applyContentWindowFrame(currentX, currentY, currentScale, currentClip)
            },
            onEnd = {
                applyContentWindowFrame(targetX, targetY, targetScale, targetClip)
                onEnd()
            }
        )
    }

    private fun animateCore(
        durationMs: Long,
        onStart: () -> Unit,
        onFrame: (Float) -> Unit,
        onEnd: () -> Unit
    ) {
        overlayAnimator.start(
            durationMs = durationMs,
            onStart = {
                isAnimating = true
                overlayStore.dispatch(
                    OverlayEvent.System.ActiveTransitionChanged(
                        transition = "duration_${durationMs}",
                    ),
                )
                onStart()
            },
            onFrame = onFrame,
            onEnd = {
                isAnimating = false
                overlayStore.dispatch(OverlayEvent.System.ActiveTransitionChanged(transition = null))
                onEnd()
            },
            onCancel = {
                isAnimating = false
                overlayStore.dispatch(OverlayEvent.System.ActiveTransitionChanged(transition = null))
            },
        )
    }

    private fun applyLargeVisualState(immediate: Boolean) {
        updateWindowMode(WindowMode.LARGE)

        backgroundLayer?.visibility = View.VISIBLE
        clearMaskedVisuals()
        bottomHandleBar?.visibility = View.VISIBLE

        contentContainer?.setBackgroundColor(0xFF000000.toInt())
        contentLayer?.clipToOutline = true
        contentLayer?.invalidateOutline()

        setBackgroundTouchable(true)
        setContentTouchable(true)
        setMiniTouchable(false)
        miniTouchLayer?.visibility = View.GONE

        applyTextureTransformForState()
        updateBottomHandlePosition(
            x = largeWindowX,
            y = largeWindowY,
            scale = contentLayer?.scaleX ?: 1f
        )

        if (immediate) {
            applyContentWindowFrame(largeWindowX, largeWindowY, 1f, null)
        }
    }

    private fun setMiniVisualState(masked: Boolean) {
        backgroundLayer?.visibility = View.GONE
        bottomHandleBar?.visibility = View.GONE

        contentContainer?.setBackgroundResource(R.drawable.rounded_overlay_bg)
        contentLayer?.clipToOutline = true
        contentLayer?.invalidateOutline()

        setBackgroundTouchable(false)
        setContentTouchable(false)
        setMiniTouchable(true)
        miniTouchLayer?.visibility = View.VISIBLE

        applyTextureTransformForState()

        if (masked) {
            contentLayer?.setRenderEffect(
                RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP)
            )
            miniMaskView?.visibility = View.VISIBLE
            miniHandleView?.visibility = View.VISIBLE
            updateMiniHandlePosition()
        } else {
            clearMaskedVisuals()
        }
    }

    private fun clearMaskedVisuals() {
        contentLayer?.setRenderEffect(null)
        miniMaskView?.visibility = View.GONE
        miniHandleView?.visibility = View.GONE
    }

    private fun createMiniMaskDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x33000000)
            cornerRadius = miniCornerRadiusPx.toFloat()
        }
    }

    private fun updateMiniHandlePosition() {
        val handle = miniHandleView ?: return
        val lp = handle.layoutParams as? FrameLayout.LayoutParams ?: return

        val visibleCenterRatio = if (dockSide == DockSide.LEFT) 0.75f else 0.25f
        val handleLeft = (miniSize * visibleCenterRatio - lp.width / 2f).roundToInt()
            .coerceIn(0, miniSize - lp.width)

        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = handleLeft
        lp.topMargin = ((miniHeight - lp.height) / 2f).roundToInt().coerceAtLeast(0)
        handle.layoutParams = lp
    }

    private fun applyTextureTransformForState() {
        val texture = textureView ?: return
        if (texture.width == 0 || texture.height == 0) {
            return
        }
        texture.setTransform(null)
    }

    private fun applyMiniFrame(x: Int, y: Int) {
        val params = miniTouchParams ?: return
        params.x = x
        params.y = y
        updateViewLayoutSafely(miniTouchLayer, params)
    }

    private fun updateBottomHandlePosition(x: Int, y: Int, scale: Float) {
        val handle = bottomHandleBar ?: return
        val lp = handle.layoutParams as? FrameLayout.LayoutParams ?: return

        val scaledWidth = largeWindowWidth * scale
        val scaledHeight = largeWindowHeight * scale
        val handleX = (x + (scaledWidth - handleBarWidthPx) / 2f).roundToInt()
        val handleY = (y + scaledHeight + handleBarMarginPx).roundToInt()

        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = handleX
        lp.topMargin = handleY
        handle.layoutParams = lp
    }

    private fun applyContentWindowFrame(x: Int, y: Int, scale: Float, clip: Rect?) {
        val params = contentParams ?: return
        val root = contentLayer ?: return

        params.x = visualXToContentX(x, scale)
        params.y = visualYToContentY(y, scale)
        root.scaleX = scale
        root.scaleY = scale
        root.clipBounds = clip?.let { Rect(it) }

        if (bottomHandleBar?.visibility == View.VISIBLE) {
            updateBottomHandlePosition(x, y, scale)
        }

        updateViewLayoutSafely(root, params)
    }

    private fun visualXToContentX(visualX: Int, scale: Float): Int {
        return (visualX - largeWindowWidth * (1f - scale)).roundToInt()
    }

    private fun visualYToContentY(visualY: Int, scale: Float): Int {
        return (visualY - largeWindowHeight * (1f - scale)).roundToInt()
    }

    private fun contentXToVisualX(contentX: Int, scale: Float): Int {
        return (contentX + largeWindowWidth * (1f - scale)).roundToInt()
    }

    private fun contentYToVisualY(contentY: Int, scale: Float): Int {
        return (contentY + largeWindowHeight * (1f - scale)).roundToInt()
    }

    private fun setBackgroundTouchable(touchable: Boolean) {
        setWindowTouchable(backgroundLayer, backgroundParams, touchable)
    }

    private fun setContentTouchable(touchable: Boolean) {
        setWindowTouchable(contentLayer, contentParams, touchable)
    }

    private fun setMiniTouchable(touchable: Boolean) {
        setWindowTouchable(miniTouchLayer, miniTouchParams, touchable)
    }

    private fun setWindowTouchable(
        view: View?,
        params: WindowManager.LayoutParams?,
        touchable: Boolean
    ) {
        windowHost.setTouchable(view, params, touchable)
    }

    private fun updateViewLayoutSafely(view: View?, params: WindowManager.LayoutParams?) {
        windowHost.update(view, params)
    }

    private fun removeViewImmediateSafely(view: View?) {
        windowHost.remove(view)
    }

    private fun toggleWindowSize() {
        if (isAnimating) {
            return
        }

        when (windowMode) {
            WindowMode.LARGE -> {
                val target = resolveMiniTargetPosition()
                animateLargeToMini(target.first, target.second)
            }

            WindowMode.MINI, WindowMode.MINI_MASKED -> animateMiniToLarge()
        }
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "AutoTask Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AutoTask")
            .setContentText("Overlay running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun fullClipRect(): Rect {
        return Rect(0, 0, largeWindowWidth, largeWindowHeight)
    }

    private fun miniTriggerDistance(): Float {
        return screenHeight * MINI_TRIGGER_DISTANCE_RATIO
    }

    private fun miniMaskTriggerDistance(): Int {
        return (miniSize * MINI_MASK_TRIGGER_RATIO).roundToInt()
    }

    private fun clampMiniY(value: Int): Int {
        val minY = topInset
        val maxY = (screenHeight - bottomInset - miniHeight).coerceAtLeast(minY)
        return value.coerceIn(minY, maxY)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun hasInternalSystemWindowPermission(): Boolean {
        return checkSelfPermission(PERMISSION_INTERNAL_SYSTEM_WINDOW) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun lerpInt(start: Int, end: Int, progress: Float): Int {
        return (start + (end - start) * progress).roundToInt()
    }

    private fun lerpFloat(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress
    }

    private fun interpolateRect(start: Rect, end: Rect, progress: Float): Rect {
        return Rect(
            lerpInt(start.left, end.left, progress),
            lerpInt(start.top, end.top, progress),
            lerpInt(start.right, end.right, progress),
            lerpInt(start.bottom, end.bottom, progress)
        )
    }

    private enum class WindowMode {
        LARGE,
        MINI,
        MINI_MASKED
    }

    private enum class DockSide {
        LEFT,
        RIGHT
    }

    private fun WindowMode.toCoreMode(): OverlayMode {
        return when (this) {
            WindowMode.LARGE -> OverlayMode.LARGE
            WindowMode.MINI -> OverlayMode.MINI
            WindowMode.MINI_MASKED -> OverlayMode.MINI_MASKED
        }
    }

    private fun DockSide.toCoreDockSide(): OverlayDockSide {
        return when (this) {
            DockSide.LEFT -> OverlayDockSide.LEFT
            DockSide.RIGHT -> OverlayDockSide.RIGHT
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "autotask_overlay"
        private const val PERMISSION_INTERNAL_SYSTEM_WINDOW = "android.permission.INTERNAL_SYSTEM_WINDOW"

        // 虚拟显示的宽高比（9:16）
        private const val VIRTUAL_ASPECT = 9f / 16f
        // 大窗口宽度占屏幕的比例
        private const val LARGE_WIDTH_RATIO = 0.70f
        // 大窗口最大高度占屏幕的比例
        private const val MAX_LARGE_HEIGHT_RATIO = 0.80f
        // 大窗口圆角半径（dp）
        private const val LARGE_CORNER_RADIUS_DP = 24f

        // 小窗口宽度占屏幕的比例
        private const val MINI_WIDTH_RATIO = 0.10f
        // 小窗口最小尺寸（dp）
        private const val MINI_MIN_SIZE_DP = 80f
        // 小窗口圆角半径（dp）
        private const val MINI_CORNER_RADIUS_DP = 24f

        // 触发小窗口的拖拽距离比例
        private const val MINI_TRIGGER_DISTANCE_RATIO = 0.2f
        // 触发遮罩模式的边缘距离比例
        private const val MINI_MASK_TRIGGER_RATIO = 0.15f
        // 遮罩模式向内恢复的触发比例
        private const val MASK_INWARD_RESTORE_RATIO = 0.12f

        // 拖拽阻尼幂次
        private const val DRAG_DAMPING_POWER = 1.8f
        // 拖拽超出阻力系数
        private const val DRAG_OVERSHOOT_RESISTANCE = 0.20f
        // 拖拽平滑系数
        private const val DRAG_SMOOTHING_ALPHA = 0.35f

        // 首次小窗口Y坐标比例
        private const val FIRST_MINI_Y_RATIO = 0.35f

        // 窗口大小切换动画持续时间（毫秒）
        private const val SIZE_SWITCH_DURATION = 380L
        // 快速吸附动画持续时间（毫秒）
        private const val SNAP_DURATION = 260L
        // 遮罩模式切换动画持续时间（毫秒）
        private const val MASK_DURATION = 260L

        // 触摸 slop 阈值（像素）
        private const val TOUCH_SLOP_PX = 10f

        // 底部手柄条高度（dp）
        private const val HANDLE_BAR_HEIGHT_DP = 4f
        // 底部手柄条边距（dp）
        private const val HANDLE_BAR_MARGIN_DP = 6f
        // 底部手柄条宽度（dp）
        private const val HANDLE_BAR_WIDTH_DP = 50f
        // 手柄触摸扩展区域（dp）
        private const val HANDLE_TOUCH_PADDING_DP = 20f

        // 小窗口手柄宽度（dp）
        private const val MINI_HANDLE_WIDTH_DP = 4f
        // 小窗口手柄高度（dp）
        private const val MINI_HANDLE_HEIGHT_DP = 36f

        // 遮罩模糊半径（像素）
        private const val BLUR_RADIUS_PX = 16f
    }
}
