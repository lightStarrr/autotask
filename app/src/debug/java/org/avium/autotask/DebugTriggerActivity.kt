package org.avium.autotask

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.avium.autotask.overlay.contract.OverlayCommandDispatcher
import org.avium.autotask.ui.theme.autoTaskTheme

class DebugTriggerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            autoTaskTheme {
                debugTriggerScreen()
            }
        }
    }
}

@Composable
private fun debugTriggerScreen() {
    val context = LocalContext.current
    val questionState = remember { mutableStateOf("") }
    val packageState = remember { mutableStateOf("mark.via") }
    val activityState = remember { mutableStateOf("") }
    val passthroughState = remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "AutoTask Debug")

        OutlinedTextField(
            value = questionState.value,
            onValueChange = { questionState.value = it },
            label = { Text(text = "Question extra") },
        )

        OutlinedTextField(
            value = packageState.value,
            onValueChange = { packageState.value = it },
            label = { Text(text = "Target package") },
        )

        OutlinedTextField(
            value = activityState.value,
            onValueChange = { activityState.value = it },
            label = { Text(text = "Target activity (optional)") },
        )

        Button(
            onClick = {
                OverlayCommandDispatcher.start(
                    context = context,
                    question = questionState.value.ifBlank { null },
                    targetPackage = packageState.value.ifBlank { null },
                    targetActivity = activityState.value.ifBlank { null },
                )
            },
        ) {
            Text(text = "Start Overlay")
        }

        Button(
            onClick = {
                passthroughState.value = !passthroughState.value
                OverlayCommandDispatcher.setTouchPassthrough(context, passthroughState.value)
            },
        ) {
            Text(text = if (passthroughState.value) "Disable Passthrough" else "Enable Passthrough")
        }

        Button(onClick = { OverlayCommandDispatcher.stop(context) }) {
            Text(text = "Stop Overlay")
        }
    }
}
