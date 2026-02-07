package org.avium.autotask.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

object FrameBufferCapture {
    private const val TAG = "FrameBufferCapture"
    private const val MAX_COMPRESSED_BYTES = 250 * 1024
    private const val MIN_QUALITY = 25
    private const val START_QUALITY = 95

    @Volatile
    private var lastErrorMessage: String = ""

    @Volatile
    private var hasDumpedApiSignatures: Boolean = false

    fun lastError(): String = lastErrorMessage

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
        HiddenApiAccess.ensureExemptions()

        captureViaAsyncScreenCapture(context)?.let {
            lastErrorMessage = ""
            return it
        }

        captureViaDisplayArgsClass(ownerClassName = "android.window.ScreenCapture", context = context)?.let {
            lastErrorMessage = ""
            return it
        }

        captureViaDisplayArgsClass(ownerClassName = "android.view.SurfaceControl", context = context)?.let {
            lastErrorMessage = ""
            return it
        }

        captureViaLegacySurfaceControlScreenshot(context)?.let {
            lastErrorMessage = ""
            return it
        }

        if (lastErrorMessage.isBlank()) {
            lastErrorMessage = "all capture strategies failed"
        }
        return null
    }

    private fun captureViaAsyncScreenCapture(context: Context): Bitmap? {
        return runCatching {
            val screenCaptureClass = Class.forName("android.window.ScreenCapture")
            val paramsClass = Class.forName("android.window.ScreenCapture\$ScreenCaptureParams")
            val outcomeReceiverClass = Class.forName("android.os.OutcomeReceiver")
            val displayId = context.display?.displayId ?: 0
            val metrics = displayMetrics(context)

            val params = buildScreenCaptureParams(screenCaptureClass, paramsClass, displayId, metrics)
                ?: throw IllegalStateException("ScreenCaptureParams cannot be constructed")

            val captureMethod = screenCaptureClass.allMethods().firstOrNull {
                it.name == "capture" &&
                    Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0].isAssignableFrom(paramsClass) &&
                    Executor::class.java.isAssignableFrom(it.parameterTypes[1]) &&
                    it.parameterTypes[2].name == "android.os.OutcomeReceiver"
            } ?: throw NoSuchMethodException("ScreenCapture.capture(ScreenCaptureParams, Executor, OutcomeReceiver)")

            val latch = CountDownLatch(1)
            var resultObj: Any? = null
            var errorObj: Any? = null

            val executor = Executor { runnable -> runnable.run() }
            val outcome = Proxy.newProxyInstance(
                outcomeReceiverClass.classLoader,
                arrayOf(outcomeReceiverClass)
            ) { _, method, args ->
                when (method.name) {
                    "onResult" -> resultObj = args?.firstOrNull()
                    "onError" -> errorObj = args?.firstOrNull()
                }
                latch.countDown()
                null
            }

            captureMethod.isAccessible = true
            captureMethod.invoke(null, params, executor, outcome)

            if (!latch.await(1_200, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("ScreenCapture.capture timeout")
            }
            if (errorObj != null) {
                throw IllegalStateException("ScreenCapture.capture error: $errorObj")
            }

            resultObjectToBitmap(resultObj)
        }.onFailure {
            recordFailure("android.window.ScreenCapture async capture failed", it)
        }.getOrNull()
    }

    private fun buildScreenCaptureParams(
        screenCaptureClass: Class<*>,
        paramsClass: Class<*>,
        displayId: Int,
        metrics: DisplayMetrics
    ): Any? {
        paramsClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }?.let { ctor ->
            return runCatching {
                ctor.isAccessible = true
                ctor.newInstance()
            }.getOrNull()
        }

        paramsClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaObjectType)
        }?.let { ctor ->
            return runCatching {
                ctor.isAccessible = true
                ctor.newInstance(displayId)
            }.getOrNull()
        }

        val builderClass = paramsClass.declaredClasses.firstOrNull {
            it.name.endsWith("\$Builder")
        }
        if (builderClass != null) {
            val builder = createBuilder(builderClass, displayId) ?: return null
            builderClass.allMethods().firstOrNull {
                it.name == "setDisplayId" &&
                    it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaObjectType)
            }?.apply {
                isAccessible = true
                invoke(builder, displayId)
            }

            builderClass.allMethods().firstOrNull {
                it.name == "setSize" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }?.apply {
                isAccessible = true
                invoke(builder, metrics.widthPixels, metrics.heightPixels)
            }

            builderClass.allMethods().firstOrNull {
                it.name == "build" && it.parameterTypes.isEmpty()
            }?.let { buildMethod ->
                return runCatching {
                    buildMethod.isAccessible = true
                    buildMethod.invoke(builder)
                }.getOrNull()
            }
        }

        // Static factory fallback on ScreenCapture / ScreenCaptureParams.
        return listOf(screenCaptureClass, paramsClass).firstNotNullOfOrNull { clazz ->
            clazz.allMethods().firstNotNullOfOrNull { method ->
                if (!Modifier.isStatic(method.modifiers) || !paramsClass.isAssignableFrom(method.returnType)) {
                    return@firstNotNullOfOrNull null
                }
                val args = buildMethodArgs(method.parameterTypes, displayId) ?: return@firstNotNullOfOrNull null
                runCatching {
                    method.isAccessible = true
                    method.invoke(null, *args)
                }.getOrNull()
            }
        }
    }

    private fun createBuilder(builderClass: Class<*>, displayId: Int): Any? {
        val ctor = builderClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
            ?: builderClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaObjectType)
            }
            ?: return null

        return runCatching {
            ctor.isAccessible = true
            if (ctor.parameterTypes.isEmpty()) ctor.newInstance() else ctor.newInstance(displayId)
        }.getOrNull()
    }

    private fun resultObjectToBitmap(resultObj: Any?): Bitmap? {
        if (resultObj == null) return null
        if (resultObj is Bitmap) return resultObj

        resultObj.javaClass.allMethods().forEach { method ->
            if (method.parameterTypes.isNotEmpty()) return@forEach
            if (method.returnType == Bitmap::class.java) {
                val bitmap = runCatching {
                    method.isAccessible = true
                    method.invoke(resultObj)
                }.getOrNull() as? Bitmap
                if (bitmap != null) return bitmap
            }
        }

        val hardwareBuffer = resultObj.javaClass.allMethods().firstNotNullOfOrNull { method ->
            if (method.parameterTypes.isNotEmpty()) return@firstNotNullOfOrNull null
            if (!method.returnType.name.contains("HardwareBuffer")) return@firstNotNullOfOrNull null
            runCatching {
                method.isAccessible = true
                method.invoke(resultObj)
            }.getOrNull() as? HardwareBuffer
        }

        if (hardwareBuffer != null) {
            return Bitmap.wrapHardwareBuffer(
                hardwareBuffer,
                ColorSpace.get(ColorSpace.Named.SRGB)
            )
        }

        lastErrorMessage = "screen capture result cannot convert to Bitmap"
        return null
    }

    private fun captureViaDisplayArgsClass(ownerClassName: String, context: Context): Bitmap? {
        return runCatching {
            val ownerClass = Class.forName(ownerClassName)
            val token = resolveDisplayToken(context)
            val metrics = displayMetrics(context)

            val args = buildDisplayCaptureArgs(ownerClass, token, metrics)
                ?: throw ClassNotFoundException("$ownerClassName DisplayCaptureArgs builder not found")

            val captureMethod = ownerClass.allMethods().firstOrNull {
                it.name == "captureDisplay" &&
                    Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(args.javaClass)
            } ?: throw NoSuchMethodException("$ownerClassName.captureDisplay(args)")

            captureMethod.isAccessible = true
            val captureResult = captureMethod.invoke(null, args) ?: return null
            screenshotBufferToBitmap(captureResult)
        }.onFailure {
            recordFailure("$ownerClassName capture failed", it)
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    private fun captureViaLegacySurfaceControlScreenshot(context: Context): Bitmap? {
        return runCatching {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val metrics = displayMetrics(context)
            val rect = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)

            val method4 = surfaceControlClass.allMethods().firstOrNull {
                it.name == "screenshot" && Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(
                        arrayOf(
                            Rect::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                    )
            }
            if (method4 != null) {
                method4.isAccessible = true
                return method4.invoke(null, rect, metrics.widthPixels, metrics.heightPixels, 0) as? Bitmap
            }

            val method5 = surfaceControlClass.allMethods().firstOrNull {
                it.name == "screenshot" && Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(
                        arrayOf(
                            Rect::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                    )
            }
            if (method5 != null) {
                method5.isAccessible = true
                return method5.invoke(null, rect, metrics.widthPixels, metrics.heightPixels, false, 0) as? Bitmap
            }

            val method2 = surfaceControlClass.allMethods().firstOrNull {
                it.name == "screenshot" && Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    )
            }
            if (method2 != null) {
                method2.isAccessible = true
                return method2.invoke(null, metrics.widthPixels, metrics.heightPixels) as? Bitmap
            }

            throw NoSuchMethodException("Legacy SurfaceControl.screenshot not found")
        }.onFailure {
            recordFailure("legacy SurfaceControl screenshot failed", it)
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    private fun resolveDisplayToken(context: Context): Any {
        val surfaceControlClass = Class.forName("android.view.SurfaceControl")
        val displayId = context.display?.displayId ?: 0

        invokeStaticNoArg(surfaceControlClass, "getInternalDisplayToken")?.let { return it }
        invokeStaticOneArg(surfaceControlClass, "getBuiltInDisplay", 0)?.let { return it }
        invokeStaticOneArg(surfaceControlClass, "getDisplayToken", displayId)?.let { return it }

        dumpApiSignaturesOnce()
        throw IllegalStateException("No supported display token resolver")
    }

    private fun dumpApiSignaturesOnce() {
        if (hasDumpedApiSignatures) return
        hasDumpedApiSignatures = true

        dumpClassMethods("android.view.SurfaceControl")
        dumpClassMethods("android.window.ScreenCapture")
    }

    private fun dumpClassMethods(className: String) {
        runCatching {
            val clazz = Class.forName(className)
            val staticMethods = clazz.allMethods()
                .filter { Modifier.isStatic(it.modifiers) }
                .distinctBy { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
                .sortedBy { it.name }

            Log.w(TAG, "API dump for $className, staticMethods=${staticMethods.size}")
            staticMethods.forEach { method ->
                val params = method.parameterTypes.joinToString(",") { it.simpleName }
                Log.w(TAG, "  ${method.returnType.simpleName} ${method.name}($params)")
            }

            clazz.declaredClasses.forEach { nested ->
                Log.w(TAG, "  nested=${nested.name}")
                nested.declaredConstructors.forEach { ctor ->
                    val params = ctor.parameterTypes.joinToString(",") { it.simpleName }
                    Log.w(TAG, "    ctor($params)")
                }
                nested.declaredMethods.forEach { method ->
                    val params = method.parameterTypes.joinToString(",") { it.simpleName }
                    Log.w(TAG, "    method ${method.returnType.simpleName} ${method.name}($params)")
                }
            }
        }.onFailure {
            Log.w(TAG, "API dump failed for $className: ${it.shortMessage()}")
        }
    }

    private fun invokeStaticNoArg(owner: Class<*>, name: String): Any? {
        val method = owner.allMethods().firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty()
        } ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(null) }.getOrNull()
    }

    private fun invokeStaticOneArg(owner: Class<*>, name: String, arg: Any): Any? {
        val method = owner.allMethods().firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.size == 1 && canAssignArgument(it.parameterTypes[0], arg)
        } ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(null, arg) }.getOrNull()
    }

    private fun canAssignArgument(paramType: Class<*>, arg: Any): Boolean {
        return when (arg) {
            is Int -> paramType == Int::class.javaPrimitiveType || paramType == Int::class.javaObjectType
            is Long -> paramType == Long::class.javaPrimitiveType || paramType == Long::class.javaObjectType
            else -> paramType.isAssignableFrom(arg.javaClass)
        }
    }

    private fun buildMethodArgs(paramTypes: Array<Class<*>>, displayId: Int): Array<Any?>? {
        if (paramTypes.size > 3) return null
        val args = Array<Any?>(paramTypes.size) { null }
        for (i in paramTypes.indices) {
            val p = paramTypes[i]
            args[i] = when {
                p == Int::class.javaPrimitiveType || p == Int::class.javaObjectType -> if (i == 0) displayId else 0
                p == Long::class.javaPrimitiveType || p == Long::class.javaObjectType -> if (i == 0) displayId.toLong() else 0L
                p == Boolean::class.javaPrimitiveType || p == Boolean::class.javaObjectType -> false
                p.name == "android.os.IBinder" -> null
                else -> return null
            }
        }
        return args
    }

    private fun buildDisplayCaptureArgs(ownerClass: Class<*>, displayToken: Any, metrics: DisplayMetrics): Any? {
        val argsClass = ownerClass.findNestedClass {
            it.name.contains("DisplayCaptureArgs") && !it.name.endsWith("\$Builder")
        } ?: return null
        val builderClass = ownerClass.findNestedClass {
            it.name.contains("DisplayCaptureArgs") && it.name.endsWith("\$Builder")
        } ?: return null

        val builderCtor = builderClass.declaredConstructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 1 && params[0].isAssignableFrom(displayToken.javaClass)
        } ?: builderClass.declaredConstructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 1 && params[0].name == "android.os.IBinder"
        } ?: return null

        builderCtor.isAccessible = true
        val builder = builderCtor.newInstance(displayToken)

        builderClass.allMethods().firstOrNull {
            it.name == "setSize" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.apply {
            isAccessible = true
            invoke(builder, metrics.widthPixels, metrics.heightPixels)
        }

        val buildMethod = builderClass.allMethods().firstOrNull {
            it.name == "build" && it.parameterTypes.isEmpty()
        } ?: return null
        buildMethod.isAccessible = true
        val args = buildMethod.invoke(builder) ?: return null

        if (!argsClass.isAssignableFrom(args.javaClass)) {
            return null
        }
        return args
    }

    private fun screenshotBufferToBitmap(screenshotBuffer: Any): Bitmap? {
        if (screenshotBuffer is Bitmap) {
            return screenshotBuffer
        }

        val asBitmap = screenshotBuffer.javaClass.allMethods().firstOrNull {
            it.name == "asBitmap" && it.parameterTypes.isEmpty()
        }
        val bitmap = asBitmap?.let {
            it.isAccessible = true
            it.invoke(screenshotBuffer)
        } as? Bitmap

        if (bitmap == null) {
            lastErrorMessage = "screenshot result cannot convert to Bitmap"
        }
        return bitmap
    }

    private fun Class<*>.findNestedClass(predicate: (Class<*>) -> Boolean): Class<*>? {
        val queue = ArrayDeque<Class<*>>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.declaredClasses.forEach { nested ->
                if (predicate(nested)) {
                    return nested
                }
                queue.add(nested)
            }
        }
        return null
    }

    private fun Class<*>.allMethods(): List<Method> {
        return methods.toList() + declaredMethods.toList()
    }

    private fun recordFailure(prefix: String, throwable: Throwable) {
        lastErrorMessage = "$prefix: ${throwable.shortMessage()}"
        Log.w(TAG, "$prefix: ${throwable.shortMessage()}")
    }

    private fun Throwable.shortMessage(): String {
        val message = message ?: ""
        return if (message.isBlank()) javaClass.simpleName else "${javaClass.simpleName}: $message"
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
