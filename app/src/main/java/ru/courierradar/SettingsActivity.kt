package ru.courierradar

import android.os.Bundle
import android.widget.EditText
import android.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.courierradar.data.Prefs
import ru.courierradar.data.StatsRepo

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var stats: StatsRepo

    private lateinit var inputMinPerKm: EditText
    private lateinit var inputMinPerHour: EditText
    private lateinit var inputFuel: EditText
    private lateinit var inputFuelPrice: EditText
    private lateinit var inputWear: EditText
    private lateinit var inputCommission: EditText
    private lateinit var switchReturn: SwitchCompat
    private lateinit var switchVibrate: SwitchCompat
    private lateinit var inputPackages: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)
        stats = StatsRepo(this)

        inputMinPerKm = findViewById(R.id.inputMinPerKm)
        inputMinPerHour = findViewById(R.id.inputMinPerHour)
        inputFuel = findViewById(R.id.inputFuel)
        inputFuelPrice = findViewById(R.id.inputFuelPrice)
        inputWear = findViewById(R.id.inputWear)
        inputCommission = findViewById(R.id.inputCommission)
        switchReturn = findViewById(R.id.switchReturn)
        switchVibrate = findViewById(R.id.switchVibrate)
        inputPackages = findViewById(R.id.inputPackages)

        fill()

        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            save()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<TextView>(R.id.btnReset).setOnClickListener {
            stats.reset()
            Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fill() {
        inputMinPerKm.setText(num(prefs.minPerKmNet.toDouble()))
        inputMinPerHour.setText(num(prefs.minPerHourNet.toDouble()))
        inputFuel.setText(num(prefs.fuelPer100.toDouble()))
        inputFuelPrice.setText(num(prefs.fuelPrice.toDouble()))
        inputWear.setText(num(prefs.wearPerKm.toDouble()))
        inputCommission.setText(num(prefs.commissionPercent.toDouble()))
        switchReturn.isChecked = prefs.returnTrip
        switchVibrate.isChecked = prefs.vibrateOnCheap
        inputPackages.setText(prefs.monitoredPackagesRaw)
    }

    private fun save() {
        prefs.minPerKmNet = read(inputMinPerKm, prefs.minPerKmNet.toDouble()).toFloat()
        prefs.minPerHourNet = read(inputMinPerHour, prefs.minPerHourNet.toDouble()).toFloat()
        prefs.fuelPer100 = read(inputFuel, prefs.fuelPer100.toDouble()).toFloat()
        prefs.fuelPrice = read(inputFuelPrice, prefs.fuelPrice.toDouble()).toFloat()
        prefs.wearPerKm = read(inputWear, prefs.wearPerKm.toDouble()).toFloat()
        prefs.commissionPercent = read(inputCommission, prefs.commissionPercent.toDouble()).toFloat()
        prefs.returnTrip = switchReturn.isChecked
        prefs.vibrateOnCheap = switchVibrate.isChecked
        val packages = inputPackages.text?.toString()?.trim().orEmpty()
        prefs.monitoredPackagesRaw = packages.ifEmpty { Prefs.DEFAULT_PACKAGES }
    }

    private fun read(field: EditText, fallback: Double): Double {
        val raw = field.text?.toString()?.trim()?.replace(',', '.') ?: return fallback
        val value = raw.toDoubleOrNull() ?: return fallback
        return if (value < 0.0) fallback else value
    }

    private fun num(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.2f", value)
}
