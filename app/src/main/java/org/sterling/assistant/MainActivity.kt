package org.sterling.assistant

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDateTime

private val NeonBlue = Color(0xFF3366FF)
private val NeonPurple = Color(0xFFCC33CC)
private val DarkBg = Color(0xFF0A0A14)
private val DarkSurface = Color(0xFF14141F)

private val SterlingColors = darkColorScheme(
    primary = NeonBlue,
    secondary = NeonPurple,
    background = DarkBg,
    surface = DarkSurface,
)

@Composable
fun SterlingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SterlingColors, content = content)
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(filesDir, "main_crash.txt").writeText(
                    "App crashed at ${LocalDateTime.now()} on thread ${thread.name}\n\n" +
                        throwable.stackTraceToString()
                )
            } catch (_: Exception) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        prefs = getSharedPreferences("sterling_config", Context.MODE_PRIVATE)

        setContent {
            SterlingTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(activity = this, prefs = prefs)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(activity: ComponentActivity, prefs: SharedPreferences) {
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var model by remember {
        mutableStateOf(prefs.getString("model", "meta-llama/llama-3.1-8b-instruct:free") ?: "")
    }
    var wakeWord by remember { mutableStateOf(prefs.getString("wake_word", "hey sterling") ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("idle") }
    var crashLog by remember {
        mutableStateOf("Tap 'View crash logs' after a crash to see the full traceback here.")
    }

    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun saveSettings() {
        prefs.edit()
            .putString("api_key", apiKey.trim())
            .putString("model", model.trim().ifBlank { "meta-llama/llama-3.1-8b-instruct:free" })
            .putString("wake_word", wakeWord.trim().lowercase().ifBlank { "hey sterling" })
            .apply()
        status = "settings saved"
    }

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        status = if (denied.isEmpty()) "mic, contacts + notification permissions granted" else "denied: ${denied.joinToString()}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sterling Voice Assistant", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Say the wake word, ask a question, and it will answer out loud. " +
                "The floating orb stays on screen even when this app is minimized."
        )

        Text("OpenRouter API key")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    apiKey = clip.getItemAt(0).text?.toString() ?: apiKey
                    status = "pasted API key from clipboard"
                } else {
                    status = "clipboard is empty - copy the key first"
                }
            }) { Text("Paste") }
            Button(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
        }

        Text("Model (must end in :free for no cost)")
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Wake word")
        OutlinedTextField(
            value = wakeWord,
            onValueChange = { wakeWord = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = { saveSettings() }, modifier = Modifier.fillMaxWidth()) {
            Text("Save settings")
        }

        Button(
            onClick = {
                saveSettings()
                val perms = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                multiPermLauncher.launch(perms.toTypedArray())

                if (!Settings.canDrawOverlays(activity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("1. Grant mic, contacts + overlay permissions") }

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                    status = "enable 'All files access' for Sterling, then come back"
                } else {
                    status = "All files access already granted"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("2. Grant 'All files access' (for file browsing tool)") }

        Button(
            onClick = {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                } else {
                    status = "battery optimization already disabled"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("3. Disable battery optimization for Sterling") }

        Button(
            onClick = {
                saveSettings()
                val intent = Intent(activity, SterlingService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    activity.startForegroundService(intent)
                } else {
                    activity.startService(intent)
                }
                status = "Sterling service starting..."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("4. Start Sterling (background orb)") }

        Button(
            onClick = {
                activity.stopService(Intent(activity, SterlingService::class.java))
                status = "Sterling service stopped"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Stop Sterling") }

        Text("Status: $status")

        Button(
            onClick = {
                val mainCrash = File(activity.filesDir, "main_crash.txt")
                val serviceCrash = File(activity.filesDir, "service_crash.txt")
                val sections = mutableListOf<String>()
                if (mainCrash.exists()) sections.add("=== MAIN APP CRASH ===\n${mainCrash.readText()}")
                if (serviceCrash.exists()) sections.add("=== SERVICE CRASH ===\n${serviceCrash.readText()}")
                crashLog = if (sections.isEmpty()) {
                    "No crash logs found. Tap 'Start Sterling', wait for it to crash " +
                        "(if it's going to), then tap this button again."
                } else {
                    sections.joinToString("\n\n")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("View crash logs") }

        OutlinedTextField(
            value = crashLog,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        )
    }
}
