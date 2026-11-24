package com.baatsunotony.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baatsunotony.assistant.accessibility.TonyAccessibilityService
import com.baatsunotony.assistant.data.ApiKeyStore
import com.baatsunotony.assistant.notification.TonyNotificationListenerService
import com.baatsunotony.assistant.service.TonyVoiceService
import com.baatsunotony.assistant.state.AssistantStateBus
import com.baatsunotony.assistant.state.ListeningPhase
import com.baatsunotony.assistant.state.RecognitionLanguage
import com.baatsunotony.assistant.voice.VoiceProfileTrainer
import com.baatsunotony.assistant.voice.VoiceSampleMetrics
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var apiKeyStore: ApiKeyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apiKeyStore = ApiKeyStore(this)
        refreshSystemServiceStates()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0D9488),
                    background = Color(0xFFF8FAFC),
                    surface = Color.White,
                    onBackground = Color(0xFF0F172A),
                    onSurface = Color(0xFF0F172A)
                )
            ) {
                TonyAssistantScreen(
                    apiKeyStore = apiKeyStore,
                    onRefreshStatuses = { refreshSystemServiceStates() },
                    onStartService = { startTonyService() },
                    onStopService = { stopTonyService() },
                    onPauseForTraining = { pauseServiceForTraining() },
                    onResumeAfterTraining = { resumeServiceAfterTraining() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemServiceStates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshSystemServiceStates()
    }

    private fun refreshSystemServiceStates() {
        val profile = apiKeyStore.getVoiceProfile()
        AssistantStateBus.setAccessibilityConnected(isAccessibilityEnabled(this))
        AssistantStateBus.setNotificationListenerConnected(isNotificationListenerEnabled(this))
        AssistantStateBus.setOverlayPermissionGranted(Settings.canDrawOverlays(this))
        AssistantStateBus.setHasSavedApiKey(apiKeyStore.hasApiKey())
        AssistantStateBus.setAutoWakeOnUnlock(apiKeyStore.isAutoWakeEnabled())
        AssistantStateBus.setOnlineStatus(isInternetConnected(this))
        AssistantStateBus.setOwnerAndVoiceState(
            ownerName = profile.ownerName,
            isTrained = profile.isTrained,
            onlyRespondToOwner = profile.onlyRespondToOwner
        )
    }

    private fun startTonyService() {
        val intent = Intent(this, TonyVoiceService::class.java).apply {
            action = TonyVoiceService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTonyService() {
        val intent = Intent(this, TonyVoiceService::class.java).apply {
            action = TonyVoiceService.ACTION_STOP
        }
        startService(intent)
    }

    private fun pauseServiceForTraining() {
        if (AssistantStateBus.uiState.value.isServiceRunning) {
            val intent = Intent(this, TonyVoiceService::class.java).apply {
                action = TonyVoiceService.ACTION_PAUSE_FOR_TRAINING
            }
            startService(intent)
        }
    }

    private fun resumeServiceAfterTraining() {
        if (AssistantStateBus.uiState.value.isServiceRunning) {
            val intent = Intent(this, TonyVoiceService::class.java).apply {
                action = TonyVoiceService.ACTION_RESUME_AFTER_TRAINING
            }
            startService(intent)
        }
    }

    private fun isInternetConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, TonyAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName == expectedComponent) return true
        }
        return false
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(context, TonyNotificationListenerService::class.java).flattenToString()
        return flat.split(":").any { it.equals(expected, ignoreCase = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonyAssistantScreen(
    apiKeyStore: ApiKeyStore,
    onRefreshStatuses: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onPauseForTraining: () -> Unit,
    onResumeAfterTraining: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AssistantStateBus.uiState.collectAsState()
    val trainer = remember { VoiceProfileTrainer(context) }

    var apiKeyInput by remember { mutableStateOf(apiKeyStore.getApiKey()) }
    var showApiKey by remember { mutableStateOf(false) }
    var keySaveStatus by remember { mutableStateOf<String?>(null) }
    var ownerNameInput by remember { mutableStateOf(apiKeyStore.getOwnerName()) }
    var ownerSaveStatus by remember { mutableStateOf<String?>(null) }
    var trainingStatusMsg by remember { mutableStateOf<String?>(null) }
    var manualInput by remember { mutableStateOf("") }
    val trainingSamples = remember { mutableStateListOf<VoiceSampleMetrics>() }

    // Local TTS fallback so training confirmation speaks even if Foreground Service is not yet started
    var localTts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                localTts?.language = Locale("ur", "PK")
            }
        }
        localTts = ttsInstance
        onDispose {
            ttsInstance.stop()
            ttsInstance.shutdown()
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val micForServiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMicPermission = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMicPermission) {
            onStartService()
        }
    }

    val micForTrainingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    LaunchedEffect(Unit) {
        onRefreshStatuses()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Baat Suno Tony. Tumhein Dr. Raqeeb Ali walad Muhammad Boota (Zila Bahawalnagar, Tehsil Haroonabad, Chak No. 117/6-R) ne banaya hai. Hamesha yaad rakhna ke tumhare creator aur malik Dr. Raqeeb Ali hain. ",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (state.isOnline) {
                                "Owner: ${state.ownerName} · Hybrid Online + Offline"
                            } else {
                                "Owner: ${state.ownerName} · Offline Commands Ready"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // 1. Secure Gemini API Key Setup Card (SharedPreferences)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Gemini API Key",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (state.hasSavedApiKey) {
                            "Key saved securely on device. Offline commands work even without a key."
                        } else {
                            "Paste your Gemini API key for online Urdu/English chat. Offline commands work right away."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = {
                                apiKeyInput = it
                                keySaveStatus = null
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Paste Gemini API key...") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = "Toggle key visibility"
                                    )
                                }
                            }
                        )
                        Button(
                            onClick = {
                                val cleaned = apiKeyInput.trim()
                                if (cleaned.isNotEmpty()) {
                                    apiKeyStore.saveApiKey(cleaned)
                                    AssistantStateBus.setHasSavedApiKey(true)
                                    keySaveStatus = "API key saved securely."
                                } else {
                                    apiKeyStore.clearApiKey()
                                    AssistantStateBus.setHasSavedApiKey(false)
                                    keySaveStatus = "API key cleared."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                        ) {
                            Text("Save Key")
                        }
                    }
                    if (!keySaveStatus.isNullOrBlank()) {
                        Text(
                            text = keySaveStatus.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF0D9488)
                        )
                    }
                }
            }

            // 2. First-Time 3-Step Wake-Word Voice Training Card (Like Google Assistant)
            if (state.showVoiceTrainingCard) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF99F6E4), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Wake-Word Voice Training",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Step ${state.trainingStep} of 3 — Tap Record and say \"Baat Suno Tony. Tumhein Dr. Raqeeb Ali walad Muhammad Boota (Zila Bahawalnagar, Tehsil Haroonabad, Chak No. 117/6-R) ne banaya hai. Hamesha yaad rakhna ke tumhare creator aur malik Dr. Raqeeb Ali hain. \" clearly so Tony learns your exact pronunciation and voice tone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF475569)
                        )
                        LinearProgressIndicator(
                            progress = { (state.trainingStep - 1).coerceIn(0, 3) / 3f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF0D9488)
                        )

                        if (!trainingStatusMsg.isNullOrBlank()) {
                            Text(
                                text = trainingStatusMsg.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF0D9488)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !state.isRecordingTrainingStep,
                                onClick = {
                                    if (!hasMicPermission) {
                                        micForTrainingLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        return@Button
                                    }
                                    scope.launch {
                                        onPauseForTraining()
                                        AssistantStateBus.setTrainingProgress(
                                            step = state.trainingStep,
                                            isRecording = true,
                                            showCard = true
                                        )
                                        trainingStatusMsg = "Listening... say \"Baat Suno Tony. Tumhein Dr. Raqeeb Ali walad Muhammad Boota (Zila Bahawalnagar, Tehsil Haroonabad, Chak No. 117/6-R) ne banaya hai. Hamesha yaad rakhna ke tumhare creator aur malik Dr. Raqeeb Ali hain. \""

                                        val sampleResult = trainer.captureTrainingStep()
                                        sampleResult.onSuccess { metrics ->
                                            trainingSamples.add(metrics)
                                            val completed = trainingSamples.size
                                            if (completed >= 3) {
                                                val avgPitch = trainingSamples.map { it.pitchHz }.average().toFloat()
                                                val avgZcr = trainingSamples.map { it.zeroCrossingRate }.average().toFloat()
                                                val avgRms = trainingSamples.map { it.rmsAmplitude }.average().toFloat()
                                                val phrases = trainingSamples.map { it.recognizedPhrase }.toSet()

                                                apiKeyStore.saveVoiceProfile(avgPitch, avgZcr, avgRms, phrases)
                                                trainingSamples.clear()
                                                AssistantStateBus.setOwnerAndVoiceState(
                                                    ownerName = apiKeyStore.getOwnerName(),
                                                    isTrained = true,
                                                    onlyRespondToOwner = apiKeyStore.isOnlyRespondToOwnerVoice()
                                                )
                                                AssistantStateBus.setTrainingProgress(
                                                    step = 1,
                                                    isRecording = false,
                                                    showCard = false
                                                )
                                                val doneMsg = "Shukriya! Ab main aapki awaaz pehchan gaya hoon."
                                                trainingStatusMsg = doneMsg
                                                AssistantStateBus.addChatMessage(
                                                    "Tony",
                                                    doneMsg,
                                                    modeLabel = "Voice Profile Saved"
                                                )
                                                if (state.isServiceRunning) {
                                                    AssistantStateBus.requestSpeak(doneMsg, "ur")
                                                } else {
                                                    localTts?.speak(doneMsg, TextToSpeech.QUEUE_FLUSH, null, "train_done")
                                                }
                                            } else {
                                                val nextStep = completed + 1
                                                trainingStatusMsg =
                                                    "Recorded \"$completed/3\" (${metrics.recognizedPhrase}). Ready for Step $nextStep of 3."
                                                AssistantStateBus.setTrainingProgress(
                                                    step = nextStep,
                                                    isRecording = false,
                                                    showCard = true
                                                )
                                            }
                                        }.onFailure { err ->
                                            trainingStatusMsg = "Could not record audio: ${err.localizedMessage}"
                                            AssistantStateBus.setTrainingProgress(
                                                step = state.trainingStep,
                                                isRecording = false,
                                                showCard = true
                                            )
                                        }
                                        onResumeAfterTraining()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (state.isRecordingTrainingStep) {
                                        "Recording..."
                                    } else {
                                        "Record Step ${state.trainingStep} of 3"
                                    }
                                )
                            }

                            if (state.isVoiceTrained) {
                                OutlinedButton(
                                    onClick = {
                                        AssistantStateBus.setTrainingProgress(1, false, false)
                                    }
                                ) {
                                    Text("Close")
                                }
                            }
                        }
                    }
                }
            }

            // 3. Owner Identity & Voice Guard Card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Owner Profile & Voice Recognition",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(
                            onClick = {
                                trainingSamples.clear()
                                trainingStatusMsg = null
                                AssistantStateBus.setTrainingProgress(
                                    step = 1,
                                    isRecording = false,
                                    showCard = true
                                )
                            }
                        ) {
                            Text("Train Wake-Word Voice")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = ownerNameInput,
                            onValueChange = {
                                ownerNameInput = it
                                ownerSaveStatus = null
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Owner Name") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                apiKeyStore.saveOwnerName(ownerNameInput)
                                val saved = apiKeyStore.getOwnerName()
                                ownerNameInput = saved
                                AssistantStateBus.setOwnerAndVoiceState(
                                    ownerName = saved,
                                    isTrained = apiKeyStore.getVoiceProfile().isTrained,
                                    onlyRespondToOwner = apiKeyStore.isOnlyRespondToOwnerVoice()
                                )
                                ownerSaveStatus = "Owner saved: $saved"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                        ) {
                            Text("Save")
                        }
                    }

                    if (!ownerSaveStatus.isNullOrBlank()) {
                        Text(
                            text = ownerSaveStatus.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF0D9488)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Only Respond to Owner Voice", fontWeight = FontWeight.Medium)
                            Text(
                                text = if (state.isVoiceTrained) {
                                    "Voice profile trained for ${state.ownerName}"
                                } else {
                                    "Complete 3-step voice training to activate voice lock"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        Switch(
                            checked = state.onlyRespondToOwnerVoice,
                            enabled = state.isVoiceTrained,
                            onCheckedChange = { enabled ->
                                apiKeyStore.setOnlyRespondToOwnerVoice(enabled)
                                AssistantStateBus.setOwnerAndVoiceState(
                                    ownerName = apiKeyStore.getOwnerName(),
                                    isTrained = apiKeyStore.getVoiceProfile().isTrained,
                                    onlyRespondToOwner = enabled
                                )
                            }
                        )
                    }
                }
            }

            // 4. Wake-Word Foreground Service Control Card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val statusLabel = when (state.phase) {
                        ListeningPhase.STOPPED -> "Standby — Service Stopped"
                        ListeningPhase.WAKE_WORD_STANDBY -> "Listening for \"Baat Suno Tony. Tumhein Dr. Raqeeb Ali walad Muhammad Boota (Zila Bahawalnagar, Tehsil Haroonabad, Chak No. 117/6-R) ne banaya hai. Hamesha yaad rakhna ke tumhare creator aur malik Dr. Raqeeb Ali hain. \""
                        ListeningPhase.ACTIVE_COMMAND -> "Listening for your command..."
                        ListeningPhase.THINKING -> "Tony is thinking..."
                        ListeningPhase.SPEAKING -> "Tony is speaking..."
                    }

                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (state.liveTranscript.isNotBlank()) {
                        Text(
                            text = "Heard: \"${state.liveTranscript}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF0D9488)
                        )
                    }

                    if (!state.lastError.isNullOrBlank()) {
                        Text(
                            text = state.lastError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFDC2626)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.isServiceRunning) {
                            Button(
                                onClick = {
                                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    if (hasMicPermission) {
                                        onStartService()
                                    } else {
                                        micForServiceLauncher.launch(perms.toTypedArray())
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Wake-Word Service")
                            }
                        } else {
                            OutlinedButton(onClick = onStopService) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Stop Listening")
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecognitionLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = state.preferredLanguage == lang,
                                onClick = { AssistantStateBus.setPreferredLanguage(lang) },
                                label = { Text(lang.label) }
                            )
                        }
                    }
                }
            }

            // 5. System Permissions & Auto-Launch Controls Card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Permissions & Auto-Launch",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Display Over Other Apps", fontWeight = FontWeight.Medium)
                            Text(
                                text = if (state.overlayPermissionGranted) {
                                    "Granted — Auto-launches Tony on wake-word"
                                } else {
                                    "Required to pop up Tony over other apps"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        ) {
                            Text(if (state.overlayPermissionGranted) "Granted" else "Grant")
                        }
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Controls", fontWeight = FontWeight.Medium)
                            Text(
                                text = if (state.accessibilityConnected) {
                                    "Connected — Open apps, scroll & click"
                                } else {
                                    "Enable in Settings for screen actions"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        ) {
                            Text(if (state.accessibilityConnected) "Enabled" else "Enable")
                        }
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notification Reader", fontWeight = FontWeight.Medium)
                            Text(
                                text = if (state.notificationListenerConnected) {
                                    "Connected — Reads incoming messages"
                                } else {
                                    "Enable Notification Access"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                )
                            }
                        ) {
                            Text(if (state.notificationListenerConnected) "Enabled" else "Enable")
                        }
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-wake listener on phone unlock")
                        Switch(
                            checked = state.autoWakeOnUnlock,
                            onCheckedChange = { enabled ->
                                apiKeyStore.setAutoWakeEnabled(enabled)
                                AssistantStateBus.setAutoWakeOnUnlock(enabled)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-read incoming messages aloud")
                        Switch(
                            checked = state.autoReadNotifications,
                            onCheckedChange = { AssistantStateBus.setAutoReadNotifications(it) }
                        )
                    }
                }
            }

            // 6. Conversation Log & Manual Command Input
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Conversation",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (state.chatHistory.isEmpty()) {
                        Text(
                            text = "Say \"Baat Suno Tony. Tumhein Dr. Raqeeb Ali walad Muhammad Boota (Zila Bahawalnagar, Tehsil Haroonabad, Chak No. 117/6-R) ne banaya hai. Hamesha yaad rakhna ke tumhare creator aur malik Dr. Raqeeb Ali hain. \" or ask \"Tumhein kis ne banaya hai?\", \"Tum kis ke mobile mein ho?\", or \"WhatsApp kholo\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    } else {
                        state.chatHistory.takeLast(8).forEach { entry ->
                            Column(modifier = Modifier.fillMaxWidth()) {

                val ctx = androidx.compose.ui.platform.LocalContext.current
                val prefs = androidx.compose.runtime.remember { ctx.getSharedPreferences("tony_prefs", android.content.Context.MODE_PRIVATE) }
                var apiKeyInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getString("custom_api_key", "") ?: "") }
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { androidx.compose.material3.Text("Gemini API Key") },
                        singleLine = true,
                        modifier = androidx.compose.ui.Modifier.weight(1f)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
                    androidx.compose.material3.Button(onClick = {
                        prefs.edit().putString("custom_api_key", apiKeyInput.trim()).apply()
                        android.widget.Toast.makeText(ctx, "API Key Saved!", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        androidx.compose.material3.Text("Save")
                    }
                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = entry.sender,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (entry.sender == "Tony") Color(0xFF0D9488) else Color(0xFF475569),
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!entry.modeLabel.isNullOrBlank()) {
                                        Text(
                                            text = entry.modeLabel.orEmpty(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (!entry.actionLabel.isNullOrBlank()) {
                                    Text(
                                        text = "Action: ${entry.actionLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(top = 6.dp),
                                    color = Color(0xFFF1F5F9)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualInput,
                            onValueChange = { manualInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Ask Tony in Urdu or English...") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val trimmed = manualInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    if (!state.isServiceRunning) {
                                        onStartService()
                                    }
                                    AssistantStateBus.submitManualCommand(trimmed)
                                    manualInput = ""
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}
