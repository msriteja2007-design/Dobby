package com.yogitha.dobby

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object DobbyShared {
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val BACKEND_URLS = listOf(
        "http://localhost:8000",
        "http://127.0.0.1:8000",
        "http://10.70.177.197:8000"
    )

    val APP_ALIASES = mapOf(
        "spotify" to listOf("com.spotify.music"),
        "whatsapp" to listOf("com.whatsapp"),
        "wa" to listOf("com.whatsapp"),
        "youtube" to listOf("com.google.android.youtube"),
        "chrome" to listOf("com.android.chrome"),
        "google chrome" to listOf("com.android.chrome"),
        "gmail" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "photos" to listOf("com.google.android.apps.photos"),
        "gallery" to listOf("com.google.android.apps.photos"),
        "camera" to listOf("com.motorola.camera3", "com.android.camera2", "com.android.camera"),
        "instagram" to listOf("com.instagram.android"),
        "settings" to listOf("com.android.settings"),
        "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "amazon shopping" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "calendar" to listOf("com.google.android.calendar"),
        "clock" to listOf("com.google.android.deskclock"),
        "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
        "messages" to listOf("com.google.android.apps.messaging"),
        "play store" to listOf("com.android.vending"),
        "google play" to listOf("com.android.vending"),
        "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
        "drive" to listOf("com.google.android.apps.docs"),
        "prime video" to listOf("com.amazon.avod.thirdpartyclient")
    )
}

data class PhoneContact(
    val name: String,
    val number: String,
    val type: Int
)

class VoiceInputManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPhaseChanged: (DobbyPhase) -> Unit,
    private val onHeard: (String) -> Unit = {},
    private val onActionLine: (String) -> Unit = {}
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private var isTtsReady = false
    private var pendingSpeechText: String? = null
    private var pendingListenAfter: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        initTts()
        setupSpeechRecognizer()
    }

    private fun initTts() {
        android.util.Log.d("DobbyTTS", "VoiceInputManager: Initializing TextToSpeech...")
        tts = TextToSpeech(context) { status ->
            android.util.Log.d("DobbyTTS", "VoiceInputManager: onInit status=$status (SUCCESS=0)")
            if (status == TextToSpeech.SUCCESS) {
                var langResult = tts?.setLanguage(Locale.getDefault())
                android.util.Log.d("DobbyTTS", "VoiceInputManager: setLanguage(default=${Locale.getDefault()}) result=$langResult")
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    langResult = tts?.setLanguage(Locale.US)
                    android.util.Log.d("DobbyTTS", "VoiceInputManager: setLanguage(Locale.US) result=$langResult")
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.ENGLISH)
                    }
                }

                try {
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(attrs)
                    android.util.Log.d("DobbyTTS", "VoiceInputManager: AudioAttributes set to USAGE_MEDIA")
                } catch (e: Exception) {
                    android.util.Log.e("DobbyTTS", "VoiceInputManager: setAudioAttributes failed", e)
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        android.util.Log.d("DobbyTTS", "VoiceInputManager: UtteranceProgressListener.onStart utteranceId=$utteranceId")
                        mainHandler.post {
                            onPhaseChanged(DobbyPhase.SPEAKING)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        android.util.Log.d("DobbyTTS", "VoiceInputManager: UtteranceProgressListener.onDone utteranceId=$utteranceId")
                        mainHandler.post {
                            if (utteranceId == "dobby-listen") {
                                mainHandler.postDelayed({ startListening() }, 400)
                            } else {
                                onPhaseChanged(DobbyPhase.DONE)
                            }
                        }
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        android.util.Log.e("DobbyTTS", "VoiceInputManager: UtteranceProgressListener.onError utteranceId=$utteranceId")
                        mainHandler.post {
                            onPhaseChanged(DobbyPhase.ERROR)
                        }
                    }
                })

                isTtsReady = true
                android.util.Log.d("DobbyTTS", "VoiceInputManager: TTS ready")

                pendingSpeechText?.let { text ->
                    val listen = pendingListenAfter
                    pendingSpeechText = null
                    speak(text, listen)
                }
            } else {
                android.util.Log.e("DobbyTTS", "VoiceInputManager: TTS init failed with status=$status")
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    onPhaseChanged(DobbyPhase.ERROR)
                    onActionLine("Couldn't hear anything")
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull() ?: ""
                    
                    if (spokenText.isBlank()) {
                        onPhaseChanged(DobbyPhase.ERROR)
                        onActionLine("Couldn't hear anything")
                        return
                    }

                    onHeard(spokenText)
                    if (handleCallConfirmation(spokenText)) {
                        return
                    }
                    onPhaseChanged(DobbyPhase.THINKING)
                    onActionLine("Sending to Dobby...")

                    processIntent(spokenText)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    fun startListening() {
        if (isListening) return
        
        tts?.stop()
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        try {
            isListening = true
            onPhaseChanged(DobbyPhase.LISTENING)
            onActionLine("Listening...")
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            onPhaseChanged(DobbyPhase.ERROR)
            onActionLine("Speech recognition error")
        }
    }
    
    private fun processIntent(spokenText: String) {
        scope.launch(Dispatchers.IO) {
            try {
                android.util.Log.e("DobbyDiagnostics", "VoiceInputManager: processIntent started for: $spokenText")
                val intentJson = postToBackend("/intent", spokenText)
                android.util.Log.e("DobbyDiagnostics", "VoiceInputManager: backend returned: $intentJson")
                val actionsArray = intentJson.optJSONArray("actions")
                val confirmations = mutableListOf<String>()
                
                if (actionsArray != null && actionsArray.length() > 0) {
                    for (i in 0 until actionsArray.length()) {
                        val actionJson = actionsArray.getJSONObject(i)
                        val intentName = jsonText(actionJson, "intent").lowercase(Locale.getDefault())
                        android.util.Log.e("DobbyDiagnostics", "VoiceInputManager: processing action: $intentName")
                        onActionLine(actionJson.optString("message", intentName))

                        if (intentName == "question") {
                            onPhaseChanged(DobbyPhase.THINKING)
                            onActionLine("Asking Gemini...")
                            val chatJson = postToBackend("/chat", spokenText)
                            confirmations.add(
                                chatJson.optString("reply", "Dobby is speechless for a moment.")
                            )
                        } else {
                            onPhaseChanged(DobbyPhase.ACTING)
                            android.util.Log.e("DobbyDiagnostics", "VoiceInputManager: executing intent: $intentName")
                            val confirmation = executeIntentFromFloating(context, actionJson) { }
                                ?: actionJson.optString("message", "Done.")
                            confirmations.add(confirmation)
                            onActionLine(confirmation)
                        }
                    }
                } else {
                    onPhaseChanged(DobbyPhase.THINKING)
                    onActionLine("Asking Gemini...")
                    val chatJson = postToBackend("/chat", spokenText)
                    confirmations.add(
                        chatJson.optString("reply", "Dobby is speechless for a moment.")
                    )
                }
                
                val dobbyMessage = confirmations.joinToString(" ")
                val listenAfter = CallSession.waitingForConfirmation
                mainHandler.post {
                    onPhaseChanged(DobbyPhase.SPEAKING)
                    speak(dobbyMessage, listenAfter = listenAfter)
                }
            } catch (e: Exception) {
                android.util.Log.e("DobbyDiagnostics", "VoiceInputManager Error: ${e.message}", e)
                onPhaseChanged(DobbyPhase.ERROR)
                val detail = e.message ?: "unknown error"
                onActionLine("Error: $detail")
                val unreachable = detail.contains("Failed to connect", ignoreCase = true) ||
                    detail.contains("Connection refused", ignoreCase = true) ||
                    detail.contains("timed out", ignoreCase = true) ||
                    detail.contains("timeout", ignoreCase = true)
                val dobbyMessage = if (unreachable) {
                    "Dobby couldn't reach the backend. Keep uvicorn running, USB plugged in, then run adb reverse tcp:8000 tcp:8000."
                } else {
                    "Dobby hit a problem: $detail"
                }
                speak(dobbyMessage)
            }
        }
    }

    private fun handleCallConfirmation(spokenText: String): Boolean {
        val pending = CallSession.pendingContact ?: return false
        if (!CallSession.waitingForConfirmation) return false
        val name = pending.first
        val number = pending.second
        when {
            isPositiveConfirmation(spokenText, name) -> {
                CallSession.waitingForConfirmation = false
                CallSession.pendingContact = null
                onPhaseChanged(DobbyPhase.ACTING)
                onActionLine("Calling $name...")
                val callResult = placeCall(context, number)
                onPhaseChanged(DobbyPhase.SPEAKING)
                speak(callResult)
            }
            isNegativeConfirmation(spokenText) -> {
                CallSession.waitingForConfirmation = false
                CallSession.pendingContact = null
                val message = "Okay, I won't call $name."
                onActionLine("Call cancelled")
                onPhaseChanged(DobbyPhase.SPEAKING)
                speak(message)
            }
            else -> {
                CallSession.waitingForConfirmation = true
                val message = "Do you want me to call $name? Please say yes or no."
                onActionLine("Please confirm")
                onPhaseChanged(DobbyPhase.SPEAKING)
                speak(message, listenAfter = true)
            }
        }
        return true
    }

    fun speak(text: String, listenAfter: Boolean = false) {
        val diagnosticText = "Dobby is working. $text"
        android.util.Log.d("DobbyTTS", "VoiceInputManager: speak() called with text='$diagnosticText', isTtsReady=$isTtsReady")

        if (text.isBlank()) {
            onPhaseChanged(DobbyPhase.DONE)
            return
        }

        if (!isTtsReady || tts == null) {
            android.util.Log.w("DobbyTTS", "VoiceInputManager: TTS not ready yet, queuing text: '$diagnosticText'")
            pendingSpeechText = diagnosticText
            pendingListenAfter = listenAfter
            return
        }

        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) {
                val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                android.util.Log.d("DobbyTTS", "VoiceInputManager: STREAM_MUSIC volume = $vol / $maxVol")
                if (vol == 0) {
                    val newVol = (maxVol * 0.6f).toInt().coerceAtLeast(1)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVol, 0)
                    android.util.Log.d("DobbyTTS", "VoiceInputManager: unmuted STREAM_MUSIC to $newVol")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DobbyTTS", "VoiceInputManager: volume check failed", e)
        }

        val utteranceId = if (listenAfter) "dobby-listen" else "dobby"
        val params = Bundle()
        val result = tts?.speak(diagnosticText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        android.util.Log.d("DobbyTTS", "VoiceInputManager: tts.speak() returned $result (0=SUCCESS, -1=ERROR)")
        if (result != TextToSpeech.SUCCESS) {
            android.util.Log.e("DobbyTTS", "VoiceInputManager: tts.speak() failed with code $result")
            onPhaseChanged(DobbyPhase.ERROR)
        }
    }

    fun stop() {
        speechRecognizer?.stopListening()
        tts?.stop()
    }

    fun destroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
    }

    private suspend fun postToBackend(path: String, message: String): JSONObject = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().put("message", message).toString()
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        var lastConnectionError: IOException? = null

        for (base in DobbyShared.BACKEND_URLS) {
            try {
                val request = Request.Builder()
                    .url("$base$path")
                    .post(body)
                    .build()

                val response = DobbyShared.httpClient.newCall(request).execute()
                val responseText = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    throw Exception("Backend error: $responseText")
                }

                return@withContext JSONObject(responseText)
            } catch (e: IOException) {
                lastConnectionError = e
            }
        }

        throw lastConnectionError ?: Exception("Could not reach Dobby backend")
    }

    private fun jsonText(json: JSONObject, key: String): String {
        if (!json.has(key) || json.isNull(key)) return ""
        return json.optString(key).trim()
    }

    private fun optionalValue(intentJson: JSONObject): Int? {
        if (intentJson.has("value") && !intentJson.isNull("value")) return intentJson.optInt("value")
        if (intentJson.has("level") && !intentJson.isNull("level")) return intentJson.optInt("level")
        return null
    }

    private fun hasWord(haystack: String, word: String): Boolean {
        return Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
    }

    private fun isPositiveConfirmation(response: String, pendingName: String = ""): Boolean {
        val text = response.trim()
        if (hasWord(text, "yes") || hasWord(text, "yeah") || hasWord(text, "yep") ||
            hasWord(text, "sure") || hasWord(text, "confirm") ||
            text.contains("do it", ignoreCase = true) ||
            text.contains("go ahead", ignoreCase = true) ||
            text.contains("please call", ignoreCase = true)
        ) {
            return true
        }
        if (pendingName.isNotBlank() &&
            hasWord(text, "call") &&
            text.contains(pendingName, ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun isNegativeConfirmation(response: String): Boolean {
        val text = response.trim()
        return hasWord(text, "no") ||
            hasWord(text, "nope") ||
            hasWord(text, "cancel") ||
            hasWord(text, "stop") ||
            text.contains("don't", ignoreCase = true) ||
            text.contains("dont", ignoreCase = true) ||
            text.contains("do not", ignoreCase = true)
    }

    private suspend fun executeIntentFromFloating(context: Context, intentJson: JSONObject, addToHistory: (String) -> Unit): String? {
        val intentString = intentJson.optString("intent").lowercase(Locale.getDefault())
        android.util.Log.e("DobbyDiagnostics", "executeIntentFromFloating called with intent: $intentString")
        return when (intentString) {
            "open_app" -> openApp(context, jsonText(intentJson, "app"))
            "play_song" -> playSpotifySong(context, jsonText(intentJson, "query"))
            "amazon_search" -> searchAmazon(context, jsonText(intentJson, "query"))
            "amazon_add_to_cart" -> withContext(Dispatchers.IO) {
                amazonUiAction(context, DobbyAccessibilityService.ACTION_ADD_CART, addToHistory)
            }
            "amazon_open_settings" -> withContext(Dispatchers.IO) {
                amazonUiAction(context, DobbyAccessibilityService.ACTION_SETTINGS, addToHistory)
            }
            "toggle_wifi" -> openPhoneSetting(context, "wifi")
            "toggle_bluetooth" -> openPhoneSetting(context, "bluetooth")
            "flashlight" -> toggleFlashlight(context, if (intentJson.optBoolean("enabled", true)) "on" else "off")
            "open_settings" -> openPhoneSetting(context, jsonText(intentJson, "setting"))
            "set_brightness" -> setBrightness(context, optionalValue(intentJson), "")
            "increase_brightness" -> setBrightness(context, null, "up")
            "decrease_brightness" -> setBrightness(context, null, "down")
            "set_volume" -> setVolume(context, optionalValue(intentJson), "")
            "increase_volume" -> setVolume(context, null, "up")
            "decrease_volume" -> setVolume(context, null, "down")
            "mute_volume" -> setVolume(context, null, "mute")
            "call_contact" -> handleCallContact(
                context,
                jsonText(intentJson, "contact").ifBlank { jsonText(intentJson, "query") },
                addToHistory
            )
            "unknown" -> null
            else -> {
                android.widget.Toast.makeText(context, "Dobby isn't sure how to do that yet", android.widget.Toast.LENGTH_SHORT).show()
                "Dobby isn't sure how to do that yet"
            }
        }
    }

    private fun amazonUiAction(context: Context, action: String, addToHistory: (String) -> Unit): String {
        android.util.Log.e("DobbyDiagnostics", "amazonUiAction called with action: $action, accessibilityEnabled: ${accessibilityEnabled(context)}")
        if (!accessibilityEnabled(context)) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            addToHistory("⚠️ Accessibility service not enabled")
            return "Turn on Dobby in Accessibility settings, open the Amazon product, then say add to cart again. Nothing was added."
        }
        addToHistory("🔍 Looking for Amazon UI element...")
        val result = DobbyAccessibilityService.request(action)
        if (result.contains("Added the open product", ignoreCase = true) ||
            result.contains("Tapped", ignoreCase = true)
        ) {
            addToHistory("✓ Successfully tapped element")
        } else {
            addToHistory("✗ Failed to tap element")
        }
        return result
    }

    private fun accessibilityEnabled(context: Context): Boolean {
        if (DobbyAccessibilityService.isConnected()) return true
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    private fun openApp(context: Context, appName: String): String {
        val spoken = normalizeAppName(appName)
        if (spoken.isEmpty()) {
            return "Tell Dobby which app to open."
        }

        val aliasPackages = DobbyShared.APP_ALIASES[spoken] ?: emptyList()
        for (aliasPackage in aliasPackages) {
            val aliasIntent = context.packageManager.getLaunchIntentForPackage(aliasPackage)
            if (aliasIntent != null) {
                context.startActivity(aliasIntent)
                return "Opening $spoken."
            }
        }

        val launchIntent = findInstalledApp(context, spoken)
        return if (launchIntent != null) {
            context.startActivity(launchIntent)
            "Opening $spoken."
        } else {
            android.widget.Toast.makeText(context, "$spoken is not installed on this phone", android.widget.Toast.LENGTH_LONG).show()
            "$spoken is not installed on this phone"
        }
    }

    private fun normalizeAppName(name: String): String {
        return name.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 +]"), " ")
            .replace(Regex("\\b(the|app|application|please|dobby)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findInstalledApp(context: Context, spoken: String): Intent? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(
            launcher,
            android.content.pm.PackageManager.ResolveInfoFlags.of(0)
        )

        val scored = apps.mapNotNull { info ->
            val label = info.loadLabel(pm).toString().lowercase(Locale.getDefault())
            val pkg = info.activityInfo.packageName.lowercase(Locale.getDefault())
            val score = when {
                label == spoken -> 100
                label.startsWith(spoken) -> 80
                spoken.contains(label) && label.length > 3 -> 75
                pkg.contains(spoken.replace(" ", "")) -> 70
                label.contains(spoken) -> 60
                spoken.split(" ").all { it.isNotBlank() && label.contains(it) } -> 50
                else -> 0
            }
            if (score == 0) null else Triple(score, label.length, info)
        }.sortedWith(compareByDescending<Triple<Int, Int, android.content.pm.ResolveInfo>> { it.first }
            .thenBy { it.second })

        val best = scored.firstOrNull()?.third ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(best.activityInfo.packageName, best.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun playSpotifySong(context: Context, query: String): String {
        val song = query.trim()
            .replace(Regex("(?i)\\s+on\\s+(the\\s+)?spotify( app)?$"), "")
            .trim()
        if (song.isEmpty() || song.equals("null", ignoreCase = true)) {
            return "Tell Dobby the song name to play."
        }

        val playIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(android.app.SearchManager.QUERY, song)
            putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, song)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(playIntent)
            return "Asked Spotify to play $song."
        }

        val searchUri = android.net.Uri.parse("spotify:search:${android.net.Uri.encode(song)}")
        val searchIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            setPackage("com.spotify.music")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (searchIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(searchIntent)
            "Opened Spotify search for $song. Tap the song if it does not start."
        } else {
            android.widget.Toast.makeText(context, "Spotify is not installed", android.widget.Toast.LENGTH_LONG).show()
            "Spotify is not installed on this phone"
        }
    }

    private fun openPhoneSetting(context: Context, setting: String): String {
        val key = setting.trim().lowercase(Locale.getDefault())
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val panel = when (key) {
                "wifi" -> android.provider.Settings.Panel.ACTION_WIFI
                "bluetooth" -> null /* Panel doesn't exist, fallback to general */
                "sound", "volume" -> android.provider.Settings.Panel.ACTION_VOLUME
                else -> null
            }
            if (panel != null) {
                try {
                    context.startActivity(Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return when (key) {
                        "wifi" -> "Opened the Wi-Fi panel. Turn it on or off here. Dobby cannot silently toggle Wi-Fi."
                        "bluetooth" -> "Opened the Bluetooth panel. Turn it on or off here."
                        else -> "Opened the volume panel."
                    }
                } catch (_: Exception) {
                    // Fall through to full settings screens.
                }
            }
        }

        val actions = when (key) {
            "wifi" -> listOf("android.settings.WIFI_SETTINGS")
            "bluetooth" -> listOf("android.settings.BLUETOOTH_SETTINGS")
            "hotspot" -> listOf(
                "com.android.settings.WIFI_TETHER_SETTINGS",
                "android.settings.WIRELESS_SETTINGS"
            )
            "sound", "volume" -> listOf("android.settings.SOUND_SETTINGS")
            "display" -> listOf("android.settings.DISPLAY_SETTINGS")
            "location" -> listOf("android.settings.LOCATION_SOURCE_SETTINGS")
            else -> listOf("android.settings.SETTINGS")
        }

        for (action in actions) {
            try {
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return when (key) {
                    "wifi" -> "Opened Wi-Fi settings. Turn it on or off from this screen."
                    "bluetooth" -> "Opened Bluetooth settings. Turn it on or off from this screen."
                    "hotspot" -> "Opened hotspot settings. You can turn it on and show the QR code here."
                    "sound", "volume" -> "Opened sound settings."
                    "display" -> "Opened display settings."
                    "location" -> "Opened location settings."
                    else -> "Opened phone settings."
                }
            } catch (_: Exception) {
                // Try the next settings screen.
            }
        }

        context.startActivity(
            Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Opened phone settings."
    }

    private fun searchAmazon(context: Context, query: String): String {
        val product = query.trim()
        if (product.isEmpty() || product.equals("null", ignoreCase = true)) {
            return openApp(context, "amazon")
        }

        val encoded = android.net.Uri.encode(product)
        val searchUris = listOf(
            "https://www.amazon.in/s?k=$encoded",
            "https://www.amazon.com/s?k=$encoded"
        )
        val amazonPackages = listOf(
            "in.amazon.mShop.android.shopping",
            "com.amazon.mShop.android.shopping"
        )

        for (pkg in amazonPackages) {
            for (uri in searchUris) {
                val appSearch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (appSearch.resolveActivity(context.packageManager) != null) {
                    context.startActivity(appSearch)
                    return "Searching Amazon for $product. Pick the item, then say add this to my cart."
                }
            }
        }

        val browserSearch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(searchUris.first())).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserSearch)
        return "Searching Amazon for $product. Pick the item, then say add this to my cart."
    }

    private fun toggleFlashlight(context: Context, state: String): String {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            val isTorchOn = state != "off"
            cameraManager.setTorchMode(cameraId, isTorchOn)
            return "Turned flashlight ${if (isTorchOn) "on" else "off"}."
        } catch (_: Exception) {
            return "Could not change the flashlight."
        }
    }

    private fun setBrightness(context: Context, level: Int?, state: String): String {
        if (!android.provider.Settings.System.canWrite(context)) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = android.net.Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Allow Dobby to change system settings, then ask again. Brightness was not changed."
        }
        val current = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
        val currentPct = (current * 100) / 255
        val targetPct = when {
            level != null -> level.coerceIn(0, 100)
            state == "up" -> (currentPct + 20).coerceAtMost(100)
            state == "down" -> (currentPct - 20).coerceAtLeast(0)
            else -> 50
        }
        val brightness = (targetPct / 100f * 255).toInt().coerceIn(0, 255)
        android.provider.Settings.System.putInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
            brightness
        )
        return "Set brightness to $targetPct%."
    }

    private fun setVolume(context: Context, level: Int?, state: String): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val target = when {
                level != null -> (maxVolume * level.coerceIn(0, 100) / 100f).toInt().coerceIn(0, maxVolume)
                state == "mute" -> 0
                state == "up" -> (current + (maxVolume / 5).coerceAtLeast(1)).coerceAtMost(maxVolume)
                state == "down" -> (current - (maxVolume / 5).coerceAtLeast(1)).coerceAtLeast(0)
                else -> maxVolume / 2
            }
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                target,
                0
            )
            val percent = if (maxVolume == 0) 0 else (target * 100) / maxVolume
            return "Set volume to $percent%."
        } catch (_: Exception) {
            return "Could not set volume."
        }
    }

    private fun handleCallContact(context: Context, contactName: String, addToHistory: (String) -> Unit): String {
        if (contactName.isBlank()) {
            return "Tell Dobby who to call."
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            addToHistory("⚠️ Contacts permission needed")
            return "Dobby needs contacts permission to find people. Open Dobby and grant contacts permission, then try again."
        }
        val (matches, conflict) = resolveCallContact(context, contactName.trim())
        if (matches.isEmpty()) {
            addToHistory("⚠️ Contact not found: $contactName")
            CallSession.waitingForConfirmation = false
            CallSession.pendingContact = null
            return "Dobby couldn't find $contactName in your contacts."
        }
        if (conflict != null) {
            addToHistory("⚠️ Multiple contacts found: $conflict")
            CallSession.waitingForConfirmation = false
            CallSession.pendingContact = null
            return "Dobby found more than one match: $conflict. Please say the exact contact name."
        }
        val contact = matches.first()
        CallSession.pendingContact = Pair(contact.name, contact.number)
        CallSession.waitingForConfirmation = true
        addToHistory("📞 Found contact: ${contact.name}")
        return "Do you want me to call ${contact.name}?"
    }

    private fun resolveCallContact(context: Context, spokenName: String): Pair<List<PhoneContact>, String?> {
        val hits = findContactMatches(context, spokenName)
        if (hits.isEmpty()) return emptyList<PhoneContact>() to null
        val exact = hits.filter { it.name.equals(spokenName, ignoreCase = true) }
        val pool = exact.ifEmpty {
            hits.filter { it.name.startsWith(spokenName, ignoreCase = true) }.ifEmpty { hits }
        }
        val uniqueNames = pool.map { it.name }.distinctBy { it.lowercase(Locale.getDefault()) }
        if (uniqueNames.size > 1) {
            return pool to uniqueNames.take(3).joinToString(", ")
        }
        val uniqueNumbers = pool.distinctBy { it.number.filter(Char::isDigit) }
        if (uniqueNumbers.size == 1) return uniqueNumbers to null
        val mobile = uniqueNumbers.filter { it.type == android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }
        if (mobile.size == 1) return mobile to null
        return uniqueNumbers to uniqueNumbers.joinToString(", ") { "${it.name} ${it.number}" }
    }

    private fun findContactMatches(context: Context, name: String): List<PhoneContact> {
        val spoken = name.trim()
        if (spoken.isBlank()) return emptyList()
        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$spoken%")
        val hits = mutableListOf<PhoneContact>()
        val cursor: android.database.Cursor? = context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE
            ),
            selection,
            selectionArgs,
            null
        )
        cursor?.use {
            val nameIdx = it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE)
            while (it.moveToNext()) {
                val displayName = it.getString(nameIdx) ?: continue
                val phoneNumber = it.getString(numberIdx) ?: continue
                if (displayName.isBlank() || phoneNumber.isBlank()) continue
                hits.add(PhoneContact(displayName, phoneNumber, it.getInt(typeIdx)))
            }
        }
        return hits.distinctBy { it.name.lowercase(Locale.getDefault()) + "|" + it.number.filter(Char::isDigit) }
    }

    private fun placeCall(context: Context, phoneNumber: String): String {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Dobby needs phone permission to make calls. Open Dobby and grant phone permission, then try again."
        }
        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            "Calling $phoneNumber."
        } catch (e: Exception) {
            "Could not place the call: ${e.message}"
        }
    }
}
