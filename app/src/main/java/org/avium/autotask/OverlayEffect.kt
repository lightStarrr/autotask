package org.avium.autotask

import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.util.Log
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.avium.autotask.util.FrameBufferCapture

private const val PREVIEW_TAG = "ScreenPreview"

@Composable
fun FullScreenHaloOverlay(
    question: String?,
    screenMetrics: DisplayMetrics,
    onPreviewTap: (x: Float, y: Float, isDoubleTap: Boolean) -> Unit
) {
    val transition = rememberInfiniteTransition(label = "edgeHalo")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val haloAlpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.56f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )
    val edgeWidthFactor by transition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.075f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edgeWidth"
    )
    val cornerAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cornerAlpha"
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
            val edgeSize = (size.minDimension * edgeWidthFactor).coerceAtLeast(24f)
            val cyan = Color(0xFF00E5FF)
            val purple = Color(0xFF7C4DFF)

            // Top halo
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(cyan.copy(alpha = haloAlpha), Color.Transparent),
                    startY = 0f,
                    endY = edgeSize
                ),
                size = Size(size.width, edgeSize)
            )
            // Bottom halo
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, cyan.copy(alpha = haloAlpha)),
                    startY = size.height - edgeSize,
                    endY = size.height
                ),
                topLeft = Offset(0f, size.height - edgeSize),
                size = Size(size.width, edgeSize)
            )
            // Left halo
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(purple.copy(alpha = haloAlpha * 0.92f), Color.Transparent),
                    startX = 0f,
                    endX = edgeSize
                ),
                size = Size(edgeSize, size.height)
            )
            // Right halo
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, purple.copy(alpha = haloAlpha * 0.92f)),
                    startX = size.width - edgeSize,
                    endX = size.width
                ),
                topLeft = Offset(size.width - edgeSize, 0f),
                size = Size(edgeSize, size.height)
            )

            val cornerRadius = edgeSize * 3f
            val cornerColors = listOf(
                Color.White.copy(alpha = cornerAlpha),
                Color.Transparent
            )

            drawRect(
                brush = Brush.radialGradient(
                    colors = cornerColors,
                    center = Offset(0f, 0f),
                    radius = cornerRadius
                ),
                size = Size(cornerRadius, cornerRadius)
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = cornerColors,
                    center = Offset(size.width, 0f),
                    radius = cornerRadius
                ),
                topLeft = Offset(size.width - cornerRadius, 0f),
                size = Size(cornerRadius, cornerRadius)
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = cornerColors,
                    center = Offset(0f, size.height),
                    radius = cornerRadius
                ),
                topLeft = Offset(0f, size.height - cornerRadius),
                size = Size(cornerRadius, cornerRadius)
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = cornerColors,
                    center = Offset(size.width, size.height),
                    radius = cornerRadius
                ),
                topLeft = Offset(size.width - cornerRadius, size.height - cornerRadius),
                size = Size(cornerRadius, cornerRadius)
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
                                onPreviewTap(x, y, false)
                            }
                        },
                        onDoubleTap = { offset ->
                            if (previewSizePx.width <= 0 || previewSizePx.height <= 0) {
                                return@detectTapGestures
                            }
                            val x = (offset.x / previewSizePx.width) * screenMetrics.widthPixels
                            val y = (offset.y / previewSizePx.height) * screenMetrics.heightPixels
                            scope.launch {
                                onPreviewTap(x, y, true)
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
