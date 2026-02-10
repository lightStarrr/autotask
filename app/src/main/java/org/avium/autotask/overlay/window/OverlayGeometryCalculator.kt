package org.avium.autotask.overlay.window

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt

data class OverlayGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val topInset: Int,
    val bottomInset: Int,
    val largeWindowWidth: Int,
    val largeWindowHeight: Int,
    val largeWindowX: Int,
    val largeWindowY: Int,
    val miniSize: Int,
    val miniHeight: Int,
    val miniScale: Float,
    val miniClipRect: Rect,
    val miniCornerRadiusPx: Int,
)

class OverlayGeometryCalculator {
    fun calculate(
        screenWidth: Int,
        screenHeight: Int,
        topInset: Int,
        bottomInset: Int,
        dpToPx: (Float) -> Int,
        virtualAspect: Float,
        largeWidthRatio: Float,
        maxLargeHeightRatio: Float,
        miniWidthRatio: Float,
        miniMinSizeDp: Float,
        miniCornerRadiusDp: Float,
    ): OverlayGeometry {
        var computedLargeWidth = (screenWidth * largeWidthRatio).roundToInt()
        var computedLargeHeight = (computedLargeWidth / virtualAspect).roundToInt()

        val maxLargeHeight = (screenHeight * maxLargeHeightRatio).roundToInt()
        if (computedLargeHeight > maxLargeHeight) {
            computedLargeHeight = maxLargeHeight
            computedLargeWidth = (computedLargeHeight * virtualAspect).roundToInt()
        }

        val largeWindowWidth = computedLargeWidth.coerceAtLeast(1)
        val largeWindowHeight = computedLargeHeight.coerceAtLeast(1)

        val largeWindowX = ((screenWidth - largeWindowWidth) / 2).coerceAtLeast(0)
        val largeWindowY = ((screenHeight - largeWindowHeight) / 2).coerceAtLeast(topInset)

        val minMiniPx = dpToPx(miniMinSizeDp)
        val miniSize = max((screenWidth * miniWidthRatio).roundToInt(), minMiniPx)
        val miniScale = miniSize.toFloat() / largeWindowWidth.toFloat()
        val miniHeight = (largeWindowHeight * miniScale).roundToInt().coerceAtLeast(1)

        return OverlayGeometry(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topInset = topInset,
            bottomInset = bottomInset,
            largeWindowWidth = largeWindowWidth,
            largeWindowHeight = largeWindowHeight,
            largeWindowX = largeWindowX,
            largeWindowY = largeWindowY,
            miniSize = miniSize,
            miniHeight = miniHeight,
            miniScale = miniScale,
            miniClipRect = Rect(0, 0, largeWindowWidth, largeWindowHeight),
            miniCornerRadiusPx = dpToPx(miniCornerRadiusDp),
        )
    }
}
