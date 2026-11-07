package ru.courierradar.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.courierradar.MainActivity
import ru.courierradar.R
import ru.courierradar.calc.Calculator
import ru.courierradar.calc.Estimate
import ru.courierradar.calc.Verdict
import ru.courierradar.data.Prefs
import ru.courierradar.data.StatsRepo
import ru.courierradar.detect.AppWatcher
import ru.courierradar.parse.ParsedOrder
import ru.courierradar.signals.Signals
import ru.courierradar.util.Format
import ru.courierradar.util.dp
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service(), Signals.ForegroundListener, Signals.OrderListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: Prefs
    private lateinit var stats: StatsRepo
    private lateinit var windowManager: WindowManager
    private var watcher: AppWatcher? = null

    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var cardRoot: View? = null
    private var bubbleRoot: View? = null
    private var tvSum: TextView? = null
    private var tvVerdict: TextView? = null
    private var tvPerKm: TextView? = null
    private var tvPerHour: TextView? = null
    private var tvNet: TextView? = null
    private var tvRoute: TextView? = null
    private var tvHint: TextView? = null
    private var tvBubbleSum: TextView? = null
    private var tvBubbleRate: TextView? = null

    private var lastOrder: ParsedOrder? = null
    private var lastEstimate: Estimate? = null
    private var lastOrderKey: Int = 0
    private var hiddenByUser = false
    private var hideJob: Job? = null
    private var activePackage: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        stats = StatsRepo(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
        Signals.addForeground(this)
        Signals.addOrder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        watcher = watcher ?: AppWatcher(this) { pkg -> onForeground(pkg) }.also { it.start(scope) }
        when (intent?.action) {
            ACTION_SHOW_NOW -> {
                hiddenByUser = false
                showOverlay()
                setExpanded(true)
                render()
            }
            ACTION_HIDE_NOW -> {
                hiddenByUser = true
                removeOverlay()
            }
            else -> if (prefs.overlayAlways) {
                showOverlay()
                render()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Signals.removeForeground(this)
        Signals.removeOrder(this)
        watcher?.stop()
        watcher = null
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ signals

    override fun onForeground(packageName: String) {
        if (packageName == this.packageName) return
        if (packageName == "android" || packageName.startsWith("com.android.systemui")) return

        if (prefs.overlayAlways) {
            hideJob?.cancel()
            hideJob = null
            activePackage = packageName
            if (hiddenByUser) return
            showOverlay()
            render()
            return
        }

        val monitored = prefs.monitoredPackages().contains(packageName)
        if (monitored) {
            hideJob?.cancel()
            hideJob = null
            if (activePackage != packageName) hiddenByUser = false
            activePackage = packageName
            showOverlay()
            render()
        } else {
            activePackage = null
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(HIDE_DELAY_MS)
                removeOverlay()
            }
        }
    }

    override fun onOrder(order: ParsedOrder) {
        val payout = order.payout ?: return
        val distance = order.distanceKm ?: 0.0
        val time = order.timeMin ?: 0.0

        val key = (payout * 100).roundToInt() * 31 + (distance * 100).roundToInt() * 17 + time.roundToInt()
        if (key == lastOrderKey && lastEstimate != null) return
        lastOrderKey = key

        hiddenByUser = false
        lastOrder = order
        lastEstimate = Calculator.estimate(payout, distance, time, prefs.economy())

        showOverlay()
        render()
        setExpanded(true)

        val estimate = lastEstimate
        if (estimate != null && prefs.vibrateOnCheap && estimate.verdict != Verdict.TAKE) {
            vibrate(estimate.verdict == Verdict.SKIP)
        }
    }

    // ------------------------------------------------------------------ overlay

    private fun showOverlay() {
        if (hiddenByUser) return
        if (rootView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_root, null)
        bind(view)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX
            y = prefs.overlayY
        }

        val added = runCatching { windowManager.addView(view, lp) }.isSuccess
        if (!added) return

        rootView = view
        params = lp
        render()
    }

    private fun bind(view: View) {
        cardRoot = view.findViewById(R.id.cardRoot)
        bubbleRoot = view.findViewById(R.id.bubbleRoot)
        tvSum = view.findViewById(R.id.tvSum)
        tvVerdict = view.findViewById(R.id.tvVerdict)
        tvPerKm = view.findViewById(R.id.tvPerKm)
        tvPerHour = view.findViewById(R.id.tvPerHour)
        tvNet = view.findViewById(R.id.tvNet)
        tvRoute = view.findViewById(R.id.tvRoute)
        tvHint = view.findViewById(R.id.tvHint)
        tvBubbleSum = view.findViewById(R.id.tvBubbleSum)
        tvBubbleRate = view.findViewById(R.id.tvBubbleRate)

        view.findViewById<View>(R.id.btnCardClose).setOnClickListener {
            hiddenByUser = true
            removeOverlay()
        }
        view.findViewById<View>(R.id.btnTake).setOnClickListener { record(taken = true) }
        view.findViewById<View>(R.id.btnSkip).setOnClickListener { record(taken = false) }
        view.findViewById<View>(R.id.btnCalc).setOnClickListener { openCalculator() }

        bubbleRoot?.setOnTouchListener(BubbleTouchListener())
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        rootView = null
        params = null
        cardRoot = null
        bubbleRoot = null
        tvSum = null
        tvVerdict = null
        tvPerKm = null
        tvPerHour = null
        tvNet = null
        tvRoute = null
        tvHint = null
        tvBubbleSum = null
        tvBubbleRate = null
        runCatching { windowManager.removeView(view) }
    }

    private fun setExpanded(expanded: Boolean) {
        cardRoot?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun clearOrder() {
        lastOrder = null
        lastEstimate = null
        lastOrderKey = 0
        render()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val estimate = lastEstimate
        val order = lastOrder
        val payout = order?.payout

        tvSum?.text = if (payout != null) Format.money(payout) else "— ₽"

        val verdict = estimate?.verdict ?: Verdict.UNKNOWN
        val colorRes = colorFor(verdict)
        val color = ContextCompat.getColor(this, colorRes)

        tvVerdict?.let { chip ->
            chip.text = when {
                estimate == null -> "ЖДУ КАРТОЧКУ ЗАКАЗА"
                estimate.verdict == Verdict.UNKNOWN -> "НУЖНЫ КМ И МИНУТЫ"
                else -> "${estimate.verdict.title} · ${Format.perKm(estimate.score)}× порога"
            }
            chip.setTextColor(color)
        }

        tvPerKm?.text = if (estimate != null && estimate.totalDistanceKm > 0.05) {
            "${Format.perKm(estimate.perKmNet)} ₽"
        } else {
            "—"
        }
        tvPerHour?.text = if (estimate != null && estimate.timeMin > 0.5) {
            "${Format.rate(estimate.perHourNet)} ₽"
        } else {
            "—"
        }
        tvNet?.text = if (estimate != null) Format.money(estimate.net) else "—"
        tvRoute?.text = if (estimate != null && estimate.distanceKm > 0) {
            "${Format.distance(estimate.distanceKm)} · ${Format.minutes(estimate.timeMin)}"
        } else {
            "—"
        }
        tvHint?.text = hint(order, estimate)

        tvBubbleSum?.text = if (payout != null) "${payout.roundToInt()} ₽" else "₽"
        tvBubbleRate?.text = if (estimate != null && estimate.totalDistanceKm > 0.05) {
            "${Format.perKm(estimate.perKmNet)}/км"
        } else {
            "радар"
        }
        bubbleRoot?.background = bubbleBackground(colorRes)
    }

    private fun hint(order: ParsedOrder?, estimate: Estimate?): String = when {
        order == null ->
            "Открой карточку заказа — сумму и километры подтяну из экрана автоматически."
        order.distanceKm == null ->
            "Километры не разобрал. Нажми «Счёт» и введи их вручную."
        order.timeMin == null ->
            "Время не разобрал — ₽/час не считается, остальное верно."
        estimate != null && estimate.net < 0 ->
            "Убыточный заказ: расходы больше выплаты. Не бери."
        estimate != null ->
            "Чистыми = выплата − топливо ${Format.money(estimate.fuelCost)} − амортизация ${Format.money(estimate.wearCost)}" +
                if (estimate.commission > 0.5) " − комиссия ${Format.money(estimate.commission)}" else ""
        else -> ""
    }

    private fun bubbleBackground(colorRes: Int): GradientDrawable {
        val stroke = ContextCompat.getColor(this, colorRes)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@OverlayService, R.color.card))
            setStroke(dp(2.5f), stroke)
        }
    }

    private fun colorFor(verdict: Verdict): Int = when (verdict) {
        Verdict.TAKE -> R.color.good
        Verdict.MAYBE -> R.color.warn
        Verdict.SKIP -> R.color.bad
        Verdict.UNKNOWN -> R.color.muted
    }

    // ------------------------------------------------------------------ actions

    private fun record(taken: Boolean) {
        val estimate = lastEstimate
        stats.record(taken, estimate)
        val message = when {
            taken && estimate != null -> "Взял: ${Format.money(estimate.net)} чистыми, ${Format.rate(estimate.perHourNet)} ₽/час"
            taken -> "Взял: записал в статистику"
            estimate != null -> "Скип: сэкономил ${Format.rate(estimate.perHourNet)} ₽/час"
            else -> "Скип учтён"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setExpanded(false)
        clearOrder()
    }

    private fun openCalculator() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_SUM, lastOrder?.payout ?: 0.0)
            putExtra(MainActivity.EXTRA_KM, lastOrder?.distanceKm ?: 0.0)
            putExtra(MainActivity.EXTRA_MIN, lastOrder?.timeMin ?: 0.0)
        }
        runCatching { startActivity(intent) }
    }

    private fun vibrate(strong: Boolean) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = if (strong) longArrayOf(0, 120, 70, 120) else longArrayOf(0, 70)
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    // ------------------------------------------------------------------ touch

    private inner class BubbleTouchListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var paramX = 0
        private var paramY = 0
        private var downAt = 0L
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    paramX = lp.x
                    paramY = lp.y
                    downAt = SystemClock.uptimeMillis()
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = paramX + dx.toInt()
                        lp.y = paramY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view.rootView, lp) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val held = SystemClock.uptimeMillis() - downAt
                    if (dragging) {
                        prefs.overlayX = lp.x
                        prefs.overlayY = lp.y
                    } else if (held >= 450) {
                        hiddenByUser = true
                        removeOverlay()
                        Toast.makeText(
                            this@OverlayService,
                            "Виджет скрыт до следующего запуска приложения курьера",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        setExpanded(cardRoot?.visibility != View.VISIBLE)
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.notification_text)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "radar_overlay"
        private const val NOTIF_ID = 4711
        private const val HIDE_DELAY_MS = 1200L

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun showNow(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW_NOW)
            ContextCompat.startForegroundService(context, intent)
        }

        fun hideNow(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_HIDE_NOW)
            ContextCompat.startForegroundService(context, intent)
        }

        const val ACTION_SHOW_NOW = "ru.courierradar.action.SHOW_NOW"
        const val ACTION_HIDE_NOW = "ru.courierradar.action.HIDE_NOW"
    }
}
