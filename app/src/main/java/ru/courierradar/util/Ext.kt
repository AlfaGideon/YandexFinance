package ru.courierradar.util

import android.content.Context

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

fun Context.dpInt(value: Int): Int = dp(value.toFloat())
