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

        // Force no-transition launch on the virtual display so mini mode does not flash app-open animation.
        val options =
            ActivityOptions.makeCustomAnimation(context, 0, 0)
                .setLaunchDisplayId(displayId)

        for (candidate in candidates) {
            val launchIntent = Intent(candidate)
            request.question?.let { launchIntent.putExtra(OverlayContract.EXTRA_QUESTION, it) }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            try {
                context.startActivity(launchIntent, options.toBundle())
                return true
            } catch (e: Exception) {
                Log.e(loggerTag, "Failed to start target activity with $launchIntent", e)
            }
        }
        return false
    }
}
