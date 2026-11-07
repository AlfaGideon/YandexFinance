package ru.courierradar.detect

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ru.courierradar.data.Prefs
import ru.courierradar.parse.OrderParser
import ru.courierradar.signals.Signals

/**
 * Reads the visible text of the courier app window and publishes:
 *  - which package is in the foreground (so the overlay knows when to appear);
 *  - the parsed order card (sum / km / minutes).
 *
 * Everything is processed in memory. Nothing is stored or sent anywhere.
 */
class CourierAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private var lastForeground: String = ""
    private var lastParseAt = 0L
    private var lastHash = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        Signals.accessibilityConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return
        val type = event?.eventType ?: return

        val monitored = isMonitored(pkg)

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                lastForeground = pkg
                Signals.emitForeground(pkg)
                if (monitored) maybeParse()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (monitored) {
                    lastForeground = pkg
                    maybeParse()
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun isMonitored(pkg: String): Boolean = prefs.isMonitored(pkg)

    private fun maybeParse() {
        val now = SystemClock.uptimeMillis()
        if (now - lastParseAt < 350) return
        val root = rootInActiveWindow ?: return
        val lines = ArrayList<String>(96)
        collect(root, lines, 0)
        if (lines.isEmpty()) return

        val hash = lines.joinToString("|").hashCode()
        if (hash == lastHash) return
        lastHash = hash
        lastParseAt = now

        val order = OrderParser.parse(lines)
        if (order.usable) Signals.emitOrder(order)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40 || out.size > 300) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    override fun onDestroy() {
        Signals.accessibilityConnected = false
        super.onDestroy()
    }
}
