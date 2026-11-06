package ru.courierradar.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {

    /** 1 234 ₽ */
    fun money(value: Double): String {
        val rounded = value.roundToInt()
        return String.format(Locale("ru", "RU"), "%,d ₽", rounded).replace('\u00A0', ' ')
    }

    /** 18,5 */
    fun perKm(value: Double): String =
        if (abs(value) >= 10.0) String.format(Locale.US, "%.0f", value)
        else String.format(Locale.US, "%.1f", value).replace('.', ',')

    fun rate(value: Double): String =
        String.format(Locale.US, "%.0f", value)

    fun distance(km: Double): String =
        if (km >= 10.0) String.format(Locale.US, "%.0f км", km)
        else String.format(Locale.US, "%.1f км", km).replace('.', ',')

    fun minutes(min: Double): String {
        val total = min.roundToInt()
        if (total < 60) return "$total мин"
        val h = total / 60
        val m = total % 60
        return if (m == 0) "$h ч" else "$h ч $m мин"
    }
}
