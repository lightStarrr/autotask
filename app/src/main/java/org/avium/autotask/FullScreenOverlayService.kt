package org.avium.autotask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.net.Uri
import android.content.pm.ServiceInfo
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

import org.avium.autotask.IntentKeys.EXTRA_PACKAGE_NAME
import org.avium.autotask.IntentKeys.EXTRA_QUESTION
import org.avium.autotask.IntentKeys.EXTRA_ACTIVITY_NAME
import org.avium.autotask.IntentKeys.EXTRA_TOUCH_PASSTHROUGH
import org.avium.autotask.util.InputInjector

class FullScreenOverlayService : Service() {
    private val tag = "FullScreenOverlayService"

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var inputManager: InputManager

    private var overlayRoot: FrameLayout? = null
    private var contentContainer: FrameLayout? = null
    private var textureView: TextureView? = null
    private var bottomHandleBar: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var touchPassthrough = false
    private var touchDownTime = 0L

    // 用于拖动的变量
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var isDraggingHandleBar = false // 标记是否正在拖动大窗横条
    private var dragStartY = 0f
    private var dragDistance = 0f

    // 窗口大小状态
    private var isMinimized = false
    private var largeWindowWidth = 0
    private var largeWindowHeight = 0
    private var screenHeight = 0
    private var isAnimating = false

    private var question: String? = null
    private var targetPackage: String = DEFAULT_TARGET_PACKAGE
    private var targetActivity: String? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
            ACTION_TOGGLE_SIZE -> {
                toggleWindowSize()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            overlayRoot?.let { windowManager.removeViewImmediate(it) }
        } catch (e: Exception) {
            Log.w(tag, "Failed to remove overlay", e)
        }
        overlayRoot = null
        contentContainer = null
        textureView = null
        bottomHandleBar = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureOverlay() {
        if (overlayRoot != null) {
            launchTargetOnDisplay()
            return
        }

        // 获取屏幕尺寸并计算窗口大小（屏幕的 70%）
        val displayMetrics = resources.displayMetrics
        screenHeight = displayMetrics.heightPixels
        largeWindowWidth = (displayMetrics.widthPixels * 0.7).toInt()
        largeWindowHeight = (displayMetrics.heightPixels * 0.7).toInt()

        val handleBarHeight = (4 * displayMetrics.density).toInt()
        val handleBarMargin = (16 * displayMetrics.density).toInt()
        val extraSpace = handleBarHeight + handleBarMargin * 2

        // 窗口大小固定为大窗尺寸，通过缩放实现大小变化
        val initialWidth = largeWindowWidth
        val initialHeight = largeWindowHeight + extraSpace

        overlayParams = OverlayEffect.buildLayoutParams(touchPassthrough, initialWidth, initialHeight)
        overlayRoot = FrameLayout(this).apply {
            setBackgroundColor(0x00000000) // 透明背景
            // 设置缩放中心点在中心
            pivotX = (initialWidth / 2).toFloat()
            pivotY = (initialHeight / 2).toFloat()
            // 设置初始缩放状态
            val scale = if (isMinimized) MINIMIZED_SIZE.toFloat() / largeWindowWidth.toFloat() else 1f
            scaleX = scale
            scaleY = scale
            setOnTouchListener { view, event ->
                handleTouch(event)
                true // 消费事件
            }
        }

        // 内容容器（黑色背景）
        contentContainer = FrameLayout(this).apply {
            if (isMinimized) {
                setBackgroundResource(R.drawable.rounded_overlay_bg)
                clipToOutline = true
            } else {
                setBackgroundColor(0xFF000000.toInt())
            }
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
                    val surface = Surface(surfaceTexture)
                    attachVirtualDisplay(surface, width, height)
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
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

        // 将内容容器添加到根布局（始终保持虚拟显示器的分辨率）
        val contentParams = FrameLayout.LayoutParams(
            largeWindowWidth,
            largeWindowHeight
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        }
        overlayRoot?.addView(contentContainer, contentParams)

        // 添加底部横条（在大窗模式下显示，位于内容容器下方）
        if (!isMinimized) {
            bottomHandleBar = View(this).apply {
                setBackgroundResource(R.drawable.bottom_handle_bar)
                visibility = View.VISIBLE
            }
            val handleWidth = (50 * displayMetrics.density).toInt()

            val handleParams = FrameLayout.LayoutParams(handleWidth, handleBarHeight).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = handleBarMargin
            }
            overlayRoot?.addView(bottomHandleBar, handleParams)
        }

        windowManager.addView(overlayRoot, overlayParams)
    }

    private fun attachVirtualDisplay(surface: Surface, width: Int, height: Int) {
        val densityDpi = resources.displayMetrics.densityDpi
        if (virtualDisplay == null) {
            // 使用 OWN_CONTENT_ONLY 不需要镜像权限
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

        for (intent in candidates) {
            question?.let { intent.putExtra(EXTRA_QUESTION, it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent, options.toBundle())
                return
            } catch (e: Exception) {
                Log.e(tag, "Failed to start target activity with $intent", e)
            }
        }
    }

    private fun resolveLaunchIntent(): Intent? {
        Log.d(tag, "resolveLaunchIntent: targetPackage=$targetPackage")

        // 首先检查包是否存在
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
        Log.d(tag, "getLaunchIntentForPackage returned null")

        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(queryIntent, 0)
        Log.d(tag, "Query found ${resolveInfos.size} launcher activities")

        val match = resolveInfos.firstOrNull { it.activityInfo.packageName == targetPackage }
        if (match == null) {
            Log.w(tag, "No launcher activity found for $targetPackage")

            // 列出该包的所有 activities 用于调试
            try {
                val packageInfo = packageManager.getPackageInfo(
                    targetPackage,
                    android.content.pm.PackageManager.GET_ACTIVITIES
                )
                packageInfo.activities?.forEach {
                    Log.d(tag, "  Available activity: ${it.name}")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to list activities", e)
            }
            return null
        }

        val activityName = match.activityInfo.name
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            component = ComponentName(targetPackage, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Log.d(tag, "Resolved launcher activity: $targetPackage/$activityName")
        }
    }

    private fun resolveExplicitIntent(): Intent? {
        val activityName = targetActivity?.takeIf { it.isNotBlank() } ?: return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            component = ComponentName(targetPackage, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(packageManager)
        if (resolved == null) {
            Log.w(tag, "Explicit activity not found: $targetPackage/$activityName")
            return null
        }
        return intent
    }

    private fun resolveDialerFallback(): Intent? {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:")
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = dialIntent.resolveActivity(packageManager) ?: return null
        return dialIntent.setComponent(resolved).also {
            Log.d(tag, "Resolved dial activity: ${resolved.packageName}/${resolved.className}")
        }
    }

    private fun resolveDialerImplicit(): Intent? {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = dialIntent.resolveActivity(packageManager) ?: return null
        return dialIntent.also {
            Log.d(tag, "Resolved implicit dial activity: ${resolved.packageName}/${resolved.className}")
        }
    }

    private fun setTouchPassthroughInternal(enabled: Boolean) {
        touchPassthrough = enabled
        val root = overlayRoot ?: return
        val params = overlayParams ?: return
        val newParams = OverlayEffect.buildLayoutParams(touchPassthrough, params.width, params.height).apply {
            width = params.width
            height = params.height
            x = params.x
            y = params.y
        }
        overlayParams = newParams
        windowManager.updateViewLayout(root, newParams)
    }

    private fun handleTouch(event: MotionEvent) {
        if (isAnimating) return // 动画期间忽略触摸

        if (isMinimized) {
            // 小窗模式：处理拖动和单击切换
            handleMinimizedWindowTouch(event)
        } else {
            // 大窗模式：检查是否正在拖动横条或点击横条
            val handleBar = bottomHandleBar
            if (isDraggingHandleBar || (handleBar != null && isTouchOnHandleBar(event, handleBar))) {
                // 一旦开始拖动横条，后续的 MOVE 事件都由 handleLargeWindowDrag 处理
                handleLargeWindowDrag(event)
            } else {
                // 点击内容区域
                if (!touchPassthrough) {
                    // 注入触摸事件到虚拟显示器
                    handleTouchEventForContent(event)
                }
                // touchPassthrough = true 时不做任何处理（大窗固定不动）
            }
        }
    }

    private fun handleTouchEventForContent(event: MotionEvent) {
        val container = contentContainer ?: return
        val display = virtualDisplay?.display ?: return
        val displayId = display.displayId

        // 获取当前缩放比例
        val scale = container.scaleX

        // 将触摸坐标转换为相对于 contentContainer 的坐标
        val location = IntArray(2)
        container.getLocationOnScreen(location)

        // 计算缩放后的实际显示区域
        val scaledWidth = (container.width * scale).toInt()
        val scaledHeight = (container.height * scale).toInt()
        val scaledLeft = location[0] + (container.width - scaledWidth) / 2
        val scaledTop = location[1] + (container.height - scaledHeight) / 2

        // 转换为相对于缩放后区域的坐标
        val relativeX = event.rawX - scaledLeft
        val relativeY = event.rawY - scaledTop

        // 检查是否在缩放后的显示范围内
        if (relativeX < 0 || relativeX > scaledWidth || relativeY < 0 || relativeY > scaledHeight) {
            return
        }

        // 转换为虚拟显示器的实际坐标（考虑缩放）
        val x = relativeX / scale
        val y = relativeY / scale

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = SystemClock.uptimeMillis()
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    touchDownTime
                )
            }
            MotionEvent.ACTION_MOVE -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    touchDownTime
                )
            }
            MotionEvent.ACTION_UP -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_UP,
                    x,
                    y,
                    touchDownTime
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_CANCEL,
                    x,
                    y,
                    touchDownTime
                )
            }
        }
    }

    private fun isTouchOnHandleBar(event: MotionEvent, handleBar: View): Boolean {
        val location = IntArray(2)
        handleBar.getLocationOnScreen(location)
        val handleLeft = location[0]
        val handleTop = location[1]
        val handleRight = handleLeft + handleBar.width
        val handleBottom = handleTop + handleBar.height

        // 扩大触摸区域，提高可用性
        val touchPadding = (20 * resources.displayMetrics.density).toInt()
        return event.rawX >= handleLeft - touchPadding &&
                event.rawX <= handleRight + touchPadding &&
                event.rawY >= handleTop - touchPadding &&
                event.rawY <= handleBottom + touchPadding
    }

    private fun handleLargeWindowDrag(event: MotionEvent) {
        val params = overlayParams ?: return
        val root = overlayRoot ?: return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.rawY
                dragDistance = 0f
                isDragging = false
                isDraggingHandleBar = true // 标记开始拖动横条
            }
            MotionEvent.ACTION_MOVE -> {
                dragDistance = dragStartY - event.rawY // 向上为正
                if (!isDragging && Math.abs(dragDistance) > 10) {
                    isDragging = true
                }

                if (isDragging && dragDistance > 0) {
                    // 实时更新窗口大小和位置
                    updateWindowDuringDrag(dragDistance)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val threshold = screenHeight * 0.25f
                    if (dragDistance >= threshold) {
                        // 超过阈值，动画缩小为小窗
                        animateToMinimized()
                    } else {
                        // 未超过阈值，动画恢复原状
                        animateToLarge()
                    }
                }
                isDragging = false
                isDraggingHandleBar = false // 清除拖动标志
            }
        }
    }

    private fun updateWindowDuringDrag(distance: Float) {
        val params = overlayParams ?: return
        val root = overlayRoot ?: return

        // 计算缩放比例（从 1.0 到 MINIMIZED_SIZE/largeWindowWidth）
        val maxDistance = screenHeight * 0.25f
        val progress = (distance / maxDistance).coerceIn(0f, 1f)

        val targetScale = MINIMIZED_SIZE.toFloat() / largeWindowWidth.toFloat()
        val scale = 1f - (1f - targetScale) * progress

        // 对整个窗口进行缩放（包括内容和小横条）
        root.scaleX = scale
        root.scaleY = scale

        // 计算窗口位置，使得底部跟随手指
        // 当pivot在中心时，窗口底部Y坐标 = params.y + initialHeight/2 * (1 + scale)
        // 我们希望底部在 initialHeight - distance
        // 所以 params.y = initialHeight/2 * (1 - scale) - distance
        val displayMetrics = resources.displayMetrics
        val handleBarHeight = (4 * displayMetrics.density).toInt()
        val handleBarMargin = (16 * displayMetrics.density).toInt()
        val extraSpace = handleBarHeight + handleBarMargin * 2
        val initialHeight = largeWindowHeight + extraSpace

        params.y = (initialHeight / 2f * (1f - scale) - distance).toInt()

        windowManager.updateViewLayout(root, params)
    }

    private fun handleMinimizedWindowTouch(event: MotionEvent) {
        val params = overlayParams ?: return
        val root = overlayRoot ?: return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - lastTouchX
                val deltaY = event.rawY - lastTouchY

                // 如果移动距离超过阈值，标记为拖动
                if (!isDragging && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                    isDragging = true
                }

                if (isDragging) {
                    // 更新窗口位置
                    params.x += deltaX.toInt()
                    params.y += deltaY.toInt()
                    windowManager.updateViewLayout(root, params)

                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // 单击：切换到大窗模式
                    animateToLarge()
                }
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
    }

    private fun animateToMinimized() {
        val params = overlayParams ?: return
        val root = overlayRoot ?: return
        val container = contentContainer ?: return

        isAnimating = true

        val startY = params.y
        val startX = params.x
        val startScale = root.scaleX

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float

                // 计算目标缩放
                val targetScale = MINIMIZED_SIZE.toFloat() / largeWindowWidth.toFloat()
                val currentScale = startScale + (targetScale - startScale) * progress

                // 计算目标位置（移到屏幕右侧中间偏上）
                val targetX = (screenHeight * 0.3f).toInt()
                val targetY = (screenHeight * 0.3f).toInt()

                val currentX = (startX + (targetX - startX) * progress).toInt()
                val currentY = (startY + (targetY - startY) * progress).toInt()

                // 对整个窗口进行缩放
                root.scaleX = currentScale
                root.scaleY = currentScale

                // 更新窗口位置
                params.x = currentX
                params.y = currentY

                windowManager.updateViewLayout(root, params)
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isMinimized = true
                    container.setBackgroundResource(R.drawable.rounded_overlay_bg)
                    container.clipToOutline = true
                    bottomHandleBar?.visibility = View.GONE
                    isAnimating = false
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    isAnimating = false
                }
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
        }
        animator.start()
    }

    private fun animateToLarge() {
        val params = overlayParams ?: return
        val root = overlayRoot ?: return
        val container = contentContainer ?: return

        isAnimating = true

        val startY = params.y
        val startX = params.x
        val startScale = root.scaleX

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float

                // 计算当前缩放比例（从当前缩放到 1.0）
                val currentScale = startScale + (1f - startScale) * progress

                // 移动到屏幕中心
                val currentX = (startX - startX * progress).toInt()
                val currentY = (startY - startY * progress).toInt()

                // 对整个窗口进行缩放
                root.scaleX = currentScale
                root.scaleY = currentScale

                // 更新窗口位置
                params.x = currentX
                params.y = currentY

                windowManager.updateViewLayout(root, params)
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isMinimized = false
                    container.setBackgroundColor(0xFF000000.toInt())
                    container.clipToOutline = false
                    root.scaleX = 1f
                    root.scaleY = 1f
                    bottomHandleBar?.visibility = View.VISIBLE
                    isAnimating = false
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    isAnimating = false
                }
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
        }
        animator.start()
    }

    private fun toggleWindowSize() {
        if (isMinimized) {
            animateToLarge()
        } else {
            animateToMinimized()
        }
    }

    private fun createNotification(): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "AutoTask Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("AutoTask")
            .setContentText("Overlay running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    companion object {
        private const val ACTION_START = "org.avium.autotask.action.START_OVERLAY"
        private const val ACTION_SET_TOUCH_PASSTHROUGH = "org.avium.autotask.action.SET_TOUCH_PASSTHROUGH"
        private const val ACTION_TOGGLE_SIZE = "org.avium.autotask.action.TOGGLE_SIZE"
        private const val ACTION_STOP = "org.avium.autotask.action.STOP_OVERLAY"

        private const val DEFAULT_TARGET_PACKAGE = "com.android.dialer"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "autotask_overlay"

        private const val MINIMIZED_SIZE = 200 // 小窗大小（像素）

        fun start(context: Context, question: String?, packageName: String?, activityName: String? = null) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_QUESTION, question)
                putExtra(EXTRA_PACKAGE_NAME, packageName ?: DEFAULT_TARGET_PACKAGE)
                putExtra(EXTRA_ACTIVITY_NAME, activityName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun setTouchPassthrough(context: Context, enabled: Boolean) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_SET_TOUCH_PASSTHROUGH
                putExtra(EXTRA_TOUCH_PASSTHROUGH, enabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun toggleWindowSize(context: Context) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_TOGGLE_SIZE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
