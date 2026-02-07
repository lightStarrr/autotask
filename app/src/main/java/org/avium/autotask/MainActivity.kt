package org.avium.autotask

import android.graphics.Bitmap
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.avium.autotask.ui.theme.AutoTaskTheme
import org.avium.autotask.util.FrameBufferCapture
import org.avium.autotask.util.HiddenApiAccess
import org.avium.autotask.util.InputInjector

private const val PREVIEW_TAG = "ScreenPreview"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HiddenApiAccess.ensureExemptions()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val question = intent?.getStringExtra(EXTRA_QUESTION)
        setContent {
            AutoTaskTheme {
                FullScreenOutlineEffect(
                    question = question,
                    screenMetrics = resources.displayMetrics,
                    overlayWindow = window
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).hide(
                WindowInsetsCompat.Type.systemBars()
            )
        }
    }
}

@Composable
fun FullScreenOutlineEffect(
    question: String?,
    screenMetrics: DisplayMetrics,
    overlayWindow: Window
) {
    val transition = rememberInfiniteTransition(label = "outline")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val sweepProgress by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewSizePx by remember { mutableStateOf(IntSize.Zero) }
    var previewStatus by remember { mutableStateOf("初始化中") }

    LaunchedEffect(Unit) {
        while (isActive) {
            val snapshot = withContext(Dispatchers.Default) {
                FrameBufferCapture.captureBitmap(context)
            }
            frameBitmap = snapshot
            if (snapshot == null) {
                val reason = FrameBufferCapture.lastError().ifBlank { "unknown" }
                previewStatus = "未获取到画面: $reason"
                Log.w(PREVIEW_TAG, "captureBitmap returned null, reason=$reason")
            } else {
                previewStatus = "已连接 ${snapshot.width}x${snapshot.height}"
                Log.d(PREVIEW_TAG, "captureBitmap success ${snapshot.width}x${snapshot.height}")
            }
            delay(1_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepX = size.width * sweepProgress
            val sweepWidth = size.width * 0.6f
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF00E5FF).copy(alpha = glowAlpha),
                        Color.Transparent
                    ),
                    start = androidx.compose.ui.geometry.Offset(sweepX - sweepWidth, 0f),
                    end = androidx.compose.ui.geometry.Offset(sweepX + sweepWidth, size.height)
                )
            )
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7C4DFF).copy(alpha = glowAlpha * 0.9f),
                        Color.Transparent
                    ),
                    center = androidx.compose.ui.geometry.Offset(
                        size.width * 0.5f,
                        size.height * 0.45f
                    ),
                    radius = size.minDimension * 0.75f
                )
            )
        }

        if (!question.isNullOrBlank()) {
            Text(
                text = question,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        val previewBitmap = frameBitmap
        val previewAspect = if (previewBitmap != null && previewBitmap.height > 0) {
            (previewBitmap.width.toFloat() / previewBitmap.height.toFloat()).coerceAtLeast(0.1f)
        } else {
            (screenMetrics.widthPixels.toFloat() / screenMetrics.heightPixels.toFloat()).coerceAtLeast(0.1f)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .width(220.dp)
                .aspectRatio(previewAspect)
                .onSizeChanged { previewSizePx = it }
                .border(width = 1.dp, color = Color.White.copy(alpha = 0.65f))
                .background(Color.Black.copy(alpha = 0.72f))
                .pointerInput(previewBitmap, previewSizePx) {
                    if (previewBitmap == null) {
                        return@pointerInput
                    }
                    detectTapGestures(
                        onTap = { offset ->
                            if (previewSizePx.width <= 0 || previewSizePx.height <= 0) {
                                return@detectTapGestures
                            }
                            val x = (offset.x / previewSizePx.width) * screenMetrics.widthPixels
                            val y = (offset.y / previewSizePx.height) * screenMetrics.heightPixels
                            scope.launch {
                                InputInjector.tapThrough(overlayWindow, x, y)
                            }
                        },
                        onDoubleTap = { offset ->
                            if (previewSizePx.width <= 0 || previewSizePx.height <= 0) {
                                return@detectTapGestures
                            }
                            val x = (offset.x / previewSizePx.width) * screenMetrics.widthPixels
                            val y = (offset.y / previewSizePx.height) * screenMetrics.heightPixels
                            scope.launch {
                                InputInjector.doubleTapThrough(overlayWindow, x, y)
                            }
                        }
                    )
                }
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "screen preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            } else {
                Text(
                    text = previewStatus,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
