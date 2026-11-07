package ru.courierradar

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import ru.courierradar.signals.Signals
import ru.courierradar.util.AccessibilityKeeper
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
    private lateinit var tvDiag: TextView

    private val diagHandler = Handler(Looper.getMainLooper())
    private val diagTicker = object : Runnable {
        override fun run() {
            updateDiagnostics()
            diagHandler.postDelayed(this, 1500L)
        }
    }

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
        tvDiag = findViewById(R.id.tvDiag)

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
        findViewById<TextView>(R.id.btnShowNow).setOnClickListener { showWidgetNow() }
        findViewById<TextView>(R.id.btnHideNow).setOnClickListener { OverlayService.hideNow(this) }
        findViewById<TextView>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<TextView>(R.id.btnAutoRestore).setOnClickListener { onAutoRestoreClick() }
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
        autoRestoreIfPossible()
        refreshStatus()
        renderStats()
        diagHandler.removeCallbacks(diagTicker)
        diagHandler.post(diagTicker)
    }

    override fun onPause() {
        diagHandler.removeCallbacks(diagTicker)
        super.onPause()
    }

    private fun showWidgetNow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала разреши «Поверх других приложений»")
            openOverlayPermission()
            return
        }
        prefs.radarEnabled = true
        switchRadar.isChecked = true
        OverlayService.showNow(this)
        toast("Виджет показан — ищи кружок на экране")
    }

    private fun updateDiagnostics() {
        val accEnabled = isAccessibilityEnabled()
        val accAlive = Signals.accessibilityConnected
        val overlayOk = Settings.canDrawOverlays(this)
        val registered = prefs.radarEnabled
        val live = AppWatcher.foregroundPackage(this)
        val seen = Signals.lastForegroundPackage
        val foreground = live ?: seen.ifEmpty { null }
        val monitored = prefs.monitoredPackages()
        val order = Signals.lastOrder

        val rows = ArrayList<String>(8)
        rows.add(mark(overlayOk) + "Поверх других приложений")
        rows.add(
            when {
                accEnabled && accAlive -> "✓ Спец. возможности: включены, служба работает"
                accEnabled -> "! Спец. возможности включены, но служба не подключена — перезагрузи телефон"
                else -> "— Спец. возможности выключены (см. подсказку ниже)"
            }
        )
        rows.add(mark(registered) + "Сервис виджета включён")
        rows.add(
            when {
                foreground == null -> "Приложение на экране: не определено (нужен доступ к данным об использовании)"
                monitored.contains(foreground) -> "Приложение на экране: $foreground ✓ в списке"
                prefs.overlayAlways -> "Приложение на экране: $foreground (режим «всегда» — не важно)"
                else -> "Приложение на экране: $foreground ✗ нет в списке — добавь в настройках"
            }
        )
        rows.add(
            if (order != null && Signals.lastOrderAt > 0) {
                "Последняя карточка: " + listOfNotNull(
                    order.payout?.let { Format.money(it) },
                    order.distanceKm?.let { Format.distance(it) },
                    order.timeMin?.let { Format.minutes(it) },
                ).joinToString(" · ")
            } else {
                "Последняя карточка: пока не распознана"
            }
        )
        rows.add(if (prefs.overlayAlways) "Режим «показывать всегда»: вкл" else "Режим «показывать всегда»: выкл")
        rows.add(
            if (AccessibilityKeeper.isIgnoringBatteryOptimizations(this)) {
                "✓ Батарея: без ограничений (процесс не так часто убивают)"
            } else {
                "— Батарея: ограничена — нажми «Работа в фоне»"
            }
        )
        rows.add(
            if (AccessibilityKeeper.canSelfRestore(this)) {
                "✓ Автовосстановление спец. возможностей: включено (ADB-право выдано)"
            } else {
                "— Автовосстановление: нужна одна ADB-команда (кнопка «Автовосстановление»)"
            }
        )

        if (!accEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rows.add("")
            rows.add("Android 13+: если в спец. возможностях написано «Ограниченные настройки» — Приложения → КурьерРадар → ⋮ → «Разрешить ограниченные настройки».")
        }
        tvDiag.text = rows.joinToString("\n")
    }

    private fun mark(ok: Boolean): String = if (ok) "✓ " else "— "

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

    private fun isAccessibilityEnabled(): Boolean = AccessibilityKeeper.isEnabled(this)

    private fun autoRestoreIfPossible() {
        if (!prefs.radarEnabled) return
        if (AccessibilityKeeper.isEnabled(this)) return
        if (!AccessibilityKeeper.canSelfRestore(this)) return
        AccessibilityKeeper.restore(this)
    }

    private fun requestBatteryExemption() {
        if (AccessibilityKeeper.isIgnoringBatteryOptimizations(this)) {
            toast("Уже разрешено: приложение не ограничено в фоне")
            return
        }
        runCatching { startActivity(AccessibilityKeeper.batteryRequestIntent(this)) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
    }

    private fun onAutoRestoreClick() {
        if (AccessibilityKeeper.canSelfRestore(this)) {
            val ok = AccessibilityKeeper.restore(this)
            toast(
                if (ok) "Спец. возможности восстановлены"
                else "Не получилось — включи вручную в настройках"
            )
            refreshStatus()
            updateDiagnostics()
            return
        }

        val command = "adb shell pm grant $packageName ${AccessibilityKeeper.WRITE_SECURE_SETTINGS}"
        AlertDialog.Builder(this)
            .setTitle("Автовосстановление спец. возможностей")
            .setMessage(
                "Android снимает разрешение, когда система убивает процесс (в том числе при " +
                    "«Очистить всё»). Одноразовая ADB-команда даёт приложению право вернуть " +
                    "разрешение самому — через ~7 секунд после очистки всё снова работает.\n\n" +
                    command + "\n\n" +
                    "Как выполнить: подключи телефон по USB с включённой отладкой и запусти " +
                    "команду на компьютере. Без компьютера можно использовать приложение " +
                    "Shizuku + любой ADB-терминал.\n\n" +
                    "Без ADB остаётся только включать вручную в настройках."
            )
            .setPositiveButton("Скопировать команду") { _, _ -> copyCommand(command) }
            .setNeutralButton("Настройки") { _, _ ->
                runCatching { startActivity(AccessibilityKeeper.settingsIntent()) }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun copyCommand(command: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("adb", command))
        toast("Команда скопирована")
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
