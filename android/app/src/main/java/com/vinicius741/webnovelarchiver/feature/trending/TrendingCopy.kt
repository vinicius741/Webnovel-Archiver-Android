package com.vinicius741.webnovelarchiver.feature.trending

import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Movement
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Reading
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Signal
import com.vinicius741.webnovelarchiver.feature.details.formatCompactCount
import java.util.Locale
import kotlin.math.roundToLong

/** Plain-language lines for Trending rows. */
internal object TrendingCopy {
    /** "+80 patrons (+48%) in 12 days", or "Patrons rising" when the window shows no net gain. */
    fun leadLine(movement: Movement): String {
        val amount = amount(movement) ?: return "${movement.label} rising"
        val percent = percent(movement)?.let { " ($it)" }.orEmpty()
        return "$amount$percent ${period(movement.changeDays)}"
    }

    /** The lead rise without its period, for narrow rows: "+80 patrons (+48%)". */
    fun shortLine(movement: Movement): String {
        val amount = amount(movement) ?: return "${movement.label} rising"
        return amount + percent(movement)?.let { " ($it)" }.orEmpty()
    }

    /** A short chip for a supporting signal: "Rating +0.05", "+1.2K views". */
    fun chip(movement: Movement): String = amount(movement) ?: "${movement.label} rising"

    fun reading(reading: Reading): String =
        when (reading) {
            is Reading.NotStarted -> if (reading.chapters == 1) "Not started · 1 chapter" else "Not started · ${reading.chapters} chapters"
            is Reading.Behind -> if (reading.unread == 1) "1 chapter to read" else "${reading.unread} chapters to read"
        }

    /** The signal's name on chips and in the explanation. */
    fun signalName(signal: Signal): String =
        when (signal) {
            Signal.PATREON -> "Patreon"
            Signal.AUDIENCE -> "Readers"
            Signal.VIEWS -> "Views"
            Signal.RATING -> "Rating"
        }

    private fun amount(movement: Movement): String? {
        if (movement.change <= 0.0) return null
        return when (movement.signal) {
            Signal.RATING -> String.format(Locale.US, "Rating +%.2f", movement.change)
            Signal.VIEWS -> "+${formatCompactCount(movement.change.roundToLong())} views"
            Signal.PATREON, Signal.AUDIENCE -> {
                val count = movement.change.roundToLong()
                "+${formatCompactCount(count)} ${movement.label.lowercase(Locale.US)}"
            }
        }
    }

    /** "+48%" for counts, when the window started above zero and the rise is at least 1%. */
    private fun percent(movement: Movement): String? {
        if (movement.signal == Signal.RATING) return null
        val start = movement.latest - movement.change
        if (start <= 0.0) return null
        val pct = movement.change / start * PERCENT
        return if (pct < 1.0) null else "+${pct.roundToLong()}%"
    }

    private fun period(days: Double): String {
        val whole = days.roundToLong()
        return when {
            whole <= 0L -> "today"
            whole == 1L -> "in 1 day"
            else -> "in $whole days"
        }
    }

    private const val PERCENT = 100.0
}
