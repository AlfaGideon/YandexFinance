package ru.courierradar.detect

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fallback foreground detector based on UsageStats.
 *
 * The accessibility service is the primary source (it is instant), but some users
 * never enable accessibility, so we also poll usage events when the special
 * "usage access" permission is granted.
 */
class AppWatcher(
    private val context: Context,
    private val onForeground: (String) -> Unit,
) {

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        if (!hasUsageAccess(context)) return
        job = scope.launch {
            while (isActive) {
                val pkg = foregroundPackage(context)
                if (!pkg.isNullOrEmpty()) onForeground(pkg)
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val POLL_MS = 900L

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        }

        @Suppress("DEPRECATION")
        fun foregroundPackage(context: Context): String? {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 20_000, now) ?: return null
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = event.packageName
                }
            }
            return last
        }
    }
}
