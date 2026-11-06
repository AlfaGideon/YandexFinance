package ru.courierradar.data

import android.content.Context
import ru.courierradar.calc.Estimate
import java.time.LocalDate

data class PeriodStats(
    val taken: Int,
    val skipped: Int,
    val payout: Double,
    val net: Double,
    val distance: Double,
    val skippedNet: Double,
) {
    val avgPerKm: Double get() = if (distance > 0.05) net / distance else 0.0
    val avgPerOrder: Double get() = if (taken > 0) net / taken else 0.0
}

class StatsRepo(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("radar_stats", Context.MODE_PRIVATE)

    private fun ensureToday() {
        val today = LocalDate.now().toString()
        if (sp.getString(KEY_DAY, null) != today) {
            sp.edit()
                .putString(KEY_DAY, today)
                .putInt("d_taken", 0)
                .putInt("d_skipped", 0)
                .putFloat("d_payout", 0f)
                .putFloat("d_net", 0f)
                .putFloat("d_dist", 0f)
                .putFloat("d_skip_net", 0f)
                .apply()
        }
    }

    fun record(taken: Boolean, estimate: Estimate?) {
        ensureToday()
        val editor = sp.edit()
        if (taken) {
            editor.putInt("d_taken", sp.getInt("d_taken", 0) + 1)
            editor.putInt("t_taken", sp.getInt("t_taken", 0) + 1)
            if (estimate != null) {
                editor.putFloat("d_payout", sp.getFloat("d_payout", 0f) + estimate.payout.toFloat())
                editor.putFloat("d_net", sp.getFloat("d_net", 0f) + estimate.net.toFloat())
                editor.putFloat("d_dist", sp.getFloat("d_dist", 0f) + estimate.totalDistanceKm.toFloat())
                editor.putFloat("t_payout", sp.getFloat("t_payout", 0f) + estimate.payout.toFloat())
                editor.putFloat("t_net", sp.getFloat("t_net", 0f) + estimate.net.toFloat())
                editor.putFloat("t_dist", sp.getFloat("t_dist", 0f) + estimate.totalDistanceKm.toFloat())
            }
        } else {
            editor.putInt("d_skipped", sp.getInt("d_skipped", 0) + 1)
            editor.putInt("t_skipped", sp.getInt("t_skipped", 0) + 1)
            if (estimate != null) {
                editor.putFloat("d_skip_net", sp.getFloat("d_skip_net", 0f) + estimate.net.toFloat())
                editor.putFloat("t_skip_net", sp.getFloat("t_skip_net", 0f) + estimate.net.toFloat())
            }
        }
        editor.apply()
    }

    fun today(): PeriodStats {
        ensureToday()
        return PeriodStats(
            taken = sp.getInt("d_taken", 0),
            skipped = sp.getInt("d_skipped", 0),
            payout = sp.getFloat("d_payout", 0f).toDouble(),
            net = sp.getFloat("d_net", 0f).toDouble(),
            distance = sp.getFloat("d_dist", 0f).toDouble(),
            skippedNet = sp.getFloat("d_skip_net", 0f).toDouble(),
        )
    }

    fun allTime(): PeriodStats = PeriodStats(
        taken = sp.getInt("t_taken", 0),
        skipped = sp.getInt("t_skipped", 0),
        payout = sp.getFloat("t_payout", 0f).toDouble(),
        net = sp.getFloat("t_net", 0f).toDouble(),
        distance = sp.getFloat("t_dist", 0f).toDouble(),
        skippedNet = sp.getFloat("t_skip_net", 0f).toDouble(),
    )

    fun reset() {
        sp.edit().clear().apply()
        ensureToday()
    }

    private companion object {
        const val KEY_DAY = "day"
    }
}
