package com.baatsunotony.assistant.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.baatsunotony.assistant.accessibility.TonyAccessibilityService
import com.baatsunotony.assistant.ai.TonyAction
import com.baatsunotony.assistant.ai.TonyActionType
import com.baatsunotony.assistant.ai.TonyReply
import com.baatsunotony.assistant.data.ApiKeyStore
import com.baatsunotony.assistant.hardware.FlashlightController
import com.baatsunotony.assistant.state.AssistantStateBus

data class OfflineExecutionResult(
    val handled: Boolean,
    val reply: TonyReply? = null,
    val actionSummary: String? = null
)

class OfflineCommandEngine(
    private val context: Context,
    private val flashlightController: FlashlightController,
    private val apiKeyStore: ApiKeyStore
) {

    companion object {
        private const val CREATOR_IDENTITY_URDU =
            "Mujhe Dr. Raqeeb Ali wald Muhammad Boota ne banaya hai, jo Zila Bahawalnagar, Tehsil Haroonabad ke Chak Number 117 Six-R (117/6R) mein rehte hain."
    }

    fun tryExecuteOffline(rawCommand: String): OfflineExecutionResult {
        val text = rawCommand.trim()
        val lower = text.lowercase()

        // 0A. Creator Identity Recognition (Offline + Instant)
        if (lower.contains("kis ne banaya") ||
            lower.contains("kisne banaya") ||
            lower.contains("who made you") ||
            lower.contains("who created you") ||
            lower.contains("creator kaun") ||
            lower.contains("banaya hai") ||
            lower.contains("کس نے بنایا")
        ) {
            AssistantStateBus.addActionLog("CREATOR_IDENTITY", "Responded with creator identity", true)
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(CREATOR_IDENTITY_URDU, "ur", TonyAction(TonyActionType.NONE)),
                actionSummary = "Creator Identity"
            )
        }

        // 0B. Owner & Device Recognition (Offline + Instant)
        if (lower.contains("kis ke mobile") ||
            lower.contains("kiske mobile") ||
            lower.contains("kis ka phone") ||
            lower.contains("kiska phone") ||
            lower.contains("main kaun hoon") ||
            lower.contains("main kon hu") ||
            lower.contains("who am i") ||
            lower.contains("whose phone") ||
            lower.contains("کس کے موبائل") ||
            lower.contains("میں کون ہوں")
        ) {
            val ownerName = apiKeyStore.getOwnerName()
            val ownerReply =
                "Main is waqt $ownerName ke mobile mein chal raha hoon aur unhi ki awaaz par kaam karta hoon."
            AssistantStateBus.addActionLog("OWNER_IDENTITY", "Verified owner: $ownerName", true)
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(ownerReply, "ur", TonyAction(TonyActionType.NONE)),
                actionSummary = "Owner: $ownerName"
            )
        }

        // 1. Flashlight On / Off
        val mentionsFlash = listOf("flashlight", "flash light", "torch", "flash", "ٹارچ", "لائٹ")
            .any { lower.contains(it) }
        if (mentionsFlash) {
            val turnOff = listOf("off", "band", "bujha", "close", "stop", "بند")
                .any { lower.contains(it) }
            val turnOn = listOf("on", "jala", "start", "khol", "open", "چلا", "آن")
                .any { lower.contains(it) }

            if (turnOff && !turnOn) {
                val ok = flashlightController.setFlashlight(false)
                val replyText = if (ok) "Flashlight band kar di hai." else "Flashlight control available nahi hai."
                return OfflineExecutionResult(
                    handled = true,
                    reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.FLASHLIGHT_OFF)),
                    actionSummary = if (ok) "Flashlight OFF" else "Flashlight unavailable"
                )
            }
            if (turnOn || !turnOff) {
                val ok = flashlightController.setFlashlight(true)
                val replyText = if (ok) "Flashlight on kar di hai." else "Flashlight control available nahi hai."
                return OfflineExecutionResult(
                    handled = true,
                    reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.FLASHLIGHT_ON)),
                    actionSummary = if (ok) "Flashlight ON" else "Flashlight unavailable"
                )
            }
        }

        // 2. Scroll Up / Down
        if (lower.contains("scroll down") ||
            lower.contains("neeche scroll") ||
            lower.contains("niche scroll") ||
            lower.contains("swipe down") ||
            lower.contains("نیچے")
        ) {
            val ok = TonyAccessibilityService.getInstance()?.scrollScreen(forward = true) ?: false
            val replyText = if (ok) "Neeche scroll kar diya hai." else "Scroll ke liye Accessibility Service enable karein."
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.SCROLL_DOWN)),
                actionSummary = if (ok) "Scrolled down (Offline)" else "Accessibility required"
            )
        }

        if (lower.contains("scroll up") ||
            lower.contains("oopar scroll") ||
            lower.contains("upar scroll") ||
            lower.contains("swipe up") ||
            lower.contains("اوپر")
        ) {
            val ok = TonyAccessibilityService.getInstance()?.scrollScreen(forward = false) ?: false
            val replyText = if (ok) "Oopar scroll kar diya hai." else "Scroll ke liye Accessibility Service enable karein."
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.SCROLL_UP)),
                actionSummary = if (ok) "Scrolled up (Offline)" else "Accessibility required"
            )
        }

        // 3. Go Back / Go Home
        if (lower == "back" ||
            lower.contains("go back") ||
            lower.contains("wapas") ||
            lower.contains("piche") ||
            lower.contains("back karo") ||
            lower.contains("واپس")
        ) {
            val ok = TonyAccessibilityService.getInstance()?.goBack() ?: false
            val replyText = if (ok) "Wapas aa gaya hoon." else "Back action ke liye Accessibility enable karein."
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.GO_BACK)),
                actionSummary = if (ok) "Navigated Back (Offline)" else "Accessibility required"
            )
        }

        if (lower.contains("go home") ||
            lower.contains("home screen") ||
            lower == "home" ||
            lower.contains("home par jao") ||
            lower.contains("ہوم")
        ) {
            val ok = TonyAccessibilityService.getInstance()?.goHome() ?: false
            val replyText = if (ok) "Home screen par aa gaya hoon." else "Home action ke liye Accessibility enable karein."
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.GO_HOME)),
                actionSummary = if (ok) "Returned Home (Offline)" else "Accessibility required"
            )
        }

        // 4. Read Notifications / Messages
        if (lower.contains("notification") ||
            lower.contains("messages parh") ||
            lower.contains("message parh") ||
            lower.contains("message suna") ||
            lower.contains("read message") ||
            lower.contains("read my messages") ||
            lower.contains("پیغام")
        ) {
            val notifications = AssistantStateBus.uiState.value.recentNotifications
            val replyText = if (notifications.isEmpty()) {
                "Abhi koi naya notification ya message nahi aaya."
            } else {
                val latest = notifications.first()
                "${latest.appName} par ${latest.title} ka message aaya hai: ${latest.body}"
            }
            AssistantStateBus.addActionLog("READ_NOTIFICATIONS", "Offline read aloud", true)
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.READ_NOTIFICATIONS)),
                actionSummary = if (notifications.isEmpty()) "No unread notifications" else "Read ${notifications.first().appName} (Offline)"
            )
        }

        // 5. Click / Tap target on screen
        val clickTarget = extractClickTarget(text)
        if (clickTarget != null) {
            val ok = TonyAccessibilityService.getInstance()?.clickByText(clickTarget) ?: false
            val replyText = if (ok) {
                "$clickTarget par click kar diya hai."
            } else {
                "$clickTarget screen par nahi mila ya Accessibility off hai."
            }
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.CLICK_TEXT, clickTarget)),
                actionSummary = if (ok) "Clicked $clickTarget (Offline)" else "Click failed"
            )
        }

        // 6. Open Installed App
        val appTarget = extractAppToOpen(text)
        if (appTarget != null) {
            val openedViaAccessibility = TonyAccessibilityService.getInstance()?.openAppByName(appTarget) == true
            val opened = openedViaAccessibility || launchInstalledAppDirectly(appTarget)
            val replyText = if (opened) {
                "Ji bilkul, $appTarget khol raha hoon."
            } else {
                "Mujhe $appTarget app phone mein nahi mila."
            }
            return OfflineExecutionResult(
                handled = true,
                reply = TonyReply(replyText, "ur", TonyAction(TonyActionType.OPEN_APP, appTarget)),
                actionSummary = if (opened) "Opened $appTarget (Offline)" else "App not found: $appTarget"
            )
        }

        return OfflineExecutionResult(handled = false)
    }

    private fun extractClickTarget(input: String): String? {
        val lower = input.lowercase()
        val urduMatch = Regex("^(.+?)\\s+(?:par|pe)\\s+(?:click|tap|dabao)(?:\\s+karo)?$", RegexOption.IGNORE_CASE)
            .find(input)
        if (urduMatch != null) {
            return urduMatch.groupValues[1].trim()
        }
        val enMatch = Regex("^(?:click|tap|press)\\s+(?:on\\s+)?(.+)$", RegexOption.IGNORE_CASE)
            .find(lower)
        if (enMatch != null) {
            return enMatch.groupValues[1].trim()
        }
        return null
    }

    private fun extractAppToOpen(input: String): String? {
        val cleaned = input.trim()
        val enRegex = Regex("^(?:open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE)
        enRegex.find(cleaned)?.let { match ->
            val candidate = match.groupValues[1]
                .replace(Regex("\\b(app|please)\\b", RegexOption.IGNORE_CASE), "")
                .trim()
            if (candidate.isNotEmpty()) return candidate
        }

        val urRegex = Regex(
            "^(.+?)\\s+(?:kholo|khol|chalao|chala|on karo|open karo|kholein|کھولو|چلاؤ)$",
            RegexOption.IGNORE_CASE
        )
        urRegex.find(cleaned)?.let { match ->
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotEmpty() && !candidate.lowercase().contains("flashlight") && !candidate.lowercase().contains("torch")) {
                return candidate
            }
        }

        return null
    }

    private fun launchInstalledAppDirectly(requestedName: String): Boolean {
        val query = requestedName.trim().lowercase()
        if (query.isEmpty()) return false

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolvedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        val match = resolvedApps.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            val pkg = resolveInfo.activityInfo.packageName.lowercase()
            label == query || label.contains(query) || pkg.contains(query)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        val appLabel = match.loadLabel(pm).toString()
        AssistantStateBus.addActionLog("OPEN_APP", "Opened $appLabel (Offline)", true)
        return true
    }
}
