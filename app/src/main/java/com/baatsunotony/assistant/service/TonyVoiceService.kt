package com.baatsunotony.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.baatsunotony.assistant.MainActivity
import com.baatsunotony.assistant.accessibility.TonyAccessibilityService
import com.baatsunotony.assistant.ai.GeminiClient
import com.baatsunotony.assistant.ai.TonyAction
import com.baatsunotony.assistant.ai.TonyActionType
import com.baatsunotony.assistant.data.ApiKeyStore
import com.baatsunotony.assistant.engine.OfflineCommandEngine
import com.baatsunotony.assistant.hardware.FlashlightController
import com.baatsunotony.assistant.state.AssistantStateBus
import com.baatsunotony.assistant.state.ListeningPhase
import com.baatsunotony.assistant.state.RecognitionLanguage
import com.baatsunotony.assistant.voice.VoiceProfileTrainer
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class TonyVoiceService : LifecycleService(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.baatsunotony.assistant.action.START"
        const val ACTION_STOP = "com.baatsunotony.assistant.action.STOP"
        const val ACTION_ENSURE_LISTENING = "com.baatsunotony.assistant.action.ENSURE_LISTENING"
        const val ACTION_PAUSE_FOR_TRAINING = "com.baatsunotony.assistant.action.PAUSE_FOR_TRAINING"
        const val ACTION_RESUME_AFTER_TRAINING = "com.baatsunotony.assistant.action.RESUME_AFTER_TRAINING"
        const val EXTRA_LAUNCHED_BY_WAKE_WORD = "extra_launched_by_wake_word"

        private const val CHANNEL_ID = "tony_voice_channel"
        private const val NOTIFICATION_ID = 1001

        private val WAKE_WORD_VARIANTS = listOf(
            "baat suno tony",
            "bat suno tony",
            "baat suno toni",
            "baat suno",
            "hey tony",
            "ok tony",
            "hello tony",
            "tony",
            "بات سنو ٹونی",
            "ٹونی"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var flashlightController: FlashlightController
    private lateinit var offlineCommandEngine: OfflineCommandEngine
    private lateinit var geminiClient: GeminiClient
    private lateinit var voiceProfileTrainer: VoiceProfileTrainer

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    
    private var isLiveChatActive = false

    private fun setBeepMuted(mute: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val dir = if (mute) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, dir, 0)
            am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, dir, 0)
        } catch (_: Exception) {}
    }

    private var isListeningActive = false
    private var isPausedForTraining = false
    private var isShuttingDown = false
    private var awaitNextCommand = false
    private var rmsSamples = mutableListOf<Float>()

    private val screenUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                isLiveChatActive = false
                pauseListening()
                return
            }

            val action = intent?.action ?: return
            if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                refreshConnectivityAndPermissions()
                if (!isPausedForTraining &&
                    apiKeyStore.isAutoWakeEnabled() &&
                    AssistantStateBus.uiState.value.isServiceRunning &&
                    AssistantStateBus.uiState.value.phase != ListeningPhase.SPEAKING &&
                    AssistantStateBus.uiState.value.phase != ListeningPhase.THINKING
                ) {
                    AssistantStateBus.addActionLog(
                        "AUTO_WAKE",
                        "Screen active ($action) — wake-word listener verified",
                        true
                    )
                    mainHandler.post { initAndStartListening() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        apiKeyStore = ApiKeyStore(this)
        flashlightController = FlashlightController(this)
        offlineCommandEngine = OfflineCommandEngine(this, flashlightController, apiKeyStore)
        geminiClient = GeminiClient(apiKeyStore)
        voiceProfileTrainer = VoiceProfileTrainer(this)

        createNotificationChannel()
        textToSpeech = TextToSpeech(this, this)
        registerScreenUnlockReceiver()
        refreshConnectivityAndPermissions()
        observeBusEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopAssistant()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_FOR_TRAINING -> {
                isPausedForTraining = true
                mainHandler.post { pauseListening() }
                return START_STICKY
            }
            ACTION_RESUME_AFTER_TRAINING -> {
                isPausedForTraining = false
                refreshConnectivityAndPermissions()
                if (AssistantStateBus.uiState.value.isServiceRunning) {
                    mainHandler.post { initAndStartListening() }
                }
                return START_STICKY
            }
            ACTION_ENSURE_LISTENING -> {
                isPausedForTraining = false
                startForegroundWithNotification("Listening for \"Baat Suno Tony\"")
                AssistantStateBus.setServiceRunning(true)
                refreshConnectivityAndPermissions()
                if (AssistantStateBus.uiState.value.phase == ListeningPhase.STOPPED) {
                    AssistantStateBus.setPhase(ListeningPhase.WAKE_WORD_STANDBY)
                }
                mainHandler.post { initAndStartListening() }
                return START_STICKY
            }
            else -> {
                isPausedForTraining = false
                startForegroundWithNotification("Listening for \"Baat Suno Tony\"")
                AssistantStateBus.setServiceRunning(true)
                AssistantStateBus.setPhase(ListeningPhase.WAKE_WORD_STANDBY)
                refreshConnectivityAndPermissions()
                mainHandler.post { initAndStartListening() }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        isShuttingDown = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenUnlockReceiver)
        } catch (_: Exception) {
        }
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        AssistantStateBus.setServiceRunning(false)
        AssistantStateBus.setPhase(ListeningPhase.STOPPED)
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            configureTtsVoice("ur")
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    AssistantStateBus.setPhase(ListeningPhase.SPEAKING)
                    mainHandler.post { pauseListening() }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (!isShuttingDown && !isPausedForTraining && AssistantStateBus.uiState.value.isServiceRunning) {
                            val nextPhase = if (awaitNextCommand) {
                                ListeningPhase.ACTIVE_COMMAND
                            } else {
                                ListeningPhase.WAKE_WORD_STANDBY
                            }
                            AssistantStateBus.setPhase(nextPhase)
                            scheduleListenRestart(250L)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
        try { Thread.sleep(1100) } catch (_: Exception) {}
        // Smooth mic cooldown
                    onDone(utteranceId)
                }
            })
        }
    }

    private fun registerScreenUnlockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenUnlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenUnlockReceiver, filter)
        }
    }

    private fun observeBusEvents() {
        lifecycleScope.launch {
            AssistantStateBus.speakRequests.collect { (text, lang) ->
                speakText(text, lang)
            }
        }
        lifecycleScope.launch {
            AssistantStateBus.manualCommandRequests.collect { command ->
                if (command.isNotBlank()) {
                    handleUserCommand(command)
                }
            }
        }
    }

    private fun refreshConnectivityAndPermissions() {
        val profile = apiKeyStore.getVoiceProfile()
        AssistantStateBus.setHasSavedApiKey(apiKeyStore.hasApiKey())
        AssistantStateBus.setAutoWakeOnUnlock(apiKeyStore.isAutoWakeEnabled())
        AssistantStateBus.setOverlayPermissionGranted(Settings.canDrawOverlays(this))
        AssistantStateBus.setOnlineStatus(isInternetAvailable())
        AssistantStateBus.setOwnerAndVoiceState(
            ownerName = profile.ownerName,
            isTrained = profile.isTrained,
            onlyRespondToOwner = profile.onlyRespondToOwner
        )
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun startForegroundWithNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TonyVoiceService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Baat Suno Tony")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tony Continuous Voice Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Tony listening hands-free for Baat Suno Tony"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun initAndStartListening() {
        if (isShuttingDown || isPausedForTraining || !AssistantStateBus.uiState.value.isServiceRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AssistantStateBus.setError("Speech recognition service unavailable on this device.")
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
        }

        if (isListeningActive) return

        rmsSamples.clear()
        val langPref = AssistantStateBus.uiState.value.preferredLanguage
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langPref.code)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langPref.code)
            if (langPref == RecognitionLanguage.BILINGUAL) {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "ur-PK"))
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            isListeningActive = true
            setBeepMuted(true); speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            isListeningActive = false
            AssistantStateBus.setError("Microphone start failed: ${e.localizedMessage}")
            scheduleListenRestart(1500L)
        }
    }

    private fun pauseListening() {
        isListeningActive = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun scheduleListenRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private val restartRunnable = Runnable {
        if (!isShuttingDown &&
            !isPausedForTraining &&
            AssistantStateBus.uiState.value.isServiceRunning &&
            AssistantStateBus.uiState.value.phase != ListeningPhase.SPEAKING &&
            AssistantStateBus.uiState.value.phase != ListeningPhase.THINKING
        ) {
            initAndStartListening()
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsSamples.size < 40) {
                    rmsSamples.add(rmsdB)
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListeningActive = false
            }

            override fun onError(error: Int) {
        try { Thread.sleep(1100) } catch (_: Exception) {}
        // Smooth mic cooldown
                isListeningActive = false
                if (isShuttingDown || isPausedForTraining || !AssistantStateBus.uiState.value.isServiceRunning) return

                val delay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                    else -> 1200L
                }
                scheduleListenRestart(delay)
            }

            override fun onResults(results: Bundle?) {
                isListeningActive = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val bestText = matches.firstOrNull()?.trim().orEmpty()
                AssistantStateBus.setLiveTranscript("")

                if (bestText.isNotEmpty()) {
                    processRecognizedSpeech(bestText)
                } else {
                    scheduleListenRestart(250L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    AssistantStateBus.setLiveTranscript(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun processRecognizedSpeech(spokenText: String) {
        val lower = spokenText.lowercase()
        val profile = apiKeyStore.getVoiceProfile()
        val allWakeVariants = (WAKE_WORD_VARIANTS + profile.learnedPhrases).distinct()
        val matchedWakeWord = allWakeVariants.firstOrNull { it.isNotBlank() && lower.contains(it) }

        if (awaitNextCommand) {
            awaitNextCommand = false
            handleUserCommand(spokenText)
            return
        }

        if (matchedWakeWord != null) {
            val avgRmsDb = if (rmsSamples.isNotEmpty()) rmsSamples.average().toFloat() else -100f
            if (!voiceProfileTrainer.matchesOwnerProfile(profile, spokenText, avgRmsDb)) {
                AssistantStateBus.addActionLog(
                    "VOICE_GUARD",
                    "Ignored wake-word (Only Respond to Owner Voice enabled)",
                    false
                )
                scheduleListenRestart(200L)
                return
            }

            playWakeChime()
            launchMainActivityToForeground()

            val strippedCommand = extractCommandAfterWakeWord(spokenText, matchedWakeWord)
            if (strippedCommand.isBlank()) {
                awaitNextCommand = true
                AssistantStateBus.addChatMessage("You", spokenText)
                val greeting = "Ji, main sun raha hoon"
                AssistantStateBus.addChatMessage("Tony", greeting, modeLabel = "Wake-Word")
                updateNotification("Active — Waiting for your command")
                speakText(greeting, "ur")
            } else {
                handleUserCommand(strippedCommand)
            }
        } else {
            scheduleListenRestart(200L)
        }
    }

    private fun playWakeChime() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            mainHandler.postDelayed({ tone.release() }, 250L)
        } catch (_: Exception) {
        }
    }

    private fun launchMainActivityToForeground() {
        val hasOverlay = Settings.canDrawOverlays(this)
        AssistantStateBus.setOverlayPermissionGranted(hasOverlay)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_LAUNCHED_BY_WAKE_WORD, true)
        }
        try {
            startActivity(launchIntent)
            AssistantStateBus.addActionLog(
                "WAKE_LAUNCH",
                if (hasOverlay) "Launched MainActivity over active apps" else "Brought MainActivity forward",
                true
            )
        } catch (e: Exception) {
            AssistantStateBus.addActionLog(
                "WAKE_LAUNCH",
                "Grant 'Display over other apps' for background auto-launch",
                false
            )
        }
    }

    private fun extractCommandAfterWakeWord(fullText: String, wakeWord: String): String {
        val idx = fullText.lowercase().indexOf(wakeWord)
        if (idx == -1) return fullText
        val after = fullText.substring(idx + wakeWord.length)
        return after.trimStart(' ', ',', '.', ':', '-', '!', '?')
    }

    private fun handleUserCommand(command: String) {
        isLiveChatActive = true
        refreshConnectivityAndPermissions()
        AssistantStateBus.addChatMessage("You", command)
        pauseListening()

        // 1. Offline Hybrid Engine first (includes Creator Identity, Owner Recognition, Apps, Scroll, Flashlight, Notifications)
        val offlineResult = offlineCommandEngine.tryExecuteOffline(command)
        if (offlineResult.handled && offlineResult.reply != null) {
            val reply = offlineResult.reply
            AssistantStateBus.addChatMessage(
                sender = "Tony",
                text = reply.reply,
                actionLabel = offlineResult.actionSummary,
                modeLabel = "Offline Engine"
            )
            updateNotification("Listening for \"Baat Suno Tony\"")
            speakText(reply.reply, reply.language)
            return
        }

        // 2. Online Gemini Conversational Engine
        if (!isInternetAvailable()) {
            val offlineMsg =
                "Internet connect nahi hai. Offline mode mein aap apps khol sakte hain, scroll kar sakte hain, flashlight on ya off kar sakte hain, aur messages sun sakte hain."
            AssistantStateBus.addChatMessage("Tony", offlineMsg, modeLabel = "Offline Fallback")
            updateNotification("Offline — Local commands active")
            speakText(offlineMsg, "ur")
            return
        }

        if (!apiKeyStore.hasApiKey()) {
            val missingKeyMsg =
                "Online AI jawab ke liye dashboard par apni Gemini API key save karein. Offline commands abhi bhi chal rahi hain."
            AssistantStateBus.addChatMessage("Tony", missingKeyMsg, modeLabel = "Key Required")
            updateNotification("Save Gemini API key for online AI")
            speakText(missingKeyMsg, "ur")
            return
        }

        AssistantStateBus.setPhase(ListeningPhase.THINKING)
        updateNotification("Thinking: $command")

        lifecycleScope.launch {
            val notifications = AssistantStateBus.uiState.value.recentNotifications
            val result = geminiClient.askTony(command, notifications)

            result.onSuccess { tonyReply ->
                val actionSummary = executeSystemAction(tonyReply.action)
                AssistantStateBus.addChatMessage(
                    sender = "Tony",
                    text = tonyReply.reply,
                    actionLabel = actionSummary,
                    modeLabel = "Online Gemini"
                )
                updateNotification("Listening for \"Baat Suno Tony\"")
                speakText(tonyReply.reply, tonyReply.language)
            }.onFailure { err ->
                val fallbackMsg =
                    "Gemini se connect nahi ho saka. Apni API key aur internet check karein."
                AssistantStateBus.setError(err.localizedMessage ?: "Gemini request failed")
                AssistantStateBus.addChatMessage("Tony", fallbackMsg, modeLabel = "Network Error")
                updateNotification("Listening for \"Baat Suno Tony\"")
                speakText(fallbackMsg, "ur")
            }
        }
    }

    private fun executeSystemAction(action: TonyAction): String? {
        if (action.type == TonyActionType.NONE) return null

        if (action.type == TonyActionType.FLASHLIGHT_ON) {
            val ok = flashlightController.setFlashlight(true)
            return if (ok) "Flashlight ON" else "Flashlight unavailable"
        }

        if (action.type == TonyActionType.FLASHLIGHT_OFF) {
            val ok = flashlightController.setFlashlight(false)
            return if (ok) "Flashlight OFF" else "Flashlight unavailable"
        }

        if (action.type == TonyActionType.READ_NOTIFICATIONS) {
            val notifications = AssistantStateBus.uiState.value.recentNotifications
            if (notifications.isEmpty()) {
                AssistantStateBus.addActionLog("READ_NOTIFICATIONS", "No recent notifications", true)
                return "No unread notifications"
            }
            val latest = notifications.first()
            AssistantStateBus.addActionLog(
                "READ_NOTIFICATIONS",
                "Read ${latest.appName}: ${latest.title}",
                true
            )
            val detailSpeech = "${latest.appName} par ${latest.title} ka message: ${latest.body}"
            mainHandler.postDelayed({ speakText(detailSpeech, "ur") }, 1200L)
            return "Read ${latest.appName} message"
        }

        val accessibility = TonyAccessibilityService.getInstance()
        if (accessibility == null) {
            AssistantStateBus.addActionLog(
                action.type.name,
                "Accessibility service not enabled",
                false
            )
            return "Accessibility permission required"
        }

        return when (action.type) {
            TonyActionType.OPEN_APP -> {
                val ok = accessibility.openAppByName(action.target)
                if (ok) "Opened ${action.target}" else "Could not find ${action.target}"
            }
            TonyActionType.SCROLL_DOWN -> {
                val ok = accessibility.scrollScreen(forward = true)
                if (ok) "Scrolled down" else "Scroll failed"
            }
            TonyActionType.SCROLL_UP -> {
                val ok = accessibility.scrollScreen(forward = false)
                if (ok) "Scrolled up" else "Scroll failed"
            }
            TonyActionType.CLICK_TEXT -> {
                val ok = accessibility.clickByText(action.target)
                if (ok) "Clicked ${action.target}" else "Target not found: ${action.target}"
            }
            TonyActionType.GO_BACK -> {
                accessibility.goBack()
                "Navigated back"
            }
            TonyActionType.GO_HOME -> {
                accessibility.goHome()
                "Returned home"
            }
            else -> null
        }
    }

    private fun speakText(text: String, languageCode: String) {
        if (!isTtsReady || text.isBlank()) {
            scheduleListenRestart(300L)
            return
        }
        configureTtsVoice(languageCode)
        val utteranceId = UUID.randomUUID().toString()
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun configureTtsVoice(languageCode: String) {
        val tts = textToSpeech ?: return
        val targetLocale = if (languageCode == "en") {
            Locale.US
        } else {
            Locale("ur", "PK")
        }
        val availability = tts.isLanguageAvailable(targetLocale)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = targetLocale
        } else {
            tts.language = Locale.US
        }
        tts.setSpeechRate(1.0f)
        tts?.setPitch(1.03f)
    }

    private fun stopAssistant() {
        isShuttingDown = true
        pauseListening()
        textToSpeech?.stop()
        AssistantStateBus.setServiceRunning(false)
        AssistantStateBus.setPhase(ListeningPhase.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
