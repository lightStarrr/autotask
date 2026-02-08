package org.avium.autotask

import android.content.Intent
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
import org.avium.autotask.ui.theme.AutoTaskTheme

class DebugTriggerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoTaskTheme {
                DebugTriggerScreen()
            }
        }
    }
}

@Composable
fun DebugTriggerScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val input = remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "测试触发器（仅 Debug）")
        OutlinedTextField(
            value = input.value,
            onValueChange = { input.value = it },
            label = { Text("输入问题") },
            singleLine = true
        )
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context, MainActivity::class.java.name)
                    putExtra(EXTRA_QUESTION, input.value)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                activity?.finish()
            }
        ) {
            Text("打开全屏悬浮窗")
        }
    }
}
