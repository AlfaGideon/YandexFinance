package ru.courierradar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import ru.courierradar.detect.CourierAccessibilityService

/**
 * Everything about the accessibility permission lifecycle.
 *
 * Android disables an accessibility service when its app gets force-stopped
 * (that is exactly what "clear all background apps" does on many ROMs).
 * The system does not let an app re-enable itself... unless the app holds
 * WRITE_SECURE_SETTINGS, which can be granted once via ADB:
 *
 *   adb shell pm grant ru.courierradar android.permission.WRITE_SECURE_SETTINGS
 *
 * With that granted the service is restored automatically within seconds.
 */
object AccessibilityKeeper {

    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    fun component(context: Context): ComponentName =
        ComponentName(context, CourierAccessibilityService::class.java)

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = component(context)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == expected) return true
        }
        return false
    }

    fun canSelfRestore(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun restore(context: Context): Boolean {
        if (!canSelfRestore(context)) return false
        if (isEnabled(context)) return true
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val flat = component(context).flattenToString()
        val updated = if (current.isBlank()) flat else "$current:$flat"
        val added = Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            updated,
        )
        val switched = Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        return added && switched
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryRequestIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
}
