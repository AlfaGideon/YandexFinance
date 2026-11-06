package ru.courierradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import ru.courierradar.data.Prefs
import ru.courierradar.overlay.OverlayService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = Prefs(context)
        if (!prefs.radarEnabled) return
        if (!Settings.canDrawOverlays(context)) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java),
            )
        }
    }
}
