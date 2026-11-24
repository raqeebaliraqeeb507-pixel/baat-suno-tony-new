package com.baatsunotony.assistant.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.baatsunotony.assistant.state.AssistantStateBus
import com.baatsunotony.assistant.state.CapturedNotification
import com.baatsunotony.assistant.state.ListeningPhase

class TonyNotificationListenerService : NotificationListenerService() {

    private var lastAnnouncedSignature: String = ""
    private var lastAnnouncedAt: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        AssistantStateBus.setNotificationListenerConnected(true)
        AssistantStateBus.addActionLog("NOTIFICATION", "Notification Listener connected", true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AssistantStateBus.setNotificationListenerConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras ?: return
        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifBlank { text }

        if (title.isBlank() && body.isBlank()) return

        val appLabel = resolveAppLabel(sbn.packageName)
        val signature = "${sbn.packageName}|$title|$body"
        val now = System.currentTimeMillis()

        // Deduplicate rapid duplicate notification updates
        if (signature == lastAnnouncedSignature && (now - lastAnnouncedAt) < 4000L) {
            return
        }
        lastAnnouncedSignature = signature
        lastAnnouncedAt = now

        val captured = CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appLabel,
            title = title.ifBlank { appLabel },
            body = body,
            timestamp = sbn.postTime
        )
        AssistantStateBus.addNotification(captured)

        val state = AssistantStateBus.uiState.value
        if (state.autoReadNotifications &&
            state.isServiceRunning &&
            state.phase != ListeningPhase.SPEAKING
        ) {
            val spokenMessage = buildString {
                append("$appLabel par ")
                if (title.isNotBlank()) {
                    append("$title ka message aaya hai")
                } else {
                    append("naya notification aaya hai")
                }
                if (body.isNotBlank()) {
                    append(": $body")
                }
            }
            AssistantStateBus.addActionLog("READ_NOTIFICATION", "Read $appLabel notification", true)
            AssistantStateBus.requestSpeak(spokenMessage, "ur")
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }
}
