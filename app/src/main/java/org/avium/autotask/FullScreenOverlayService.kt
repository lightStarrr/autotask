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
    private var textureView: TextureView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var touchPassthrough = false
    private var touchDownTime = 0L

    // 用于拖动的变量
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

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
        textureView = null
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
        val windowWidth = (displayMetrics.widthPixels * 0.7).toInt()
        val windowHeight = (displayMetrics.heightPixels * 0.7).toInt()

        overlayParams = OverlayEffect.buildLayoutParams(touchPassthrough, windowWidth, windowHeight)
        overlayRoot = FrameLayout(this).apply {
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
            setOnTouchListener { view, event ->
                if (touchPassthrough) {
                    // 拖动模式：允许拖动悬浮窗
                    handleDragEvent(event)
                    true // 消费事件
                } else {
                    // 非穿透模式：注入触摸事件到虚拟显示器
                    handleTouchEvent(event)
                    true // 消费事件
                }
            }
        }

        overlayRoot?.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

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

    private fun handleTouchEvent(event: MotionEvent) {
        val display = virtualDisplay?.display ?: return
        val displayId = display.displayId

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = SystemClock.uptimeMillis()
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_DOWN,
                    event.x,
                    event.y,
                    touchDownTime
                )
            }
            MotionEvent.ACTION_MOVE -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_MOVE,
                    event.x,
                    event.y,
                    touchDownTime
                )
            }
            MotionEvent.ACTION_UP -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_UP,
                    event.x,
                    event.y,
                    touchDownTime
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                InputInjector.injectMotionEvent(
                    inputManager,
                    displayId,
                    MotionEvent.ACTION_CANCEL,
                    event.x,
                    event.y,
                    touchDownTime
                )
            }
        }
    }

    private fun handleDragEvent(event: MotionEvent) {
        val params = overlayParams ?: return
        val root = overlayRoot ?: return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录初始触摸位置
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 计算移动距离
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

                    // 更新上次触摸位置
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
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
        private const val ACTION_STOP = "org.avium.autotask.action.STOP_OVERLAY"

        private const val DEFAULT_TARGET_PACKAGE = "com.android.dialer"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "autotask_overlay"

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

        fun stop(context: Context) {
            val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
