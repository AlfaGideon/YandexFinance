package ru.courierradar

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private lateinit var switchAlways: SwitchCompat
    private lateinit var switchYandex: SwitchCompat
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
        switchAlways = findViewById(R.id.switchAlways)
        switchYandex = findViewById(R.id.switchYandex)
        inputPackages = findViewById(R.id.inputPackages)

        fill()

        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            save()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<TextView>(R.id.btnFindApps).setOnClickListener { pickCourierApps() }

        findViewById<TextView>(R.id.btnReset).setOnClickListener {
            stats.reset()
            Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun pickCourierApps() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(0)
        val candidates = installed
            .filter { it.packageName != packageName }
            .filter { app ->
                val label = pm.getApplicationLabel(app).toString()
                app.packageName.contains("yandex", true) ||
                    app.packageName.contains("яндекс", true) ||
                    label.contains("яндекс", true) ||
                    label.contains("yandex", true)
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        if (candidates.isEmpty()) {
            Toast.makeText(this, "Приложения Яндекса не найдены", Toast.LENGTH_SHORT).show()
            return
        }

        val titles = candidates
            .map { "${pm.getApplicationLabel(it)}  ·  ${it.packageName}" }
            .toTypedArray()
        val current = prefs.monitoredPackages()
        val checked = candidates.map { current.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Под какими приложениями показывать виджет")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Сохранить") { _, _ ->
                val detected = candidates.map { it.packageName }.toSet()
                val picked = candidates.filterIndexed { index, _ -> checked[index] }.map { it.packageName }
                val manual = current - detected
                val result = picked + manual
                val value = if (result.isEmpty()) Prefs.DEFAULT_PACKAGES else result.joinToString(", ")
                prefs.monitoredPackagesRaw = value
                inputPackages.setText(value)
                Toast.makeText(
                    this,
                    "Выбрано приложений: ${picked.size + manual.size}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        switchAlways.isChecked = prefs.overlayAlways
        switchYandex.isChecked = prefs.matchAllYandex
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
        prefs.overlayAlways = switchAlways.isChecked
        prefs.matchAllYandex = switchYandex.isChecked
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
