package org.avium.autotask.overlay.launch

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.net.toUri
import org.avium.autotask.overlay.contract.OverlayContract

class TargetIntentResolver(
    private val packageManager: PackageManager,
    private val loggerTag: String,
) {
    fun resolveCandidates(request: TargetLaunchRequest): List<Intent> {
        return if (request.targetPackage == OverlayContract.DEFAULT_TARGET_PACKAGE) {
            listOfNotNull(resolveDialerImplicit(), resolveDialerFallback(request.targetPackage))
        } else {
            listOfNotNull(
                resolveExplicitIntent(request),
                resolveLaunchIntent(request.targetPackage),
                resolveDialerFallback(request.targetPackage),
                resolveDialerImplicit(),
            )
        }
    }

    private fun resolveLaunchIntent(targetPackage: String): Intent? {
        Log.d(loggerTag, "resolveLaunchIntent: targetPackage=$targetPackage")

        try {
            packageManager.getPackageInfo(targetPackage, 0)
            Log.d(loggerTag, "Package $targetPackage exists")
        } catch (e: Exception) {
            Log.w(loggerTag, "Package $targetPackage not found", e)
            return null
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            Log.d(loggerTag, "Found launch intent: ${launchIntent.component}")
            return launchIntent
        }

        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(queryIntent, 0)
        val match = resolveInfos.firstOrNull { it.activityInfo.packageName == targetPackage } ?: return null

        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            component = ComponentName(targetPackage, match.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveExplicitIntent(request: TargetLaunchRequest): Intent? {
        val activityName = request.targetActivity?.takeIf { it.isNotBlank() } ?: return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            component = ComponentName(request.targetPackage, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent.takeIf { it.resolveActivity(packageManager) != null }
    }

    private fun resolveDialerFallback(targetPackage: String): Intent? {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = "tel:".toUri()
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = dialIntent.resolveActivity(packageManager) ?: return null
        return dialIntent.setComponent(resolved)
    }

    private fun resolveDialerImplicit(): Intent? {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = "tel:".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return dialIntent.takeIf { it.resolveActivity(packageManager) != null }
    }
}
