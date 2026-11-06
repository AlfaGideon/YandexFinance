package ru.courierradar.parse

/**
 * Best-effort parser for the text of an order card shown by a courier app.
 *
 * Courier clients re-render their cards often, so nothing here is bound to one
 * exact layout: we collect every text node of the active window, pull out money,
 * kilometres and minutes, and drop tokens that obviously belong to the wallet or
 * shift summary rather than to the order itself.
 */
data class ParsedOrder(
    val payout: Double?,
    val distanceKm: Double?,
    val timeMin: Double?,
    val raw: String = "",
) {
    val usable: Boolean get() = payout != null && payout > 0

    val confidence: Int
        get() {
            var c = 0
            if (payout != null) c += 40
            if (distanceKm != null) c += 30
            if (timeMin != null) c += 30
            return c
        }
}

object OrderParser {

    private val moneySuffix = Regex(
        """(\d{1,3}(?:[ \u00A0\u202F]\d{3})+|\d{1,6})(?:[.,](\d{1,2}))?\s*(?:₽|руб\.?|р\.)""",
        RegexOption.IGNORE_CASE,
    )

    private val moneyPrefix = Regex(
        """(?:₽|rub)\s*(\d{1,6})""",
        RegexOption.IGNORE_CASE,
    )

    private val kmRegex = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*(?:км|km)\b""", RegexOption.IGNORE_CASE)
    private val minRegex = Regex("""(\d{1,3})\s*(?:мин|min)\b""", RegexOption.IGNORE_CASE)
    private val hourRegex = Regex("""(\d{1,2})\s*(?:ч\b|час(?:а|ов)?\b)""", RegexOption.IGNORE_CASE)

    private val noise = listOf(
        "баланс", "вывести", "вывод", "кошел", "счёт", "счет", "смен", "за сегодня",
        "за неделю", "заработано", "итог", "пособие", "бонус", "поддержк", "чаевые",
        "статистик", "уровень", "тариф", "страховк", "штраф",
    )

    fun parse(lines: List<String>): ParsedOrder {
        val text = lines.joinToString(" | ")
        val payout = pickPayout(text)
        val distance = pickDistance(text)
        val time = pickTime(text)
        return ParsedOrder(payout, distance, time, text.take(400))
    }

    fun parseSingle(text: String): ParsedOrder = parse(listOf(text))

    private fun pickPayout(text: String): Double? {
        val candidates = ArrayList<Pair<Double, Boolean>>() // value -> "clean" (not near wallet/shift text)

        for (m in moneySuffix.findAll(text)) {
            val digits = m.groupValues[1].replace(Regex("[ \u00A0\u202F]"), "")
            val fraction = m.groupValues[2]
            val value = (digits + if (fraction.isNotEmpty()) ".$fraction" else "").toDoubleOrNull() ?: continue
            if (value < 30.0 || value > 20000.0) continue
            candidates.add(value to isClean(text, m.range.first, m.range.last))
        }
        for (m in moneyPrefix.findAll(text)) {
            val value = m.groupValues[1].toDoubleOrNull() ?: continue
            if (value < 30.0 || value > 20000.0) continue
            candidates.add(value to isClean(text, m.range.first, m.range.last))
        }

        if (candidates.isEmpty()) return null

        val clean = candidates.filter { it.second }.map { it.first }
        val pool = if (clean.isNotEmpty()) clean else candidates.map { it.first }
        val orderSized = pool.filter { it <= 6000.0 }
        return if (orderSized.isNotEmpty()) orderSized.max() else pool.min()
    }

    private fun isClean(text: String, start: Int, end: Int): Boolean {
        val from = (start - 30).coerceAtLeast(0)
        val to = (end + 12).coerceAtMost(text.length)
        val context = text.substring(from, to).lowercase()
        return noise.none { context.contains(it) }
    }

    private fun pickDistance(text: String): Double? {
        val values = kmRegex.findAll(text)
            .mapNotNull { parseNumber(it.groupValues[1]) }
            .filter { it in 0.1..90.0 }
            .toList()
        return values.maxOrNull()
    }

    private fun pickTime(text: String): Double? {
        val minutes = minRegex.findAll(text)
            .mapNotNull { parseNumber(it.groupValues[1]) }
            .filter { it in 1.0..240.0 }
            .toList()
        if (minutes.isNotEmpty()) return minutes.max()

        val hours = hourRegex.findAll(text)
            .mapNotNull { parseNumber(it.groupValues[1]) }
            .filter { it in 1.0..8.0 }
            .toList()
        return hours.maxOrNull()?.times(60)
    }

    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()
}
