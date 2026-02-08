package org.avium.autotask

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.avium.autotask.util.HiddenApiAccess

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HiddenApiAccess.ensureExemptions()

        val question = intent?.getStringExtra(EXTRA_QUESTION)
        if (Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(this, FullScreenOverlayService::class.java).apply {
                action = FullScreenOverlayService.ACTION_SHOW_OVERLAY
                putExtra(EXTRA_QUESTION, question)
            }
            startService(overlayIntent)
        } else {
            Toast.makeText(this, "请先授予悬浮窗权限后重试", Toast.LENGTH_SHORT).show()
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(permissionIntent)
        }

        finish()
    }
}
