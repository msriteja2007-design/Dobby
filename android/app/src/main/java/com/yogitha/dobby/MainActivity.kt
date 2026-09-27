package com.yogitha.dobby

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yogitha.dobby.ui.theme.DobbyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class DobbyPhase {
    LISTENING, THINKING, ACTING, SPEAKING, DONE, ERROR, TEACHING
}

object CallSession {
    @Volatile var pendingContact: Pair<String, String>? = null
    @Volatile var waitingForConfirmation: Boolean = false
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null
    private val ttsBusy = mutableStateOf(false)
    
    private val floatingServiceRunning = mutableStateOf(false)
    private val dobbyHistory = mutableStateOf(listOf<String>())
    private val isTeachingMode = mutableStateOf(false)
    private val currentWorkflowName = mutableStateOf("")
    
    // Floating activation state
    var activateVoiceFromFloating = false
    var isHeadlessMode = false
    
    private val floatingActivationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingDobbyService.ACTION_ACTIVATE) {
                // Activate voice in headless mode - bring to foreground but minimize UI
                activateVoiceFromFloating = true
                isHeadlessMode = true
                val mainIntent = Intent(context, MainActivity::class.java)
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                mainIntent.putExtra("activate_voice", true)
                mainIntent.putExtra("headless", true)
                context?.startActivity(mainIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register receiver for floating icon activation
        registerReceiver(
            floatingActivationReceiver,
            IntentFilter(FloatingDobbyService.ACTION_ACTIVATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        
        initTts()
        enableEdgeToEdge()
        setContent {
            DobbyTheme {
                DobbyScreen(
                    speak = { text -> speak(text) },
                    stopSpeaking = { tts?.stop() },
                    ttsBusy = ttsBusy.value,
                    floatingServiceRunning = floatingServiceRunning.value,
                    startFloatingService = { startFloatingService() },
                    stopFloatingService = { stopFloatingService() },
                    dobbyHistory = dobbyHistory.value,
                    addToHistory = { item -> addToHistory(item) },
                    updateFloatingIconState = { phase -> updateFloatingIconState(phase) },
                    isTeachingMode = isTeachingMode.value,
                    startTeaching = { name -> startTeachingMode(name) },
                    stopTeaching = { stopTeachingMode() },
                    currentWorkflowName = currentWorkflowName.value
                )
            }
        }
        
        // Check if floating service is already running
        floatingServiceRunning.value = FloatingDobbyService.isRunning()
        requestCallPermissionsIfNeeded()
        
        // Load saved workflows
        lifecycleScope.launch {
            WorkflowManager.loadWorkflows(this@MainActivity)
        }
    }

    private fun requestCallPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 42)
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("activate_voice", false)) {
            activateVoiceFromFloating = true
        }
    }
    
    override fun onDestroy() {
        unregisterReceiver(floatingActivationReceiver)
        if (tts?.isSpeaking == false) {
            tts?.stop()
            tts?.shutdown()
        }
        super.onDestroy()
    }
    
    private fun startFloatingService() {
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        val intent = Intent(this, FloatingDobbyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        floatingServiceRunning.value = true
        addToHistory("Started floating Dobby")
    }
    
    private fun stopFloatingService() {
        val intent = Intent(this, FloatingDobbyService::class.java)
        intent.action = FloatingDobbyService.ACTION_STOP
        startService(intent)
        floatingServiceRunning.value = false
        addToHistory("Stopped floating Dobby")
    }
    
    private fun hasOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this)
        }
        return true
    }
    
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant overlay permission for Dobby", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun addToHistory(item: String) {
        val newHistory = mutableListOf(item) + dobbyHistory.value
        dobbyHistory.value = newHistory.take(20) // Keep last 20 items
    }
    
    private fun updateFloatingIconState(phase: DobbyPhase) {
        FloatingDobbyService.getInstance()?.updateIconState(phase)
    }

    private fun initTts() {
        android.util.Log.d("DobbyTTS", "MainActivity: Initializing TextToSpeech...")
        tts = TextToSpeech(this) { status ->
            android.util.Log.d("DobbyTTS", "MainActivity: onInit status=$status (SUCCESS=0)")
            if (status == TextToSpeech.SUCCESS) {
                var langResult = tts?.setLanguage(Locale.getDefault())
                android.util.Log.d("DobbyTTS", "MainActivity: setLanguage(default=${Locale.getDefault()}) result=$langResult")
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    langResult = tts?.setLanguage(Locale.US)
                    android.util.Log.d("DobbyTTS", "MainActivity: setLanguage(Locale.US) result=$langResult")
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
                    android.util.Log.d("DobbyTTS", "MainActivity: AudioAttributes set to USAGE_MEDIA")
                } catch (e: Exception) {
                    android.util.Log.e("DobbyTTS", "MainActivity: setAudioAttributes failed", e)
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        android.util.Log.d("DobbyTTS", "MainActivity: UtteranceProgressListener.onStart utteranceId=$utteranceId")
                        ttsBusy.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        android.util.Log.d("DobbyTTS", "MainActivity: UtteranceProgressListener.onDone utteranceId=$utteranceId")
                        ttsBusy.value = false
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        android.util.Log.e("DobbyTTS", "MainActivity: UtteranceProgressListener.onError utteranceId=$utteranceId")
                        ttsBusy.value = false
                    }
                })

                isTtsReady = true
                android.util.Log.d("DobbyTTS", "MainActivity: TTS ready")

                pendingSpeechText?.let { text ->
                    pendingSpeechText = null
                    speak(text)
                }
            } else {
                android.util.Log.e("DobbyTTS", "MainActivity: TTS init failed with status=$status")
            }
        }
    }

    private fun speak(text: String) {
        val diagnosticText = "Dobby is working. $text"
        android.util.Log.d("DobbyTTS", "MainActivity: speak() called with text='$diagnosticText', isTtsReady=$isTtsReady")
        if (text.isBlank()) return

        if (!isTtsReady || tts == null) {
            android.util.Log.w("DobbyTTS", "MainActivity: TTS not ready yet, queuing text: '$diagnosticText'")
            pendingSpeechText = diagnosticText
            return
        }

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                android.util.Log.d("DobbyTTS", "MainActivity: STREAM_MUSIC volume = $vol / $maxVol")
                if (vol == 0) {
                    val newVol = (maxVol * 0.6f).toInt().coerceAtLeast(1)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    android.util.Log.d("DobbyTTS", "MainActivity: unmuted STREAM_MUSIC to $newVol")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DobbyTTS", "MainActivity: volume check failed", e)
        }

        val params = Bundle()
        val result = tts?.speak(diagnosticText, TextToSpeech.QUEUE_FLUSH, params, "dobby")
        android.util.Log.d("DobbyTTS", "MainActivity: tts.speak() returned $result (0=SUCCESS, -1=ERROR)")
        if (result == TextToSpeech.SUCCESS) {
            ttsBusy.value = true
        } else {
            android.util.Log.e("DobbyTTS", "MainActivity: tts.speak() failed with code $result")
            ttsBusy.value = false
        }
    }
    
    private fun startTeachingMode(workflowName: String) {
        isTeachingMode.value = true
        currentWorkflowName.value = workflowName
        WorkflowManager.startTeaching(workflowName)
        addToHistory("🎓 Teaching mode: $workflowName")
        speak("Dobby is watching. Please demonstrate the workflow.")
    }
    
    private fun stopTeachingMode() {
        val workflow = WorkflowManager.stopTeaching()
        isTeachingMode.value = false
        currentWorkflowName.value = ""
        
        if (workflow != null) {
            addToHistory("✓ Learned workflow: ${workflow.name}")
            speak("Dobby has learned this workflow!")
            
            // Save workflows
            lifecycleScope.launch {
                WorkflowManager.saveWorkflows(this@MainActivity)
            }
        } else {
            addToHistory("⚠️ No workflow learned")
            speak("Dobby didn't learn anything this time.")
        }
    }
}

fun findContactMatches(context: Context, name: String): List<PhoneContact> {
    val spoken = name.trim()
    if (spoken.isBlank()) return emptyList()
    val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
    val selectionArgs = arrayOf("%$spoken%")
    val hits = mutableListOf<PhoneContact>()
    val cursor: Cursor? = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        ),
        selection,
        selectionArgs,
        null
    )
    cursor?.use {
        val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val typeIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
        while (it.moveToNext()) {
            val displayName = it.getString(nameIdx) ?: continue
            val phoneNumber = it.getString(numberIdx) ?: continue
            if (displayName.isBlank() || phoneNumber.isBlank()) continue
            hits.add(PhoneContact(displayName, phoneNumber, it.getInt(typeIdx)))
        }
    }
    return hits.distinctBy { it.name.lowercase(Locale.getDefault()) + "|" + it.number.filter(Char::isDigit) }
}

fun resolveCallContact(context: Context, spokenName: String): Pair<List<PhoneContact>, String?> {
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
    val mobile = uniqueNumbers.filter { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }
    if (mobile.size == 1) return mobile to null
    return uniqueNumbers to uniqueNumbers.joinToString(", ") { "${it.name} ${it.number}" }
}

fun handleCallContact(context: Context, contactName: String, addToHistory: (String) -> Unit): String {
    if (contactName.isBlank()) {
        return "Tell Dobby who to call."
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
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

fun placeCall(context: Context, phoneNumber: String): String {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        return "Dobby needs phone permission to make calls. Open Dobby and grant phone permission, then try again."
    }
    return try {
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(callIntent)
        "Calling $phoneNumber."
    } catch (e: Exception) {
        "Could not place the call: ${e.message}"
    }
}

fun hasWord(haystack: String, word: String): Boolean {
    return Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
}

fun isPositiveConfirmation(response: String, pendingName: String = ""): Boolean {
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

fun isNegativeConfirmation(response: String): Boolean {
    val text = response.trim()
    return hasWord(text, "no") ||
        hasWord(text, "nope") ||
        hasWord(text, "cancel") ||
        hasWord(text, "stop") ||
        text.contains("don't", ignoreCase = true) ||
        text.contains("dont", ignoreCase = true) ||
        text.contains("do not", ignoreCase = true)
}



@Composable
fun DobbyScreen(
    speak: (String) -> Unit,
    stopSpeaking: () -> Unit,
    ttsBusy: Boolean,
    floatingServiceRunning: Boolean,
    startFloatingService: () -> Unit,
    stopFloatingService: () -> Unit,
    dobbyHistory: List<String>,
    addToHistory: (String) -> Unit,
    updateFloatingIconState: (DobbyPhase) -> Unit,
    isTeachingMode: Boolean = false,
    startTeaching: (String) -> Unit = {},
    stopTeaching: () -> Unit = {},
    currentWorkflowName: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var heard by remember { mutableStateOf("") }
    var understanding by remember { mutableStateOf("Idle") }
    var actionLine by remember { mutableStateOf("Tap the mic") }
    var userWants by remember { mutableStateOf("") }
    var currentTask by remember { mutableStateOf("Idle") }
    var learnedLine by remember { mutableStateOf("None yet") }
    var dobbyMessage by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(DobbyPhase.DONE) }
    var isLoading by remember { mutableStateOf(false) }
    var localIsTeachingMode by remember { mutableStateOf(isTeachingMode) }
    var localWorkflowName by remember { mutableStateOf(currentWorkflowName) }

    val shownPhase = if (ttsBusy && phase != DobbyPhase.ERROR) DobbyPhase.SPEAKING else phase
    
    // Update floating icon state when phase changes
    androidx.compose.runtime.LaunchedEffect(shownPhase) {
        updateFloatingIconState(shownPhase)
    }
    
    // Check if activated from floating icon
    var activateFromFloating by remember { mutableStateOf(false) }
    
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val activity = context as MainActivity
        if (activity.activateVoiceFromFloating && !isLoading) {
            activity.activateVoiceFromFloating = false
            activateFromFloating = true
        }
    }
    
    // Sync teaching mode state
    androidx.compose.runtime.LaunchedEffect(isTeachingMode, currentWorkflowName) {
        localIsTeachingMode = isTeachingMode
        localWorkflowName = currentWorkflowName
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull() ?: ""
            if (spokenText.isBlank()) {
                phase = DobbyPhase.ERROR
                actionLine = "Couldn't hear anything"
                return@rememberLauncherForActivityResult
            }

            heard = spokenText
            addToHistory("🎤 Heard: $spokenText")

            // Check if we're waiting for call confirmation
            if (CallSession.waitingForConfirmation && CallSession.pendingContact != null) {
                val (name, number) = CallSession.pendingContact!!
                when {
                    isPositiveConfirmation(spokenText, name) -> {
                        CallSession.waitingForConfirmation = false
                        CallSession.pendingContact = null
                        phase = DobbyPhase.ACTING
                        actionLine = "Calling $name..."
                        addToHistory("📞 Calling $name")
                        val callResult = placeCall(context, number)
                        dobbyMessage = callResult
                        phase = DobbyPhase.SPEAKING
                        speak(callResult)
                        phase = DobbyPhase.DONE
                        actionLine = callResult
                    }
                    isNegativeConfirmation(spokenText) -> {
                        CallSession.waitingForConfirmation = false
                        CallSession.pendingContact = null
                        phase = DobbyPhase.DONE
                        actionLine = "Call cancelled"
                        addToHistory("📞 Call cancelled")
                        dobbyMessage = "Okay, I won't call $name."
                        speak(dobbyMessage)
                    }
                    else -> {
                        phase = DobbyPhase.THINKING
                        actionLine = "Please confirm"
                        CallSession.waitingForConfirmation = true
                        dobbyMessage = "Do you want me to call $name? Please say yes or no."
                        speak(dobbyMessage)
                    }
                }
                return@rememberLauncherForActivityResult
            }

            phase = DobbyPhase.THINKING
            understanding = "Thinking"
            actionLine = "Sending to Dobby..."
            currentTask = "Thinking"
            isLoading = true

            scope.launch {
                try {
                    // Check if user wants to stop teaching
                    if (WorkflowManager.isTeaching() && (spokenText.lowercase().contains("done") || 
                        spokenText.lowercase().contains("that's all") ||
                        spokenText.lowercase().contains("finished") ||
                        spokenText.lowercase().contains("stop teaching"))) {
                        val workflow = WorkflowManager.stopTeaching()
                        
                        if (workflow != null) {
                            addToHistory("✓ Learned workflow: ${workflow.name}")
                            speak("Dobby has learned this workflow!")
                            phase = DobbyPhase.DONE
                            actionLine = "Dobby has learned this workflow!"
                            currentTask = "Done"
                            addToHistory("✓ Done")
                            
                            // Save workflows
                            WorkflowManager.saveWorkflows(context)
                        } else {
                            addToHistory("⚠️ No workflow learned")
                            speak("Dobby didn't learn anything this time.")
                            phase = DobbyPhase.DONE
                            actionLine = "Dobby didn't learn anything this time."
                            currentTask = "Done"
                        }
                        isLoading = false
                        return@launch
                    }
                    
                    // Check if we should execute a learned workflow
                    if (!WorkflowManager.isTeaching()) {
                        val matchingWorkflow = WorkflowManager.findMatchingWorkflow(spokenText)
                        if (matchingWorkflow != null) {
                            addToHistory("🔄 Found workflow: ${matchingWorkflow.name}")
                            phase = DobbyPhase.ACTING
                            actionLine = "Executing workflow..."
                            
                            // Extract parameters from spoken text (simple extraction for MVP)
                            val params = extractParameters(spokenText, matchingWorkflow)
                            val generalizedSteps = WorkflowManager.generalizeWorkflow(matchingWorkflow, params)
                            
                            val confirmations = mutableListOf<String>()
                            for (step in generalizedSteps) {
                                val stepJson = JSONObject().apply {
                                    put("intent", step.action)
                                    step.app?.let { put("app", it) }
                                    step.query?.let { put("query", it) }
                                    step.setting?.let { put("setting", it) }
                                    step.state?.let { put("state", it) }
                                    step.value?.let { put("value", it) }
                                    step.enabled?.let { put("enabled", it) }
                                    step.contact?.let { put("contact", it) }
                                }
                                
                                val confirmation = executeIntent(context, stepJson, addToHistory)
                                    ?: step.description ?: "Done."
                                confirmations.add(confirmation)
                                addToHistory("✓ $confirmation")
                            }
                            
                            dobbyMessage = confirmations.joinToString(" ")
                            phase = DobbyPhase.SPEAKING
                            speak(dobbyMessage)
                            phase = DobbyPhase.DONE
                            actionLine = dobbyMessage
                            currentTask = "Done"
                            addToHistory("✓ Done")
                            isLoading = false
                            return@launch
                        }
                    }
                    
                    val intentJson = postToBackend("/intent", spokenText)
                    applyMemory(intentJson) { wants, task, learned ->
                        userWants = wants
                        if (task.isNotBlank()) {
                            understanding = task
                            currentTask = task
                        }
                        if (learned.isNotBlank()) learnedLine = learned
                    }
                    val actionsArray = intentJson.optJSONArray("actions")
                    val confirmations = mutableListOf<String>()
                    if (actionsArray != null && actionsArray.length() > 0) {
                        for (i in 0 until actionsArray.length()) {
                            val actionJson = actionsArray.getJSONObject(i)
                            val intentName = jsonText(actionJson, "intent").lowercase(Locale.getDefault())
                            actionLine = actionJson.optString("message", intentName)

                            if (intentName == "question") {
                                phase = DobbyPhase.THINKING
                                actionLine = "Asking Gemini..."
                                addToHistory("💭 Thinking...")
                                val chatJson = postToBackend("/chat", spokenText)
                                confirmations.add(
                                    chatJson.optString("reply", "Dobby is speechless for a moment.")
                                )
                            } else if (intentName == "teach_workflow") {
                                phase = DobbyPhase.TEACHING
                                actionLine = "Teaching mode"
                                addToHistory("🎓 Entering teaching mode")
                                val workflowName = actionJson.optString("workflow_name", "custom_workflow")
                                WorkflowManager.startTeaching(workflowName)
                                confirmations.add("Dobby is watching. Please demonstrate the workflow.")
                            } else {
                                phase = DobbyPhase.ACTING
                                addToHistory("⚡ Acting: $intentName")
                                
                                // If in teaching mode, capture this step
                                if (WorkflowManager.isTeaching()) {
                                    val step = WorkflowStep(
                                        action = intentName,
                                        app = jsonText(actionJson, "app"),
                                        query = jsonText(actionJson, "query"),
                                        setting = jsonText(actionJson, "setting"),
                                        state = jsonText(actionJson, "state"),
                                        value = optionalValue(actionJson),
                                        enabled = actionJson.optBoolean("enabled").takeIf { it },
                                        contact = jsonText(actionJson, "contact"),
                                        description = actionLine
                                    )
                                    WorkflowManager.addStep(step)
                                    addToHistory("📝 Captured step: $intentName")
                                }
                                
                                val confirmation = executeIntent(context, actionJson, addToHistory)
                                    ?: actionJson.optString("message", "Done.")
                                confirmations.add(confirmation)
                                actionLine = confirmation
                                addToHistory("✓ $confirmation")
                            }
                        }
                    } else {
                        phase = DobbyPhase.THINKING
                        actionLine = "Asking Gemini..."
                        addToHistory("💭 Thinking...")
                        val chatJson = postToBackend("/chat", spokenText)
                        confirmations.add(
                            chatJson.optString("reply", "Dobby is speechless for a moment.")
                        )
                    }
                    dobbyMessage = confirmations.joinToString(" ")
                    phase = DobbyPhase.SPEAKING
                    speak(dobbyMessage)
                    phase = DobbyPhase.DONE
                    actionLine = dobbyMessage
                    currentTask = "Done"
                    addToHistory("✓ Done")
                } catch (e: Exception) {
                    phase = DobbyPhase.ERROR
                    val detail = e.message ?: "unknown error"
                    actionLine = "Error: $detail"
                    currentTask = "Error"
                    val unreachable = detail.contains("Failed to connect", ignoreCase = true) ||
                        detail.contains("Connection refused", ignoreCase = true) ||
                        detail.contains("timed out", ignoreCase = true) ||
                        detail.contains("timeout", ignoreCase = true)
                    dobbyMessage = if (unreachable) {
                        "Dobby couldn't reach the backend. Keep uvicorn running, USB plugged in, then run adb reverse tcp:8000 tcp:8000."
                    } else {
                        "Dobby hit a problem: $detail"
                    }
                    speak(dobbyMessage)
                } finally {
                    isLoading = false
                }
            }
        } else {
            phase = DobbyPhase.DONE
            actionLine = "Speech cancelled"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            phase = DobbyPhase.LISTENING
            actionLine = "Listening..."
            launchSpeechRecognition(context, speechLauncher)
        } else {
            phase = DobbyPhase.ERROR
            actionLine = "Microphone permission needed"
        }
    }

    val onMicClick: () -> Unit = {
        stopSpeaking()
        
        // Check audio permission first
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            phase = DobbyPhase.LISTENING
            actionLine = "Listening..."
            currentTask = "Listening"
            launchSpeechRecognition(context, speechLauncher)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    
    // Trigger mic click when activated from floating icon
    androidx.compose.runtime.LaunchedEffect(activateFromFloating) {
        if (activateFromFloating && !isLoading) {
            activateFromFloating = false
            // Small delay to allow UI to settle
            kotlinx.coroutines.delay(200)
            onMicClick()
            addToHistory("🎤 Activated from floating icon")
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top
        ) {
            Text(text = "Dobby", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "────────────────────",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
            
            // Floating service controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { if (floatingServiceRunning) stopFloatingService() else startFloatingService() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (floatingServiceRunning) "Stop Floating" else "Start Floating")
                }
            }
            
            // Teaching mode controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!localIsTeachingMode) {
                    Button(
                        onClick = { startTeaching("New Workflow") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🎓 Start Teaching")
                    }
                } else {
                    Button(
                        onClick = { stopTeaching() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("✓ Stop Teaching")
                    }
                }
            }
            
            if (localIsTeachingMode) {
                Text(
                    text = "Teaching: $localWorkflowName",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Dobby is watching your actions...",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Text(
                text = "Status: ${shownPhase.name}",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            MemoryBlock("Heard", heard.ifBlank { "(nothing yet)" })
            MemoryBlock("Understanding", understanding)
            MemoryBlock("Action", actionLine)
            
            // History section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .padding(12.dp)
            ) {
                Text("Recent Activity", style = MaterialTheme.typography.titleSmall)
                if (dobbyHistory.isEmpty()) {
                    Text("No activity yet", style = MaterialTheme.typography.bodySmall)
                } else {
                    dobbyHistory.forEach { item ->
                        Text("• $item", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .padding(12.dp)
            ) {
                Text("Memory", style = MaterialTheme.typography.titleSmall)
                Text("• User wants: ${userWants.ifBlank { "(none)" }}")
                Text("• Current task: $currentTask")
                Text("• Learned: $learnedLine")
                Text("• Status: ${shownPhase.name}")
            }
            if (dobbyMessage.isNotEmpty()) {
                Text(
                    text = dobbyMessage,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }
            Button(
                onClick = onMicClick,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .align(Alignment.CenterHorizontally),
                enabled = !isLoading
            ) {
                Text("🎙️ Tap to Speak")
            }
        }
    }
}

@Composable
fun MemoryBlock(title: String, value: String) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

fun applyMemory(
    intentJson: JSONObject,
    onMemory: (String, String, String) -> Unit
) {
    val memory = intentJson.optJSONObject("memory")
    val wants = jsonText(memory ?: JSONObject(), "user_wants").ifBlank {
        val actions = intentJson.optJSONArray("actions")
        if (actions != null && actions.length() > 0) {
            jsonText(actions.getJSONObject(0), "query")
        } else ""
    }
    val task = intentJson.optString("understanding").ifBlank {
        jsonText(memory ?: JSONObject(), "current_task")
    }
    val learned = formatLearned(memory?.optJSONArray("learned_workflows"))
    onMemory(wants, task, learned)
}

fun formatLearned(workflows: JSONArray?): String {
    if (workflows == null || workflows.length() == 0) return "None yet"
    val names = mutableListOf<String>()
    for (i in 0 until workflows.length()) {
        val item = workflows.optJSONObject(i) ?: continue
        val name = item.optString("name").ifBlank { "workflow" }
        val actions = item.optJSONArray("actions")
        val queryHint = if (actions != null && actions.length() > 0) {
            jsonText(actions.getJSONObject(0), "query")
        } else ""
        names.add(if (queryHint.isBlank()) name else "$name ($queryHint)")
    }
    return names.joinToString(", ")
}

suspend fun postToBackend(path: String, message: String): JSONObject = withContext(Dispatchers.IO) {
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

fun jsonText(json: JSONObject, key: String): String {
    if (!json.has(key) || json.isNull(key)) return ""
    return json.optString(key).trim()
}

fun optionalValue(intentJson: JSONObject): Int? {
    if (intentJson.has("value") && !intentJson.isNull("value")) return intentJson.optInt("value")
    if (intentJson.has("level") && !intentJson.isNull("level")) return intentJson.optInt("level")
    return null
}

suspend fun executeIntent(context: Context, intentJson: JSONObject, addToHistory: (String) -> Unit): String? {
    val intentString = intentJson.optString("intent").lowercase(Locale.getDefault())
    android.util.Log.e("DobbyDiagnostics", "executeIntent called with intent: $intentString")
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
            Toast.makeText(context, "Dobby isn't sure how to do that yet", Toast.LENGTH_SHORT).show()
            "Dobby isn't sure how to do that yet"
        }
    }
}

fun amazonUiAction(context: Context, action: String, addToHistory: (String) -> Unit): String {
    android.util.Log.e("DobbyDiagnostics", "amazonUiAction called with action: $action, accessibilityEnabled: ${accessibilityEnabled(context)}")
    if (!accessibilityEnabled(context)) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

fun accessibilityEnabled(context: Context): Boolean {
    if (DobbyAccessibilityService.isConnected()) return true
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabled.contains(context.packageName, ignoreCase = true)
}

fun openApp(context: Context, appName: String): String {
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
        Toast.makeText(context, "$spoken is not installed on this phone", Toast.LENGTH_LONG).show()
        "$spoken is not installed on this phone"
    }
}

fun normalizeAppName(name: String): String {
    return name.lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9 +]"), " ")
        .replace(Regex("\\b(the|app|application|please|dobby)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun findInstalledApp(context: Context, spoken: String): Intent? {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(
        launcher,
        PackageManager.ResolveInfoFlags.of(0)
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

fun playSpotifySong(context: Context, query: String): String {
    val song = query.trim()
        .replace(Regex("(?i)\\s+on\\s+(the\\s+)?spotify( app)?$"), "")
        .trim()
    if (song.isEmpty() || song.equals("null", ignoreCase = true)) {
        return "Tell Dobby the song name to play."
    }

    val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        setPackage("com.spotify.music")
        putExtra(SearchManager.QUERY, song)
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
        putExtra(MediaStore.EXTRA_MEDIA_TITLE, song)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (playIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(playIntent)
        return "Asked Spotify to play $song."
    }

    val searchUri = Uri.parse("spotify:search:${Uri.encode(song)}")
    val searchIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
        setPackage("com.spotify.music")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return if (searchIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(searchIntent)
        "Opened Spotify search for $song. Tap the song if it does not start."
    } else {
        Toast.makeText(context, "Spotify is not installed", Toast.LENGTH_LONG).show()
        "Spotify is not installed on this phone"
    }
}

fun openPhoneSetting(context: Context, setting: String): String {
    val key = setting.trim().lowercase(Locale.getDefault())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val panel = when (key) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "bluetooth" -> null /* Panel doesn't exist, fallback to general */
            "sound", "volume" -> Settings.Panel.ACTION_VOLUME
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

fun launchSpeechRecognition(
    context: Context,
    speechLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something to Dobby...")
    }

    try {
        speechLauncher.launch(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Speech recognition not available", Toast.LENGTH_LONG).show()
    }
}

fun searchAmazon(context: Context, query: String): String {
    val product = query.trim()
    if (product.isEmpty() || product.equals("null", ignoreCase = true)) {
        return openApp(context, "amazon")
    }

    val encoded = Uri.encode(product)
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
            val appSearch = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (appSearch.resolveActivity(context.packageManager) != null) {
                context.startActivity(appSearch)
                return "Searching Amazon for $product. Pick the item, then say add this to my cart."
            }
        }
    }

    val browserSearch = Intent(Intent.ACTION_VIEW, Uri.parse(searchUris.first())).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(browserSearch)
    return "Searching Amazon for $product. Pick the item, then say add this to my cart."
}

fun toggleFlashlight(context: Context, state: String): String {
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

fun setBrightness(context: Context, level: Int?, state: String): String {
    if (!Settings.System.canWrite(context)) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        intent.data = Uri.parse("package:" + context.packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Allow Dobby to change system settings, then ask again. Brightness was not changed."
    }
    val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    val currentPct = (current * 100) / 255
    val targetPct = when {
        level != null -> level.coerceIn(0, 100)
        state == "up" -> (currentPct + 20).coerceAtMost(100)
        state == "down" -> (currentPct - 20).coerceAtLeast(0)
        else -> 50
    }
    val brightness = (targetPct / 100f * 255).toInt().coerceIn(0, 255)
    Settings.System.putInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        brightness
    )
    return "Set brightness to $targetPct%."
}

fun setVolume(context: Context, level: Int?, state: String): String {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = when {
            level != null -> (maxVolume * level.coerceIn(0, 100) / 100f).toInt().coerceIn(0, maxVolume)
            state == "mute" -> 0
            state == "up" -> (current + (maxVolume / 5).coerceAtLeast(1)).coerceAtMost(maxVolume)
            state == "down" -> (current - (maxVolume / 5).coerceAtLeast(1)).coerceAtLeast(0)
            else -> maxVolume / 2
        }
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            target,
            0
        )
        val percent = if (maxVolume == 0) 0 else (target * 100) / maxVolume
        return "Set volume to $percent%."
    } catch (_: Exception) {
        return "Could not set volume."
    }
}

fun extractParameters(spokenText: String, workflow: Workflow): Map<String, String> {
    val params = mutableMapOf<String, String>()
    val textLower = spokenText.lowercase()
    
    // Simple parameter extraction for MVP
    // Look for product names, song names, contact names, etc.
    val words = spokenText.split(" ").filter { it.isNotBlank() }
    
    // Try to identify what parameter type is needed based on workflow steps
    for (step in workflow.steps) {
        when {
            step.action == "amazon_search" || step.action == "play_song" -> {
                // Extract the query parameter (everything after common words)
                val queryWords = words.filterNot { 
                    it.lowercase() in listOf("find", "search", "play", "on", "for", "the", "a", "an", "dobby", "please")
                }
                if (queryWords.isNotEmpty()) {
                    params["query"] = queryWords.joinToString(" ")
                }
            }
            step.action == "call_contact" -> {
                // Extract contact name
                val contactWords = words.filterNot { 
                    it.lowercase() in listOf("call", "phone", "dial", "dobby", "please")
                }
                if (contactWords.isNotEmpty()) {
                    params["contact"] = contactWords.joinToString(" ")
                }
            }
        }
    }
    
    return params
}
