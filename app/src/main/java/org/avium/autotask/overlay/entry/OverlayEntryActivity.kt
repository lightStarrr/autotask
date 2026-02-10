package org.avium.autotask.overlay.entry

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.avium.autotask.overlay.contract.OverlayCommandDispatcher
import org.avium.autotask.overlay.contract.OverlayContract

class OverlayEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val question = intent.getStringExtra(OverlayContract.EXTRA_QUESTION)
        val targetPackage = intent.getStringExtra(OverlayContract.EXTRA_TARGET_PACKAGE)
        val targetActivity = intent.getStringExtra(OverlayContract.EXTRA_TARGET_ACTIVITY)

        OverlayCommandDispatcher.start(
            context = this,
            question = question,
            targetPackage = targetPackage,
            targetActivity = targetActivity,
        )
        finish()
    }
}
