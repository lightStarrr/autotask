package org.avium.autotask

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.avium.autotask.IntentKeys.EXTRA_PACKAGE_NAME
import org.avium.autotask.IntentKeys.EXTRA_QUESTION

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val question = intent.getStringExtra(EXTRA_QUESTION)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)

        FullScreenOverlayService.start(this, question, packageName)
        finish()
    }
}
