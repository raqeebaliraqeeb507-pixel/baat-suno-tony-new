package com.baatsunotony.assistant.data

import android.content.Context

data class VoiceProfileData(
    val ownerName: String,
    val isTrained: Boolean,
    val onlyRespondToOwner: Boolean,
    val avgPitchHz: Float,
    val avgZeroCrossingRate: Float,
    val avgRmsAmplitude: Float,
    val learnedPhrases: Set<String>
)

class ApiKeyStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "baat_suno_tony_prefs"
        private const val KEY_GEMINI_API = "gemini_api_key"
        private const val KEY_AUTO_WAKE_ENABLED = "auto_wake_on_unlock"
        private const val KEY_OWNER_NAME = "owner_name"
        private const val KEY_VOICE_TRAINED = "voice_profile_trained"
        private const val KEY_ONLY_OWNER_VOICE = "only_respond_to_owner_voice"
        private const val KEY_OWNER_PITCH_HZ = "owner_pitch_hz"
        private const val KEY_OWNER_ZCR = "owner_zcr"
        private const val KEY_OWNER_RMS = "owner_rms"
        private const val KEY_LEARNED_PHRASES = "learned_wake_phrases"

        const val DEFAULT_OWNER_NAME = "Dr. Raqeeb Ali"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API, apiKey.trim()).apply()
    }

    fun getApiKey(): String {
        return prefs.getString(KEY_GEMINI_API, "").orEmpty().trim()
    }

    fun hasApiKey(): Boolean {
        return getApiKey().isNotEmpty()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_GEMINI_API).apply()
    }

    fun setAutoWakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_WAKE_ENABLED, enabled).apply()
    }

    fun isAutoWakeEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_WAKE_ENABLED, true)
    }

    fun saveOwnerName(name: String) {
        val cleaned = name.trim().ifEmpty { DEFAULT_OWNER_NAME }
        prefs.edit().putString(KEY_OWNER_NAME, cleaned).apply()
    }

    fun getOwnerName(): String {
        return prefs.getString(KEY_OWNER_NAME, DEFAULT_OWNER_NAME)
            ?.trim()
            ?.ifEmpty { DEFAULT_OWNER_NAME }
            ?: DEFAULT_OWNER_NAME
    }

    fun setOnlyRespondToOwnerVoice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLY_OWNER_VOICE, enabled).apply()
    }

    fun isOnlyRespondToOwnerVoice(): Boolean {
        return prefs.getBoolean(KEY_ONLY_OWNER_VOICE, false)
    }

    fun saveVoiceProfile(
        avgPitchHz: Float,
        avgZcr: Float,
        avgRms: Float,
        learnedPhrases: Set<String>
    ) {
        prefs.edit()
            .putBoolean(KEY_VOICE_TRAINED, true)
            .putFloat(KEY_OWNER_PITCH_HZ, avgPitchHz)
            .putFloat(KEY_OWNER_ZCR, avgZcr)
            .putFloat(KEY_OWNER_RMS, avgRms)
            .putStringSet(KEY_LEARNED_PHRASES, learnedPhrases)
            .apply()
    }

    fun getVoiceProfile(): VoiceProfileData {
        return VoiceProfileData(
            ownerName = getOwnerName(),
            isTrained = prefs.getBoolean(KEY_VOICE_TRAINED, false),
            onlyRespondToOwner = isOnlyRespondToOwnerVoice(),
            avgPitchHz = prefs.getFloat(KEY_OWNER_PITCH_HZ, 0f),
            avgZeroCrossingRate = prefs.getFloat(KEY_OWNER_ZCR, 0f),
            avgRmsAmplitude = prefs.getFloat(KEY_OWNER_RMS, 0f),
            learnedPhrases = prefs.getStringSet(KEY_LEARNED_PHRASES, emptySet()) ?: emptySet()
        )
    }
}
