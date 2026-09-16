package org.sterling.assistant

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlin.coroutines.resume

private val NeonBlue = Color(0xFF3366FF)
private val NeonGreen = Color(0xFF33CC66)
private val NeonOrange = Color(0xFFFFAA00)
private val NeonPurple = Color(0xFFCC33CC)
private val NeonRed = Color(0xFFCC3333)
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

/**
 * Handles the actual listen -> chat -> speak loop for the in-app,
 * no-wake-word conversation mode. Runs entirely while the app is open and
 * visible, which is both simpler than the wake-word background service and
 * a much safer permission story (microphone only used while-in-use, not
 * continuously in the background) if this is ever submitted to Play Store.
 */
class ConversationManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
        }
    }

    suspend fun waitForTts() {
        var attempts = 0
        while (!ttsReady && attempts < 50) {
            delay(100)
            attempts++
        }
    }

    /**
     * Listens once and returns the best-guess transcription, or null on
     * timeout/error/no-match. The three EXTRA_SPEECH_INPUT_* tunables below
     * are the real sensitivity lever Android's SpeechRecognizer exposes -
     * there's no raw microphone-gain control, but loosening these silence/
     * length thresholds gives quiet or hesitant speech more room before the
     * recognizer decides you're done talking.
     */
    suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var resumed = false

        fun finish(result: String?) {
            if (!resumed) {
                resumed = true
                try { recognizer.destroy() } catch (_: Exception) { }
                if (cont.isActive) cont.resume(result)
            }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = finish(null)
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                finish(matches?.firstOrNull())
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }
        recognizer.startListening(intent)

        cont.invokeOnCancellation {
            try { recognizer.destroy() } catch (_: Exception) { }
        }
    }

    suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        val engine = tts
        if (engine == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        var resumed = false
        fun finish() {
            if (!resumed) {
                resumed = true
                if (cont.isActive) cont.resume(Unit)
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java, still required to override")
            override fun onError(utteranceId: String?) = finish()
        })
        val id = "sterling_${System.currentTimeMillis()}"
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
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
                    SterlingScreen(activity = this, prefs = prefs)
                }
            }
        }
    }
}

@Composable
fun SterlingScreen(activity: ComponentActivity, prefs: SharedPreferences) {
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
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
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

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        status = if (denied.isEmpty()) "mic, contacts + notification permissions granted" else "denied: ${denied.joinToString()}"
        hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Sterling",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Talk to it - no wake word needed while the app is open.",
            color = Color(0xFF9999AA)
        )

        ConversationOrb(
            apiKey = apiKey,
            model = model,
            hasAudioPermission = hasAudioPermission,
            onRequestPermission = { audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        Divider(color = Color(0xFF2A2A3A))
        Text(
            "Advanced: background listening",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "For a wake-word-triggered floating orb that keeps listening even " +
                "when this app is closed, set it up below.",
            color = Color(0xFF9999AA)
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

        Text("Wake word (used only by the background orb below)")
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
                status = "background orb starting - look for a persistent " +
                    "notification and a floating orb on screen to confirm it's running"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("4. Start background orb (wake word)") }

        Button(
            onClick = {
                activity.stopService(Intent(activity, SterlingService::class.java))
                status = "background orb stopped"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Stop background orb") }

        Text("Status: $status")

        Button(
            onClick = {
                val mainCrash = File(activity.filesDir, "main_crash.txt")
                val serviceCrash = File(activity.filesDir, "service_crash.txt")
                val sections = mutableListOf<String>()
                if (mainCrash.exists()) sections.add("=== MAIN APP CRASH ===\n${mainCrash.readText()}")
                if (serviceCrash.exists()) sections.add("=== SERVICE CRASH ===\n${serviceCrash.readText()}")
                crashLog = if (sections.isEmpty()) {
                    "No crash logs found."
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

@Composable
fun ConversationOrb(
    apiKey: String,
    model: String,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val convManager = remember { ConversationManager(context) }

    // idle | listening | thinking | speaking | error
    var state by remember { mutableStateOf("idle") }
    var lastReply by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { convManager.shutdown() }
    }

    // Without this, editing the API key/model while a conversation is
    // already running wouldn't take effect until you stopped and
    // restarted it - the loop below is a long-lived coroutine that would
    // otherwise keep whatever apiKey/model value it captured when it
    // started. rememberUpdatedState keeps it reading the latest value.
    val currentApiKey by rememberUpdatedState(apiKey)
    val currentModel by rememberUpdatedState(model)

    fun startLoop() {
        if (running) return
        running = true
        scope.launch {
            convManager.waitForTts()
            while (running) {
                state = "listening"
                val heard = convManager.listenOnce()
                if (!running) break
                if (heard.isNullOrBlank()) {
                    // No match/timeout this round - just listen again rather
                    // than treating it as an error, since silence is normal.
                    // The small delay avoids hammering the recognizer in a
                    // tight loop on the rare device where it errors instantly
                    // and repeatedly (e.g. no speech-recognition service
                    // available at all).
                    delay(300)
                    continue
                }

                if (currentApiKey.isBlank()) {
                    lastReply = "Please enter and save an OpenRouter API key below first."
                    state = "error"
                    convManager.speak(lastReply)
                    running = false
                    break
                }

                state = "thinking"
                val client = OpenRouterClient(
                    apiKey = currentApiKey,
                    model = currentModel.ifBlank { "meta-llama/llama-3.1-8b-instruct:free" },
                    toolsSchema = Tools.schema(),
                    toolExecutor = { name, args -> Tools.execute(context, name, args) },
                )
                val reply = try {
                    withContext(Dispatchers.IO) { client.chat(heard) }
                } catch (e: Exception) {
                    "Sorry, I ran into a problem: ${e.message}"
                }
                lastReply = reply
                state = "speaking"
                convManager.speak(reply)
            }
            state = "idle"
        }
    }

    fun stopLoop() {
        running = false
        state = "idle"
    }

    // Auto-start the conversation the moment the app opens, provided mic
    // permission is already granted - this is the "no wake word, just talk"
    // behavior. If permission isn't granted yet, the button below prompts
    // for it, and this effect fires again once it's granted.
    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) startLoop()
    }

    val orbColor = when (state) {
        "listening" -> NeonGreen
        "thinking" -> NeonOrange
        "speaking" -> NeonPurple
        "error" -> NeonRed
        else -> NeonBlue
    }
    val pulseDurationMs = when (state) {
        "listening" -> 500
        "thinking" -> 320
        "speaking" -> 380
        "error" -> 220
        else -> 1000
    }

    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb_scale",
    )
    val glowScaleAnim by infiniteTransition.animateFloat(
        initialValue = 1.15f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb_glow",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(glowScaleAnim)
                    .background(orbColor.copy(alpha = 0.25f), shape = CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scaleAnim)
                    .background(orbColor, shape = CircleShape)
            )
        }

        Text(
            when {
                !hasAudioPermission -> "Microphone access needed"
                state == "listening" -> "Listening..."
                state == "thinking" -> "Thinking..."
                state == "speaking" -> "Speaking..."
                state == "error" -> "Something went wrong"
                else -> "Idle"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        if (lastReply.isNotBlank() && state != "listening") {
            Text(
                lastReply,
                color = Color(0xFFCCCCDD),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        if (!hasAudioPermission) {
            Button(onClick = onRequestPermission) { Text("Grant microphone access") }
        } else {
            Button(onClick = { if (running) stopLoop() else startLoop() }) {
                Text(if (running) "Pause conversation" else "Start conversation")
            }
        }
    }
}
