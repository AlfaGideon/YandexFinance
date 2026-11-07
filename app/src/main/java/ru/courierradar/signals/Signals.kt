package ru.courierradar.signals

import ru.courierradar.parse.ParsedOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Very small in-process event bus: the accessibility service publishes what it sees,
 * the overlay service subscribes and renders it. No networking, no disk, no IPC.
 */
object Signals {

    fun interface ForegroundListener {
        fun onForeground(packageName: String)
    }

    fun interface OrderListener {
        fun onOrder(order: ParsedOrder)
    }

    private val foregroundListeners = CopyOnWriteArrayList<ForegroundListener>()
    private val orderListeners = CopyOnWriteArrayList<OrderListener>()

    @Volatile
    var accessibilityConnected: Boolean = false

    @Volatile
    var lastForegroundPackage: String = ""
        private set

    @Volatile
    var lastOrder: ParsedOrder? = null
        private set

    @Volatile
    var lastOrderAt: Long = 0L
        private set

    fun addForeground(listener: ForegroundListener) {
        foregroundListeners.addIfAbsent(listener)
    }

    fun removeForeground(listener: ForegroundListener) {
        foregroundListeners.remove(listener)
    }

    fun addOrder(listener: OrderListener) {
        orderListeners.addIfAbsent(listener)
    }

    fun removeOrder(listener: OrderListener) {
        orderListeners.remove(listener)
    }

    fun emitForeground(packageName: String) {
        lastForegroundPackage = packageName
        foregroundListeners.forEach { runCatching { it.onForeground(packageName) } }
    }

    fun emitOrder(order: ParsedOrder) {
        lastOrder = order
        lastOrderAt = System.currentTimeMillis()
        orderListeners.forEach { runCatching { it.onOrder(order) } }
    }
}
