package com.baatsunotony.assistant.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.baatsunotony.assistant.data.ApiKeyStore
import com.baatsunotony.assistant.service.TonyVoiceService
import com.baatsunotony.assistant.state.AssistantStateBus

class TonyUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_USER_PRESENT &&
            action != Intent.ACTION_SCREEN_ON &&
            action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val keyStore = ApiKeyStore(context)
        if (!keyStore.isAutoWakeEnabled()) return

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) return

        AssistantStateBus.addActionLog("AUTO_WAKE", "Device event ($action) ensured listener active", true)

        val serviceIntent = Intent(context, TonyVoiceService::class.java).apply {
            this.action = TonyVoiceService.ACTION_ENSURE_LISTENING
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            // Android 14+ may restrict background FGS start from some broadcast states unless already running
        }
    }
}
