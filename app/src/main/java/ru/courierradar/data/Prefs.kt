package ru.courierradar.data

import android.content.Context
import android.content.SharedPreferences
import ru.courierradar.calc.EconomyParams

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)

    var radarEnabled: Boolean
        get() = sp.getBoolean("radar_enabled", false)
        set(value) = sp.edit().putBoolean("radar_enabled", value).apply()

    var fuelPer100: Float
        get() = sp.getFloat("fuel_per_100", 8.0f)
        set(value) = sp.edit().putFloat("fuel_per_100", value).apply()

    var fuelPrice: Float
        get() = sp.getFloat("fuel_price", 62.0f)
        set(value) = sp.edit().putFloat("fuel_price", value).apply()

    var wearPerKm: Float
        get() = sp.getFloat("wear_per_km", 2.5f)
        set(value) = sp.edit().putFloat("wear_per_km", value).apply()

    var commissionPercent: Float
        get() = sp.getFloat("commission", 0.0f)
        set(value) = sp.edit().putFloat("commission", value).apply()

    var returnTrip: Boolean
        get() = sp.getBoolean("return_trip", false)
        set(value) = sp.edit().putBoolean("return_trip", value).apply()

    var minPerKmNet: Float
        get() = sp.getFloat("min_per_km", 25.0f)
        set(value) = sp.edit().putFloat("min_per_km", value).apply()

    var minPerHourNet: Float
        get() = sp.getFloat("min_per_hour", 450.0f)
        set(value) = sp.edit().putFloat("min_per_hour", value).apply()

    var vibrateOnCheap: Boolean
        get() = sp.getBoolean("vibrate_cheap", true)
        set(value) = sp.edit().putBoolean("vibrate_cheap", value).apply()

    var overlayX: Int
        get() = sp.getInt("overlay_x", 24)
        set(value) = sp.edit().putInt("overlay_x", value).apply()

    var overlayY: Int
        get() = sp.getInt("overlay_y", 260)
        set(value) = sp.edit().putInt("overlay_y", value).apply()

    var monitoredPackagesRaw: String
        get() = sp.getString("packages", DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        set(value) = sp.edit().putString("packages", value).apply()

    fun monitoredPackages(): Set<String> = monitoredPackagesRaw
        .split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun economy() = EconomyParams(
        fuelPer100 = fuelPer100.toDouble(),
        fuelPrice = fuelPrice.toDouble(),
        wearPerKm = wearPerKm.toDouble(),
        commissionPercent = commissionPercent.toDouble(),
        returnTrip = returnTrip,
        minPerKmNet = minPerKmNet.toDouble(),
        minPerHourNet = minPerHourNet.toDouble(),
    )

    companion object {
        const val DEFAULT_PACKAGES =
            "ru.yandex.taxi, ru.yandex.ytaxi, ru.yandex.taxi.courier, ru.yandex.taxi.driver, " +
                "ru.yandex.pro, ru.yandex.lavka.courier, com.yandex.courier, ru.yandex.go.courier"
    }
}
