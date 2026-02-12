package org.avium.autotask.overlay.launch

import android.app.ActivityOptions
import android.app.IActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import org.avium.autotask.overlay.contract.OverlayContract

class TargetLauncher(
    private val context: Context,
    private val loggerTag: String,
    private val hiddenActivityManager: IActivityManager?,
) {
    fun launchOnDisplay(
        displayId: Int,
        request: TargetLaunchRequest,
        candidates: List<Intent>,
    ): Boolean {
        if (candidates.isEmpty()) {
            Log.w(loggerTag, "Launch intent not found for ${request.targetPackage}")
            return false
        }

        // Match Flyme flow: always request launch on virtual display with no transition.
        val options =
            ActivityOptions.makeCustomAnimation(context, 0, 0)
                .setLaunchDisplayId(displayId)

        for (candidate in candidates) {
            val launchIntent = Intent(candidate)
            request.question?.let { launchIntent.putExtra(OverlayContract.EXTRA_QUESTION, it) }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

            val launched =
                if (hiddenActivityManager != null) {
                    startWithHiddenApi(launchIntent, options)
                } else {
                    startWithContext(launchIntent, options)
                }
            if (launched) {
                return true
            }
        }
        return false
    }

    private fun startWithHiddenApi(intent: Intent, options: ActivityOptions): Boolean {
        return try {
            val result =
                hiddenActivityManager?.startActivityAsUserWithFeature(
                    null,
                    context.packageName,
                    null,
                    intent,
                    intent.resolveTypeIfNeeded(context.contentResolver),
                    null,
                    null,
                    0,
                    0,
                    null,
                    options.toBundle(),
                    0,
                ) ?: return false
            if (result >= 0) {
                true
            } else {
                Log.w(loggerTag, "Hidden startActivityAsUserWithFeature failed, result=$result intent=$intent")
                false
            }
        } catch (e: Exception) {
            Log.e(loggerTag, "Failed hidden-api start for $intent", e)
            false
        }
    }

    private fun startWithContext(intent: Intent, options: ActivityOptions): Boolean {
        return try {
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.e(loggerTag, "Failed to start target activity with $intent", e)
            false
        }
    }
}
