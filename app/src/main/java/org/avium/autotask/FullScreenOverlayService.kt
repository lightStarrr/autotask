package org.avium.autotask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avium.autotask.ui.theme.AutoTaskTheme
import org.avium.autotask.util.HiddenApiAccess
import org.avium.autotask.util.InputInjector

class FullScreenOverlayService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val questionState = mutableStateOf<String?>(null)
    private val composeViewTreeOwner = OverlayComposeViewTreeOwner()
    private var isStopping = false
    private val tapPassThroughMutex = Mutex()

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopOverlay("screen_off")
                ACTION_CLOSE_SYSTEM_DIALOGS_COMPAT -> {
                    val reason = intent.getStringExtra(EXTRA_SYSTEM_DIALOG_REASON)
                    if (reason in EXIT_SYSTEM_DIALOG_REASONS) {
                        stopOverlay("system_dialog:$reason")
                    }
                }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        HiddenApiAccess.ensureExemptions()
        composeViewTreeOwner.performCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerSystemEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            stopOverlay("explicit_stop")
            return START_NOT_STICKY
        }

        questionState.value = intent?.getStringExtra(EXTRA_QUESTION)
        ensureOverlayAttached()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterSystemEventReceiver()
        removeOverlay()
        serviceScope.cancel()
        composeViewTreeOwner.performDestroy()
        super.onDestroy()
    }

    private fun ensureOverlayAttached() {
        if (overlayView != null) {
            return
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Disable WM automatic fitting so the overlay can draw into status/navigation bar areas.
            setFitInsetsTypes(0)
            setFitInsetsSides(0)
            setFitInsetsIgnoringVisibility(true)
        }

        val displayMetrics = resources.displayMetrics
        val view = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    stopOverlay("back_key")
                    true
                } else {
                    false
                }
            }
            setViewTreeLifecycleOwner(composeViewTreeOwner)
            setViewTreeSavedStateRegistryOwner(composeViewTreeOwner)
            setViewTreeViewModelStoreOwner(composeViewTreeOwner)
            setContent {
                AutoTaskTheme {
                    FullScreenHaloOverlay(
                        question = questionState.value,
                        screenMetrics = displayMetrics,
                        onPreviewTap = { x, y, isDoubleTap ->
                            handlePreviewTap(x, y, isDoubleTap)
                        }
                    )
                }
            }
        }

        windowManager.addView(view, layoutParams)
        view.requestFocus()
        overlayView = view
        overlayLayoutParams = layoutParams
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching {
            view.disposeComposition()
            windowManager.removeView(view)
        }
        overlayView = null
        overlayLayoutParams = null
    }

    private fun registerSystemEventReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_CLOSE_SYSTEM_DIALOGS_COMPAT)
        }
        registerReceiver(systemEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterSystemEventReceiver() {
        runCatching {
            unregisterReceiver(systemEventReceiver)
        }
    }

    private fun stopOverlay(reason: String) {
        if (isStopping) {
            return
        }
        isStopping = true
        Log.i(TAG, "Stopping overlay, reason=$reason")
        questionState.value = null
        removeOverlay()
        stopSelf()
    }

    private fun handlePreviewTap(x: Float, y: Float, isDoubleTap: Boolean) {
        serviceScope.launch {
            tapPassThroughMutex.withLock {
                setOverlayTouchable(isTouchable = false)
                try {
                    // Give WM one frame to apply NOT_TOUCHABLE before injecting.
                    delay(16)
                    val injected = withContext(Dispatchers.Default) {
                        if (isDoubleTap) {
                            InputInjector.doubleTap(x, y)
                        } else {
                            InputInjector.tap(x, y)
                        }
                    }
                    if (!injected) {
                        Log.w(TAG, "Tap passthrough injection failed at ($x,$y), doubleTap=$isDoubleTap")
                    }
                } finally {
                    // Delay a bit to prevent the tail of injected events being intercepted by overlay.
                    delay(16)
                    setOverlayTouchable(isTouchable = true)
                }
            }
        }
    }

    private fun setOverlayTouchable(isTouchable: Boolean) {
        val layoutParams = overlayLayoutParams ?: return
        val view = overlayView ?: return

        val updatedFlags = if (isTouchable) {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        if (updatedFlags == layoutParams.flags) {
            return
        }

        layoutParams.flags = updatedFlags
        runCatching {
            windowManager.updateViewLayout(view, layoutParams)
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val EXTRA_SYSTEM_DIALOG_REASON = "reason"
        private const val ACTION_CLOSE_SYSTEM_DIALOGS_COMPAT = "android.intent.action.CLOSE_SYSTEM_DIALOGS"
        private val EXIT_SYSTEM_DIALOG_REASONS = setOf("homekey", "recentapps")

        const val ACTION_SHOW_OVERLAY = "org.avium.autotask.action.SHOW_OVERLAY"
        const val ACTION_STOP_OVERLAY = "org.avium.autotask.action.STOP_OVERLAY"
    }
}

private class OverlayComposeViewTreeOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val internalViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = internalViewModelStore

    fun performCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun performDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        internalViewModelStore.clear()
    }
}
