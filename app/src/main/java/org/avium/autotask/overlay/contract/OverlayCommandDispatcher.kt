package org.avium.autotask.overlay.contract

import android.content.Context
import android.content.Intent
import org.avium.autotask.overlay.service.OverlayService

object OverlayCommandDispatcher {
    fun start(
        context: Context,
        question: String?,
        targetPackage: String?,
        targetActivity: String? = null,
    ) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayContract.ACTION_START
            putExtra(OverlayContract.EXTRA_PROTOCOL_VERSION, OverlayContract.PROTOCOL_VERSION)
            putExtra(OverlayContract.EXTRA_QUESTION, question)
            putExtra(
                OverlayContract.EXTRA_TARGET_PACKAGE,
                targetPackage ?: OverlayContract.DEFAULT_TARGET_PACKAGE,
            )
            putExtra(OverlayContract.EXTRA_TARGET_ACTIVITY, targetActivity)
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayContract.ACTION_STOP
            putExtra(OverlayContract.EXTRA_PROTOCOL_VERSION, OverlayContract.PROTOCOL_VERSION)
        }
        context.startService(intent)
    }

    fun toggleSize(context: Context) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayContract.ACTION_TOGGLE_SIZE
            putExtra(OverlayContract.EXTRA_PROTOCOL_VERSION, OverlayContract.PROTOCOL_VERSION)
        }
        context.startForegroundService(intent)
    }

    fun setTouchPassthrough(
        context: Context,
        enabled: Boolean,
    ) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayContract.ACTION_SET_TOUCH_PASSTHROUGH
            putExtra(OverlayContract.EXTRA_PROTOCOL_VERSION, OverlayContract.PROTOCOL_VERSION)
            putExtra(OverlayContract.EXTRA_TOUCH_PASSTHROUGH, enabled)
        }
        context.startForegroundService(intent)
    }
}
