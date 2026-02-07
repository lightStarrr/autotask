package org.avium.autotask.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

object FrameBufferCapture {
    private const val MAX_COMPRESSED_BYTES = 250 * 1024
    private const val MIN_QUALITY = 25
    private const val START_QUALITY = 95

    fun captureAndCompress(context: Context, maxBytes: Int = MAX_COMPRESSED_BYTES): ByteArray? {
        val rawBitmap = captureFrameBuffer(context) ?: return null
        val preprocessed = preprocess(rawBitmap)
        return compressToSize(preprocessed, maxBytes)
    }

    fun captureBitmap(context: Context): Bitmap? {
        val rawBitmap = captureFrameBuffer(context) ?: return null
        return preprocess(rawBitmap)
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun captureFrameBuffer(context: Context): Bitmap? {
        return try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val getToken = surfaceControlClass.getDeclaredMethod("getInternalDisplayToken")
            val displayToken = getToken.invoke(null)

            val metrics = displayMetrics(context)
            val displayCaptureArgsClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs")
            val builderClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
            val builder = builderClass.getConstructor(Class.forName("android.os.IBinder"))
                .newInstance(displayToken)
            builderClass.getMethod("setSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(builder, metrics.widthPixels, metrics.heightPixels)
            val args = builderClass.getMethod("build").invoke(builder)

            val captureDisplay = surfaceControlClass.getDeclaredMethod("captureDisplay", displayCaptureArgsClass)
            val screenshotBuffer = captureDisplay.invoke(null, args) ?: return null
            val asBitmap = screenshotBuffer.javaClass.getMethod("asBitmap")
            asBitmap.invoke(screenshotBuffer) as? Bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun displayMetrics(context: Context): DisplayMetrics {
        return context.resources.displayMetrics
    }

    private fun preprocess(bitmap: Bitmap): Bitmap {
        val argb = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        val maxPixels = 2_000_000
        val pixelCount = argb.width * argb.height
        val scaled = if (pixelCount > maxPixels) {
            val scale = sqrt(maxPixels.toDouble() / pixelCount.toDouble())
            val targetW = (argb.width * scale).toInt().coerceAtLeast(1)
            val targetH = (argb.height * scale).toInt().coerceAtLeast(1)
            argb.scale(targetW, targetH)
        } else {
            argb
        }

        if (scaled.config == Bitmap.Config.ARGB_8888) {
            return scaled
        }

        val out = createBitmap(scaled.width, scaled.height)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }

    private fun compressToSize(bitmap: Bitmap, maxBytes: Int): ByteArray? {
        var quality = START_QUALITY
        var currentBitmap = bitmap
        while (true) {
            val stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val data = stream.toByteArray()
            if (data.size <= maxBytes) {
                return data
            }

            if (quality > MIN_QUALITY) {
                quality -= 10
                continue
            }

            val nextWidth = (currentBitmap.width * 0.85f).toInt().coerceAtLeast(1)
            val nextHeight = (currentBitmap.height * 0.85f).toInt().coerceAtLeast(1)
            if (nextWidth == currentBitmap.width || nextHeight == currentBitmap.height) {
                return data
            }
            currentBitmap = currentBitmap.scale(nextWidth, nextHeight)
            quality = START_QUALITY
        }
    }
}
