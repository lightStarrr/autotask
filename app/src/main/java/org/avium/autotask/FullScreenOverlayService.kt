package org.avium.autotask

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.net.toUri
import org.avium.autotask.IntentKeys.EXTRA_ACTIVITY_NAME
import org.avium.autotask.IntentKeys.EXTRA_PACKAGE_NAME
import org.avium.autotask.IntentKeys.EXTRA_QUESTION
import org.avium.autotask.IntentKeys.EXTRA_TOUCH_PASSTHROUGH
import org.avium.autotask.util.InputInjector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class FullScreenOverlayService : Service() {
    private val tag = "FullScreenOverlayService"

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var inputManager: InputManager
    private lateinit var activityManager: ActivityManager

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

    private var overlayState = OverlayState.LARGE
    private var dockSide = DockSide.RIGHT
    private var activeAnimator: ValueAnimator? = null
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
    private var targetPackage: String = DEFAULT_TARGET_PACKAGE
    private var targetActivity: String? = null

    private val contentOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (overlayState == OverlayState.LARGE) {
                outline.setRect(0, 0, view.width, view.height)
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

        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                question = intent.getStringExtra(EXTRA_QUESTION)
                targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: DEFAULT_TARGET_PACKAGE
                targetActivity = intent.getStringExtra(EXTRA_ACTIVITY_NAME)
                touchPassthrough = false
                ensureOverlay()
            }

            ACTION_SET_TOUCH_PASSTHROUGH -> {
                val enabled = intent.getBooleanExtra(EXTRA_TOUCH_PASSTHROUGH, false)
                setTouchPassthroughInternal(enabled)
            }

            ACTION_TOGGLE_SIZE -> toggleWindowSize()
            ACTION_STOP -> stopVirtualDisplayAndService()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        activeAnimator?.cancel()
        activeAnimator = null

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureOverlay() {
        if (backgroundLayer != null && contentLayer != null && miniTouchLayer != null) {
            launchTargetOnDisplay()
            return
        }

        calculateGeometry()
        val trustedOverlay = hasInternalSystemWindowPermission()

        backgroundParams = OverlayEffect.buildBackgroundLayoutParams(
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

        contentParams = OverlayEffect.buildContentLayoutParams(
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

        miniTouchParams = OverlayEffect.buildMiniTouchLayoutParams(
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
            setBackgroundColor(0x33000000)
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

        try {
            windowManager.addView(backgroundLayer, backgroundParams)
            windowManager.addView(contentLayer, contentParams)
            windowManager.addView(miniTouchLayer, miniTouchParams)
        } catch (e: Exception) {
            Log.e(tag, "Failed to add overlay views", e)
            return
        }

        applyLargeVisualState(immediate = true)
        launchTargetOnDisplay()
    }

    private fun calculateGeometry() {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val systemBarInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())

        screenWidth = bounds.width()
        screenHeight = bounds.height()
        topInset = systemBarInsets.top
        bottomInset = systemBarInsets.bottom

        var computedLargeWidth = (screenWidth * LARGE_WIDTH_RATIO).roundToInt()
        var computedLargeHeight = (computedLargeWidth / VIRTUAL_ASPECT).roundToInt()

        val maxLargeHeight = (screenHeight * MAX_LARGE_HEIGHT_RATIO).roundToInt()
        if (computedLargeHeight > maxLargeHeight) {
            computedLargeHeight = maxLargeHeight
            computedLargeWidth = (computedLargeHeight * VIRTUAL_ASPECT).roundToInt()
        }

        largeWindowWidth = computedLargeWidth.coerceAtLeast(1)
        largeWindowHeight = computedLargeHeight.coerceAtLeast(1)

        largeWindowX = ((screenWidth - largeWindowWidth) / 2).coerceAtLeast(0)
        largeWindowY = ((screenHeight - largeWindowHeight) / 2).coerceAtLeast(topInset)

        val minMiniPx = dpToPx(MINI_MIN_SIZE_DP)
        miniSize = max((screenWidth * MINI_WIDTH_RATIO).roundToInt(), minMiniPx)
        miniScale = miniSize.toFloat() / largeWindowWidth.toFloat()
        miniHeight = (largeWindowHeight * miniScale).roundToInt().coerceAtLeast(1)

        miniClipRect = Rect(0, 0, largeWindowWidth, largeWindowHeight)
        miniCornerRadiusPx = dpToPx(MINI_CORNER_RADIUS_DP)
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
            launchTargetOnDisplay()
        } else {
            virtualDisplay?.surface = surface
        }
    }

    private fun launchTargetOnDisplay() {
        val display = virtualDisplay?.display ?: return
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
        val candidates = if (targetPackage == DEFAULT_TARGET_PACKAGE) {
            listOfNotNull(resolveDialerImplicit(), resolveDialerFallback())
        } else {
            listOfNotNull(
                resolveExplicitIntent(),
                resolveLaunchIntent(),
                resolveDialerFallback(),
                resolveDialerImplicit()
            )
        }

        if (candidates.isEmpty()) {
            Log.w(tag, "Launch intent not found for $targetPackage")
            return
        }

        for (candidate in candidates) {
            question?.let { candidate.putExtra(EXTRA_QUESTION, it) }
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(candidate, options.toBundle())
                return
            } catch (e: Exception) {
                Log.e(tag, "Failed to start target activity with $candidate", e)
            }
        }
    }

    private fun stopVirtualDisplayAndService() {
        if (targetPackage != DEFAULT_TARGET_PACKAGE) {
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

    private fun resolveLaunchIntent(): Intent? {
        Log.d(tag, "resolveLaunchIntent: targetPackage=$targetPackage")

        try {
            packageManager.getPackageInfo(targetPackage, 0)
            Log.d(tag, "Package $targetPackage exists")
        } catch (e: Exception) {
            Log.w(tag, "Package $targetPackage not found", e)
            return null
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            Log.d(tag, "Found launch intent: ${launchIntent.component}")
            return launchIntent
        }

        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(queryIntent, 0)
        val match = resolveInfos.firstOrNull { it.activityInfo.packageName == targetPackage } ?: return null

        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            component = ComponentName(targetPackage, match.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveExplicitIntent(): Intent? {
        val activityName = targetActivity?.takeIf { it.isNotBlank() } ?: return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            component = ComponentName(targetPackage, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent.takeIf { it.resolveActivity(packageManager) != null }
    }

    private fun resolveDialerFallback(): Intent? {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = "tel:".toUri()
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = dialIntent.resolveActivity(packageManager) ?: return null
        return dialIntent.setComponent(resolved)
    }

    private fun resolveDialerImplicit(): Intent? {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = "tel:".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return dialIntent.takeIf { it.resolveActivity(packageManager) != null }
    }

    private fun setTouchPassthroughInternal(enabled: Boolean) {
        touchPassthrough = enabled
    }

    private fun handleBackgroundTouch(event: MotionEvent) {
        if (overlayState != OverlayState.LARGE || isAnimating) {
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
        if (overlayState != OverlayState.LARGE || isAnimating) {
            return
        }

        if (!touchPassthrough) {
            injectTouchToVirtualDisplay(event)
        }
    }

    private fun injectTouchToVirtualDisplay(event: MotionEvent) {
        val display = virtualDisplay?.display ?: return

        val x = event.x.coerceIn(0f, largeWindowWidth.toFloat())
        val y = event.y.coerceIn(0f, largeWindowHeight.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = SystemClock.uptimeMillis()
                InputInjector.injectMotionEvent(
                    inputManager,
                    display.displayId,
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    touchDownTime
                )
            }

            MotionEvent.ACTION_MOVE -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    display.displayId,
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    touchDownTime
                )
            }

            MotionEvent.ACTION_UP -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    display.displayId,
                    MotionEvent.ACTION_UP,
                    x,
                    y,
                    touchDownTime
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    display.displayId,
                    MotionEvent.ACTION_CANCEL,
                    x,
                    y,
                    touchDownTime
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
        when (overlayState) {
            OverlayState.MINI -> handleMiniWindowTouch(event)
            OverlayState.MINI_MASKED -> handleMaskedMiniTouch(event)
            OverlayState.LARGE -> Unit
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
                        dockSide = DockSide.LEFT
                        animateMiniToMasked(currentY)
                    }

                    currentX >= (screenWidth - miniSize + maskThreshold) -> {
                        dockSide = DockSide.RIGHT
                        animateMiniToMasked(currentY)
                    }

                    else -> {
                        dockSide = resolveDockSide(currentX)
                        val snapX = if (dockSide == DockSide.LEFT) 0 else screenWidth - miniSize
                        animateMiniToDocked(snapX, currentY)
                    }
                }
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
            dockSide = DockSide.RIGHT
            return firstX to firstY
        }

        val x = if (lastMiniVisibleX <= 0) {
            dockSide = DockSide.LEFT
            0
        } else {
            dockSide = DockSide.RIGHT
            screenWidth - miniSize
        }
        return x to clampMiniY(lastMiniVisibleY)
    }

    private fun rememberMiniVisiblePosition(x: Int, y: Int) {
        val snappedX = if (x <= screenWidth / 2) 0 else screenWidth - miniSize
        lastMiniVisibleX = snappedX
        lastMiniVisibleY = clampMiniY(y)
        hasMiniPosition = true
        dockSide = if (snappedX == 0) DockSide.LEFT else DockSide.RIGHT
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
                overlayState = OverlayState.MINI
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
                overlayState = OverlayState.LARGE
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
                overlayState = OverlayState.LARGE
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
                overlayState = OverlayState.MINI
                setMiniVisualState(masked = false)
            },
            onEnd = {
                rememberMiniVisiblePosition(targetX, clampedY)
                overlayState = OverlayState.MINI
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
                overlayState = OverlayState.MINI
                setMiniVisualState(masked = false)
            },
            onEnd = {
                overlayState = OverlayState.MINI_MASKED
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
                overlayState = OverlayState.MINI
                setMiniVisualState(masked = false)
            },
            onEnd = {
                overlayState = OverlayState.MINI
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
                overlayState = OverlayState.MINI_MASKED
                setMiniVisualState(masked = true)
            },
            onEnd = {
                overlayState = OverlayState.MINI_MASKED
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
        activeAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                onFrame(valueAnimator.animatedValue as Float)
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    isAnimating = true
                    onStart()
                }

                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    activeAnimator = null
                    onEnd()
                }

                override fun onAnimationCancel(animation: Animator) {
                    isAnimating = false
                    activeAnimator = null
                }

                override fun onAnimationRepeat(animation: Animator) = Unit
            })
        }

        activeAnimator = animator
        animator.start()
    }

    private fun applyLargeVisualState(immediate: Boolean) {
        overlayState = OverlayState.LARGE

        backgroundLayer?.visibility = View.VISIBLE
        clearMaskedVisuals()
        bottomHandleBar?.visibility = View.VISIBLE

        contentContainer?.setBackgroundColor(0xFF000000.toInt())
        contentLayer?.clipToOutline = false
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

        if (clip == null) {
            root.clipToOutline = false
        }

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
        if (view == null || params == null) {
            return
        }

        val currentlyTouchable = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (currentlyTouchable == touchable) {
            return
        }

        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        updateViewLayoutSafely(view, params)
    }

    private fun updateViewLayoutSafely(view: View?, params: WindowManager.LayoutParams?) {
        if (view == null || params == null) {
            return
        }
        try {
            if (view.windowToken != null) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to update view layout", e)
        }
    }

    private fun removeViewImmediateSafely(view: View?) {
        if (view == null) {
            return
        }
        try {
            if (view.windowToken != null) {
                windowManager.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to remove overlay view", e)
        }
    }

    private fun toggleWindowSize() {
        if (isAnimating) {
            return
        }

        when (overlayState) {
            OverlayState.LARGE -> {
                val target = resolveMiniTargetPosition()
                animateLargeToMini(target.first, target.second)
            }

            OverlayState.MINI, OverlayState.MINI_MASKED -> animateMiniToLarge()
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

    private enum class OverlayState {
        LARGE,
        MINI,
        MINI_MASKED
    }

    private enum class DockSide {
        LEFT,
        RIGHT
    }

    companion object {
        // 启动悬浮窗服务的动作常量
        private const val ACTION_START = "org.avium.autotask.action.START_OVERLAY"
        // 设置触摸穿透的动作常量
        private const val ACTION_SET_TOUCH_PASSTHROUGH = "org.avium.autotask.action.SET_TOUCH_PASSTHROUGH"
        // 切换窗口大小的动作常量
        private const val ACTION_TOGGLE_SIZE = "org.avium.autotask.action.TOGGLE_SIZE"
        // 停止悬浮窗服务的动作常量
        private const val ACTION_STOP = "org.avium.autotask.action.STOP_OVERLAY"

        // 默认目标应用包名（拨号器）
        private const val DEFAULT_TARGET_PACKAGE = "com.android.dialer"

        // 通知ID和频道ID
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "autotask_overlay"
        private const val PERMISSION_INTERNAL_SYSTEM_WINDOW = "android.permission.INTERNAL_SYSTEM_WINDOW"

        // 虚拟显示的宽高比（9:16）
        private const val VIRTUAL_ASPECT = 9f / 16f
        // 大窗口宽度占屏幕的比例
        private const val LARGE_WIDTH_RATIO = 0.70f
        // 大窗口最大高度占屏幕的比例
        private const val MAX_LARGE_HEIGHT_RATIO = 0.80f

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

        fun start(context: Context, question: String?, packageName: String?, activityName: String? = null) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_QUESTION, question)
                putExtra(EXTRA_PACKAGE_NAME, packageName ?: DEFAULT_TARGET_PACKAGE)
                putExtra(EXTRA_ACTIVITY_NAME, activityName)
            }
            context.startForegroundService(intent)
        }

        fun setTouchPassthrough(context: Context, enabled: Boolean) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_SET_TOUCH_PASSTHROUGH
                putExtra(EXTRA_TOUCH_PASSTHROUGH, enabled)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
