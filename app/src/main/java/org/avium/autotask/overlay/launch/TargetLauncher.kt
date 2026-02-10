package org.avium.autotask.overlay.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import org.avium.autotask.overlay.contract.OverlayContract

class TargetLauncher(
    private val context: Context,
    private val loggerTag: String,
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

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)

        for (candidate in candidates) {
            request.question?.let { candidate.putExtra(OverlayContract.EXTRA_QUESTION, it) }
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(candidate, options.toBundle())
                return true
            } catch (e: Exception) {
                Log.e(loggerTag, "Failed to start target activity with $candidate", e)
            }
        }
        return false
    }
}
