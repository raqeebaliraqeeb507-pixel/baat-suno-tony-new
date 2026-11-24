package com.baatsunotony.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.baatsunotony.assistant.state.AssistantStateBus
import java.lang.ref.WeakReference

class TonyAccessibilityService : AccessibilityService() {

    companion object {
        private var instanceRef: WeakReference<TonyAccessibilityService>? = null

        fun getInstance(): TonyAccessibilityService? = instanceRef?.get()

        fun isConnected(): Boolean = getInstance() != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        AssistantStateBus.setAccessibilityConnected(true)
        AssistantStateBus.addActionLog("ACCESSIBILITY", "Accessibility Service connected", true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Actions are invoked on demand from voice and offline command engines
    }

    override fun onInterrupt() {
        AssistantStateBus.setAccessibilityConnected(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceRef = null
        AssistantStateBus.setAccessibilityConnected(false)
    }

    fun openAppByName(requestedName: String): Boolean {
        val query = requestedName.trim().lowercase()
        if (query.isEmpty()) return false

        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolvedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        val exactOrPartial = resolvedApps.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            val pkg = resolveInfo.activityInfo.packageName.lowercase()
            label == query || label.contains(query) || pkg.contains(query)
        }

        if (exactOrPartial != null) {
            val launchIntent = pm.getLaunchIntentForPackage(exactOrPartial.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                val appLabel = exactOrPartial.loadLabel(pm).toString()
                AssistantStateBus.addActionLog("OPEN_APP", "Opened $appLabel", true)
                return true
            }
        }

        AssistantStateBus.addActionLog("OPEN_APP", "App not found: $requestedName", false)
        return false
    }

    fun scrollScreen(forward: Boolean): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findFirstScrollableNode(root)
            if (scrollable != null) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (scrollable.performAction(action)) {
                    AssistantStateBus.addActionLog(
                        if (forward) "SCROLL_DOWN" else "SCROLL_UP",
                        "Scrolled active window",
                        true
                    )
                    return true
                }
            }
        }

        return performScrollGesture(forward)
    }

    fun clickByText(targetText: String): Boolean {
        val query = targetText.trim()
        if (query.isEmpty()) return false
        val root = rootInActiveWindow ?: return false

        val matchedNodes = root.findAccessibilityNodeInfosByText(query)
        for (node in matchedNodes) {
            if (clickNodeOrParent(node)) {
                AssistantStateBus.addActionLog("CLICK_TEXT", "Clicked \"$query\"", true)
                return true
            }
        }

        val fuzzyMatch = findNodeByTextOrDescription(root, query.lowercase())
        if (fuzzyMatch != null && clickNodeOrParent(fuzzyMatch)) {
            AssistantStateBus.addActionLog("CLICK_TEXT", "Clicked \"$query\"", true)
            return true
        }

        AssistantStateBus.addActionLog("CLICK_TEXT", "Could not find \"$query\" on screen", false)
        return false
    }

    fun goBack(): Boolean {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        AssistantStateBus.addActionLog("GO_BACK", "Navigated back", ok)
        return ok
    }

    fun goHome(): Boolean {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        AssistantStateBus.addActionLog("GO_HOME", "Returned to Home screen", ok)
        return ok
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val tapPath = Path().apply {
                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            }
            val stroke = GestureDescription.StrokeDescription(tapPath, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByTextOrDescription(
        node: AccessibilityNodeInfo,
        lowerQuery: String
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.lowercase().orEmpty()
        val nodeDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (nodeText.contains(lowerQuery) || nodeDesc.contains(lowerQuery)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNodeByTextOrDescription(child, lowerQuery)
            if (match != null) return match
        }
        return null
    }

    private fun performScrollGesture(forward: Boolean): Boolean {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val centerX = metrics.widthPixels / 2f
        val startY = if (forward) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.30f
        val endY = if (forward) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.75f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 320L))
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        AssistantStateBus.addActionLog(
            if (forward) "SCROLL_DOWN" else "SCROLL_UP",
            if (dispatched) "Swiped screen" else "Gesture failed",
            dispatched
        )
        return dispatched
    }
}
