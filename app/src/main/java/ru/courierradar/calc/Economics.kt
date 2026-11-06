package ru.courierradar.calc

import kotlin.math.max

enum class Verdict(val title: String) {
    TAKE("БРАТЬ"),
    MAYBE("НА ГРАНИ"),
    SKIP("СКИП"),
    UNKNOWN("НЕТ ДАННЫХ"),
}

data class EconomyParams(
    val fuelPer100: Double,
    val fuelPrice: Double,
    val wearPerKm: Double,
    val commissionPercent: Double,
    val returnTrip: Boolean,
    val minPerKmNet: Double,
    val minPerHourNet: Double,
)

data class Estimate(
    val payout: Double,
    val distanceKm: Double,
    val timeMin: Double,
    val totalDistanceKm: Double,
    val fuelCost: Double,
    val wearCost: Double,
    val commission: Double,
    val net: Double,
    val perKmNet: Double,
    val perHourGross: Double,
    val perHourNet: Double,
    val verdict: Verdict,
    val score: Double,
)

object Calculator {

    fun estimate(
        payout: Double,
        distanceKm: Double,
        timeMin: Double,
        params: EconomyParams,
    ): Estimate {
        val dist = max(distanceKm, 0.0)
        val time = max(timeMin, 0.0)
        val totalDistance = if (params.returnTrip) dist * 2 else dist

        val fuelCost = totalDistance / 100.0 * params.fuelPer100 * params.fuelPrice
        val wearCost = totalDistance * params.wearPerKm
        val commission = payout * (params.commissionPercent / 100.0)
        val net = payout - commission - fuelCost - wearCost

        val perKmNet = if (totalDistance > 0.05) net / totalDistance else 0.0
        val hours = time / 60.0
        val perHourGross = if (hours > 0.001) payout / hours else 0.0
        val perHourNet = if (hours > 0.001) net / hours else 0.0

        val verdict = when {
            dist <= 0.05 && time <= 0.5 -> Verdict.UNKNOWN
            else -> verdictOf(perKmNet, perHourNet, params)
        }

        val score = if (hours > 0.001 && totalDistance > 0.05) {
            val kmRatio = perKmNet / params.minPerKmNet.coerceAtLeast(1.0)
            val hourRatio = perHourNet / params.minPerHourNet.coerceAtLeast(1.0)
            (0.45 * kmRatio + 0.55 * hourRatio).coerceIn(0.0, 3.0)
        } else {
            0.0
        }

        return Estimate(
            payout = payout,
            distanceKm = dist,
            timeMin = time,
            totalDistanceKm = totalDistance,
            fuelCost = fuelCost,
            wearCost = wearCost,
            commission = commission,
            net = net,
            perKmNet = perKmNet,
            perHourGross = perHourGross,
            perHourNet = perHourNet,
            verdict = verdict,
            score = score,
        )
    }

    private fun verdictOf(perKmNet: Double, perHourNet: Double, p: EconomyParams): Verdict {
        val kmOk = perKmNet >= p.minPerKmNet
        val hourOk = perHourNet >= p.minPerHourNet
        return when {
            kmOk && hourOk -> Verdict.TAKE
            kmOk || hourOk -> Verdict.MAYBE
            perKmNet >= p.minPerKmNet * 0.75 || perHourNet >= p.minPerHourNet * 0.75 -> Verdict.MAYBE
            else -> Verdict.SKIP
        }
    }

    /** Сколько денег теряешь в час, если возьмёшь этот заказ вместо нормального. */
    fun hourlyLoss(est: Estimate, p: EconomyParams): Double {
        if (est.timeMin <= 0.5) return 0.0
        return max(0.0, p.minPerHourNet - est.perHourNet)
    }
}
