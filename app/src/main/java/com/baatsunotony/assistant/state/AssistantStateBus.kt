package com.baatsunotony.assistant.state

import com.baatsunotony.assistant.data.ApiKeyStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ListeningPhase {
    STOPPED,
    WAKE_WORD_STANDBY,
    ACTIVE_COMMAND,
    THINKING,
    SPEAKING
}

enum class RecognitionLanguage(val code: String, val label: String) {
    BILINGUAL("ur-PK", "Urdu + English"),
    URDU("ur-PK", "Urdu (Pakistan)"),
    ENGLISH("en-US", "English (US)")
}

data class ChatEntry(
    val id: Long = System.currentTimeMillis(),
    val sender: String,
    val text: String,
    val actionLabel: String? = null,
    val modeLabel: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class SystemActionLog(
    val id: Long = System.currentTimeMillis(),
    val actionType: String,
    val detail: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class AssistantUiState(
    val isServiceRunning: Boolean = false,
    val phase: ListeningPhase = ListeningPhase.STOPPED,
    val liveTranscript: String = "",
    val preferredLanguage: RecognitionLanguage = RecognitionLanguage.BILINGUAL,
    val autoReadNotifications: Boolean = true,
    val autoWakeOnUnlock: Boolean = true,
    val hasSavedApiKey: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val isOnline: Boolean = true,
    val isFlashlightOn: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val ownerName: String = ApiKeyStore.DEFAULT_OWNER_NAME,
    val isVoiceTrained: Boolean = false,
    val onlyRespondToOwnerVoice: Boolean = false,
    val trainingStep: Int = 1,
    val isRecordingTrainingStep: Boolean = false,
    val showVoiceTrainingCard: Boolean = true,
    val chatHistory: List<ChatEntry> = emptyList(),
    val recentNotifications: List<CapturedNotification> = emptyList(),
    val actionLogs: List<SystemActionLog> = emptyList(),
    val lastError: String? = null
)

object AssistantStateBus {
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _speakRequests = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val speakRequests: SharedFlow<Pair<String, String>> = _speakRequests.asSharedFlow()

    private val _manualCommandRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val manualCommandRequests: SharedFlow<String> = _manualCommandRequests.asSharedFlow()

    fun setServiceRunning(running: Boolean) {
        _uiState.update {
            it.copy(
                isServiceRunning = running,
                phase = if (running) ListeningPhase.WAKE_WORD_STANDBY else ListeningPhase.STOPPED,
                lastError = if (running) null else it.lastError
            )
        }
    }

    fun setPhase(phase: ListeningPhase) {
        _uiState.update { it.copy(phase = phase) }
    }

    fun setLiveTranscript(transcript: String) {
        _uiState.update { it.copy(liveTranscript = transcript) }
    }

    fun setPreferredLanguage(language: RecognitionLanguage) {
        _uiState.update { it.copy(preferredLanguage = language) }
    }

    fun setAutoReadNotifications(enabled: Boolean) {
        _uiState.update { it.copy(autoReadNotifications = enabled) }
    }

    fun setAutoWakeOnUnlock(enabled: Boolean) {
        _uiState.update { it.copy(autoWakeOnUnlock = enabled) }
    }

    fun setHasSavedApiKey(hasKey: Boolean) {
        _uiState.update { it.copy(hasSavedApiKey = hasKey) }
    }

    fun setOverlayPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(overlayPermissionGranted = granted) }
    }

    fun setOnlineStatus(online: Boolean) {
        _uiState.update { it.copy(isOnline = online) }
    }

    fun setFlashlightOn(on: Boolean) {
        _uiState.update { it.copy(isFlashlightOn = on) }
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _uiState.update { it.copy(accessibilityConnected = connected) }
    }

    fun setNotificationListenerConnected(connected: Boolean) {
        _uiState.update { it.copy(notificationListenerConnected = connected) }
    }

    fun setOwnerAndVoiceState(
        ownerName: String,
        isTrained: Boolean,
        onlyRespondToOwner: Boolean
    ) {
        _uiState.update {
            it.copy(
                ownerName = ownerName,
                isVoiceTrained = isTrained,
                onlyRespondToOwnerVoice = onlyRespondToOwner,
                showVoiceTrainingCard = !isTrained
            )
        }
    }

    fun setTrainingProgress(step: Int, isRecording: Boolean, showCard: Boolean) {
        _uiState.update {
            it.copy(
                trainingStep = step,
                isRecordingTrainingStep = isRecording,
                showVoiceTrainingCard = showCard
            )
        }
    }

    fun setError(message: String?) {
        _uiState.update { it.copy(lastError = message) }
    }

    fun addChatMessage(
        sender: String,
        text: String,
        actionLabel: String? = null,
        modeLabel: String? = null
    ) {
        _uiState.update { state ->
            val entry = ChatEntry(
                sender = sender,
                text = text,
                actionLabel = actionLabel,
                modeLabel = modeLabel
            )
            val updated = (state.chatHistory + entry).takeLast(40)
            state.copy(chatHistory = updated)
        }
    }

    fun addNotification(notification: CapturedNotification) {
        _uiState.update { state ->
            val filtered = state.recentNotifications.filterNot {
                it.packageName == notification.packageName &&
                    it.title == notification.title &&
                    it.body == notification.body
            }
            state.copy(recentNotifications = (listOf(notification) + filtered).take(20))
        }
    }

    fun addActionLog(actionType: String, detail: String, success: Boolean) {
        _uiState.update { state ->
            val entry = SystemActionLog(
                actionType = actionType,
                detail = detail,
                success = success
            )
            val updated = (listOf(entry) + state.actionLogs).take(25)
            state.copy(actionLogs = updated)
        }
    }

    fun requestSpeak(text: String, languageCode: String = "ur") {
        _speakRequests.tryEmit(text to languageCode)
    }

    fun submitManualCommand(command: String) {
        _manualCommandRequests.tryEmit(command)
    }
}
