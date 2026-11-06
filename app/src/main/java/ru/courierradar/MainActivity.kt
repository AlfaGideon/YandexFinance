package ru.courierradar

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ru.courierradar.calc.Calculator
import ru.courierradar.calc.Estimate
import ru.courierradar.calc.Verdict
import ru.courierradar.data.Prefs
import ru.courierradar.data.StatsRepo
import ru.courierradar.detect.AppWatcher
import ru.courierradar.detect.CourierAccessibilityService
import ru.courierradar.overlay.OverlayService
import ru.courierradar.util.Format

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var stats: StatsRepo

    private lateinit var switchRadar: SwitchCompat
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccStatus: TextView
    private lateinit var tvUsageStatus: TextView
    private lateinit var btnOverlayPerm: TextView
    private lateinit var inputSum: EditText
    private lateinit var inputKm: EditText
    private lateinit var inputMin: EditText
    private lateinit var tvCalcVerdict: TextView
    private lateinit var tvCalcDetails: TextView
    private lateinit var tvToday: TextView
    private lateinit var tvAllTime: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        stats = StatsRepo(this)

        switchRadar = findViewById(R.id.switchRadar)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccStatus = findViewById(R.id.tvAccStatus)
        tvUsageStatus = findViewById(R.id.tvUsageStatus)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        inputSum = findViewById(R.id.inputSum)
        inputKm = findViewById(R.id.inputKm)
        inputMin = findViewById(R.id.inputMin)
        tvCalcVerdict = findViewById(R.id.tvCalcVerdict)
        tvCalcDetails = findViewById(R.id.tvCalcDetails)
        tvToday = findViewById(R.id.tvToday)
        tvAllTime = findViewById(R.id.tvAllTime)

        switchRadar.isChecked = prefs.radarEnabled

        switchRadar.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    switchRadar.isChecked = false
                    toast("Сначала разреши показ поверх других приложений")
                    openOverlayPermission()
                    return@setOnCheckedChangeListener
                }
                prefs.radarEnabled = true
                OverlayService.start(this)
                requestNotificationPermission()
            } else {
                prefs.radarEnabled = false
                OverlayService.stop(this)
            }
            refreshStatus()
        }

        findViewById<TextView>(R.id.btnOverlayPerm).setOnClickListener { openOverlayPermission() }
        findViewById<TextView>(R.id.btnAccPerm).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure { toast("Не удалось открыть настройки спец. возможностей") }
        }
        findViewById<TextView>(R.id.btnUsagePerm).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }.onFailure { toast("Не удалось открыть настройки доступа к данным об использовании") }
        }
        findViewById<TextView>(R.id.btnCalc).setOnClickListener { calculate() }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        prefillFromIntent()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        switchRadar.isChecked = prefs.radarEnabled
        refreshStatus()
        renderStats()
    }

    private fun prefillFromIntent() {
        val sum = intent?.getDoubleExtra(EXTRA_SUM, 0.0) ?: 0.0
        val km = intent?.getDoubleExtra(EXTRA_KM, 0.0) ?: 0.0
        val min = intent?.getDoubleExtra(EXTRA_MIN, 0.0) ?: 0.0
        if (sum > 0) inputSum.setText(trimNumber(sum))
        if (km > 0) inputKm.setText(trimNumber(km))
        if (min > 0) inputMin.setText(trimNumber(min))
        if (sum > 0) calculate()
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.1f", value)

    private fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { toast("Разреши «Поверх других приложений» в настройках вручную") }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFICATIONS,
        )
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = statusLine("Поверх других приложений", overlayOk)
        btnOverlayPerm.text = if (overlayOk) "Готово" else "Разрешить"

        val accOk = isAccessibilityEnabled()
        tvAccStatus.text = statusLine("Чтение карточки заказа", accOk)
        findViewById<TextView>(R.id.btnAccPerm).text = if (accOk) "Готово" else "Разрешить"

        val usageOk = AppWatcher.hasUsageAccess(this)
        tvUsageStatus.text = statusLine("Определение запуска приложений", usageOk)
        findViewById<TextView>(R.id.btnUsagePerm).text = if (usageOk) "Готово" else "Разрешить"
    }

    private fun statusLine(title: String, ok: Boolean): String =
        if (ok) "$title  ✓" else "$title  —"

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, CourierAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val parsed = ComponentName.unflattenFromString(entry) ?: continue
            if (parsed == expected) return true
        }
        return false
    }

    private fun calculate() {
        val sum = parseField(inputSum)
        val km = parseField(inputKm)
        val min = parseField(inputMin)

        if (sum <= 0.0) {
            toast("Введи сумму заказа")
            return
        }

        val estimate = Calculator.estimate(sum, km, min, prefs.economy())
        renderCalc(estimate)
    }

    private fun renderCalc(estimate: Estimate) {
        val color = when (estimate.verdict) {
            Verdict.TAKE -> R.color.good
            Verdict.MAYBE -> R.color.warn
            Verdict.SKIP -> R.color.bad
            Verdict.UNKNOWN -> R.color.muted
        }
        tvCalcVerdict.setTextColor(ContextCompat.getColor(this, color))
        tvCalcVerdict.text = when (estimate.verdict) {
            Verdict.TAKE -> "БРАТЬ — нормальная ставка"
            Verdict.MAYBE -> "НА ГРАНИ — решай по ситуации"
            Verdict.SKIP -> "СКИП — заказ дешёвый"
            Verdict.UNKNOWN -> "Добавь километры и минуты"
        }

        val rows = ArrayList<String>(6)
        rows.add("Чистыми на руки: ${Format.money(estimate.net)}")
        if (estimate.totalDistanceKm > 0.05) {
            rows.add("Ставка: ${Format.perKm(estimate.perKmNet)} ₽/км чистыми (${Format.distance(estimate.totalDistanceKm)})")
        }
        if (estimate.timeMin > 0.5) {
            rows.add("В час: ${Format.rate(estimate.perHourNet)} ₽ чистыми, грязными ${Format.rate(estimate.perHourGross)} ₽")
        }
        rows.add(
            "Расходы: топливо ${Format.money(estimate.fuelCost)}, " +
                "амортизация ${Format.money(estimate.wearCost)}" +
                if (estimate.commission > 0.5) ", комиссия ${Format.money(estimate.commission)}" else ""
        )
        rows.add("Твои пороги: ${Format.rate(prefs.minPerKmNet.toDouble())} ₽/км и ${Format.rate(prefs.minPerHourNet.toDouble())} ₽/час")

        val loss = Calculator.hourlyLoss(estimate, prefs.economy())
        if (loss > 1.0 && estimate.verdict != Verdict.TAKE) {
            rows.add("Такой заказ съедает ~${Format.money(loss)} дохода в час")
        }
        tvCalcDetails.text = rows.joinToString("\n")
    }

    private fun renderStats() {
        val today = stats.today()
        tvToday.text = buildString {
            append("Сегодня: взял ${today.taken} · скип ${today.skipped}\n")
            append("Чистыми ${Format.money(today.net)} · ${Format.perKm(today.avgPerKm)} ₽/км · ${Format.distance(today.distance)}\n")
            append("Средний заказ: ${Format.money(today.avgPerOrder)}")
        }
        val all = stats.allTime()
        tvAllTime.text = buildString {
            append("Всего: ${all.taken} взятых, ${all.skipped} скипов · ${Format.distance(all.distance)}\n")
            append("Чистыми за всё время: ${Format.money(all.net)} · ${Format.perKm(all.avgPerKm)} ₽/км")
        }
    }

    private fun parseField(field: EditText): Double =
        field.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SUM = "extra_sum"
        const val EXTRA_KM = "extra_km"
        const val EXTRA_MIN = "extra_min"
        private const val REQ_NOTIFICATIONS = 101
    }
}
