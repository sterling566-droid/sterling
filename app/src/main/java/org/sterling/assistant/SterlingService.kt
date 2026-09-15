package org.sterling.assistant

/*
 * The background service. Runs as an Android foreground service (declared
 * in AndroidManifest.xml with foregroundServiceType="microphone") so it
 * keeps running while the app is minimized. It:
 *   1. Draws a small glowing, pulsing orb overlay (WindowManager).
 *   2. Loops Android's native SpeechRecognizer (Google's speech engine)
 *      listening for the wake word.
 *   3. On wake word: listens for the question, sends it to OpenRouter
 *      (which can call back into local tools), and speaks the reply with
 *      Android's built-in TextToSpeech engine.
 *   4. Goes back to listening.
 *
 * Any uncaught exception on any thread gets written to service_crash.txt
 * in the app's internal storage - viewable from the main app's "View
 * crash logs" button, no adb needed. Note this can only catch genuine
 * Java/Kotlin exceptions, not an external OS-level kill (e.g. the phone
 * force-stopping the process directly) - that kind of kill leaves no
 * catchable exception to log, by definition.
 */

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlin.coroutines.resume

class SterlingService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager

    private var bubbleView: TextView? = null
    private var innerShape: GradientDrawable? = null
    private var outerShape: GradientDrawable? = null
    private var tts: TextToSpeech? = null
    private var wakeWord = "hey sterling"
    private lateinit var client: OpenRouterClient
    private var running = false

    companion object {
        const val CHANNEL_ID = "sterling_service"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()

        // Catch crashes on every thread, not just this one - Android's
        // default handler would otherwise just silently kill the process.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(filesDir, "service_crash.txt").writeText(
                    "Service crashed at ${LocalDateTime.now()} on thread ${thread.name}\n\n" +
                        throwable.stackTraceToString()
                )
            } catch (_: Exception) {
                // Nothing more we can do if even writing the crash log fails.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        prefs = getSharedPreferences("sterling_config", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", "meta-llama/llama-3.1-8b-instruct:free")
            ?: "meta-llama/llama-3.1-8b-instruct:free"
        wakeWord = (prefs.getString("wake_word", "hey sterling") ?: "hey sterling").lowercase()

        client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            toolsSchema = Tools.schema(),
            toolExecutor = { name, args -> Tools.execute(this, name, args) }
        )

        tts = TextToSpeech(this, this)

        startForegroundServiceNotification()
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            serviceScope.launch { mainLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    override fun onDestroy() {
        running = false
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        removeBubble()
        super.onDestroy()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Sterling", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sterling is listening")
            .setContentText("Say \"$wakeWord\" to ask a question")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------
    // Bubble UI: glowing orb with a state-colored core, a translucent halo,
    // and a continuous pulsing "breathing" animation whose speed reflects
    // how active Sterling currently is.
    // ---------------------------------------------------------------------

    private val stateColors = mapOf(
        "idle" to "#3366FF",     // blue: waiting for wake word
        "awake" to "#33CC66",    // green: heard wake word, listening for command
        "thinking" to "#FFAA00", // orange: waiting on OpenRouter / running a tool
        "speaking" to "#CC33CC", // purple: speaking the reply
        "error" to "#CC3333",   // red: something went wrong
    )

    private val pulseDurations = mapOf(
        "idle" to 900L, "awake" to 400L, "thinking" to 300L, "speaking" to 350L, "error" to 200L,
    )

    private fun glowColor(state: String): String {
        val base = stateColors[state]?.removePrefix("#") ?: "3366FF"
        return "#55$base" // ~33% opacity halo in the same hue as the core
    }

    private fun createBubble() {
        val size = 150
        val overlayType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        val tv = TextView(this).apply {
            text = "S"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }

        val inner = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(stateColors["idle"]))
        }
        innerShape = inner

        try {
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(glowColor("idle")))
            }
            outerShape = outer
            val layered = LayerDrawable(arrayOf(outer, inner))
            val inset = size / 5
            layered.setLayerInset(0, 0, 0, 0, 0)
            layered.setLayerInset(1, inset, inset, inset, inset)
            tv.background = layered
        } catch (e: Exception) {
            // Glow is cosmetic - fall back to the plain solid core if it fails.
            tv.background = inner
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        tv.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - touchX).toInt()
                    layoutParams.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, layoutParams)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(tv, layoutParams)
        bubbleView = tv
        applyPulse(tv, "idle")
    }

    private fun applyPulse(view: TextView, state: String) {
        val duration = pulseDurations[state] ?: 900L
        val anim = ScaleAnimation(
            1f, 1.18f, 1f, 1.18f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            this.duration = duration
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        view.startAnimation(anim)
    }

    private fun setBubbleState(state: String) {
        val view = bubbleView ?: return
        view.post {
            innerShape?.setColor(Color.parseColor(stateColors[state] ?: stateColors["idle"]))
            try {
                outerShape?.setColor(Color.parseColor(glowColor(state)))
            } catch (_: Exception) {
            }
            applyPulse(view, state)
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try {
                it.clearAnimation()
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
    }

    // ---------------------------------------------------------------------
    // Speech recognition (Android's native SpeechRecognizer - Google's
    // speech engine via Android's own API). Must run on the main thread.
    // ---------------------------------------------------------------------

    private suspend fun listenOnce(): List<String> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this@SterlingService)
            var finished = false

            fun finish(result: List<String>) {
                if (finished) return
                finished = true
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                if (cont.isActive) cont.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) = finish(emptyList())
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?: arrayListOf()
                    finish(matches)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Android's SpeechRecognizer has no direct microphone-gain
                // control, but loosening these silence/length thresholds
                // gives quiet or hesitant speech more room before the
                // recognizer decides you're done talking.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            }
            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Text-to-speech via Android's built-in TextToSpeech engine (most
    // devices default this to Google's TTS engine). Using the OS's own
    // audio framework here, rather than manually downloading and playing
    // an mp3, is also the more robust choice under Android 17's new
    // background-audio-hardening rules, since it properly requests audio
    // focus through the blessed system API rather than a raw MediaPlayer.
    // ---------------------------------------------------------------------

    private suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        val engine = tts
        if (engine == null) {
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }

        val utteranceId = "sterling_${System.currentTimeMillis()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (cont.isActive) cont.resume(Unit)
            }
        })

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            if (cont.isActive) cont.resume(Unit)
        }
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    private suspend fun mainLoop() {
        setBubbleState("idle")
        while (running) {
            try {
                val texts = listenOnce()
                val heard = texts.joinToString(" ") { it.lowercase() }

                if (heard.contains(wakeWord)) {
                    setBubbleState("awake")
                    val questionTexts = listenOnce()
                    val question = questionTexts.firstOrNull()?.trim().orEmpty()

                    if (question.isEmpty()) {
                        setBubbleState("idle")
                        continue
                    }

                    setBubbleState("thinking")
                    val reply = try {
                        client.chat(question)
                    } catch (e: OpenRouterException) {
                        "Sorry, I ran into a problem: ${e.message}"
                    }

                    setBubbleState("speaking")
                    try {
                        speak(reply)
                    } catch (_: Exception) {
                    }

                    if (running) setBubbleState("idle")
                } else {
                    delay(300)
                }
            } catch (e: Exception) {
                setBubbleState("error")
                delay(2000)
                if (running) setBubbleState("idle")
            }
        }
    }
}
