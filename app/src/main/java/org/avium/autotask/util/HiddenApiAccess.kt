package org.avium.autotask.util

import android.util.Log

object HiddenApiAccess {
    private const val TAG = "HiddenApiAccess"

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = false

    fun ensureExemptions(): Boolean {
        if (initialized) {
            return enabled
        }

        synchronized(this) {
            if (initialized) {
                return enabled
            }

            enabled = runCatching {
                val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
                val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
                val setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions",
                    Array<String>::class.java
                )
                getRuntime.isAccessible = true
                setHiddenApiExemptions.isAccessible = true
                val runtime = getRuntime.invoke(null)
                val prefixes = arrayOf(
                    "Landroid/view/SurfaceControl;",
                    "Landroid/window/ScreenCapture;",
                    "Landroid/hardware/input/InputManager;",
                    "Landroid/hardware/input/InputManagerGlobal;",
                    "Landroid/os/"
                )
                setHiddenApiExemptions.invoke(runtime, prefixes)
                true
            }.onFailure {
                Log.w(TAG, "Hidden API exemption failed: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)

            initialized = true
            if (enabled) {
                Log.i(TAG, "Hidden API exemptions enabled")
            }
            return enabled
        }
    }
}
