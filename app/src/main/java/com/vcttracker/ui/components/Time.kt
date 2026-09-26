package com.vcttracker.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Time {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun clock(instant: Instant, is24: Boolean): String =
        DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
            .format(instant.atZone(zone))

    fun day(instant: Instant): String {
        val date = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(date)
        }
    }

    fun full(instant: Instant, is24: Boolean): String =
        DateTimeFormatter.ofPattern(if (is24) "EEEE d MMMM · HH:mm" else "EEEE d MMMM · h:mm a", Locale.getDefault())
            .format(instant.atZone(zone))

    /** "in 3h 12m", "in 2d 4h", "starting" */
    fun until(instant: Instant, now: Instant = Instant.now()): String {
        val d = Duration.between(now, instant)
        if (d.isNegative || d.toMinutes() < 1) return "starting"
        val days = d.toDays()
        val hours = d.toHours() % 24
        val mins = d.toMinutes() % 60
        return when {
            days > 0 -> "in ${days}d ${hours}h"
            hours > 0 -> "in ${hours}h ${mins}m"
            else -> "in ${mins}m"
        }
    }

    fun ago(instant: Instant, now: Instant = Instant.now()): String {
        val d = Duration.between(instant, now)
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toHours() < 1 -> "${d.toMinutes()}m ago"
            d.toDays() < 1 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }
}

@Composable
fun is24Hour(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

/** Current time that ticks every [periodMs], for countdowns. */
@Composable
fun rememberNow(periodMs: Long = 30_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = Instant.now()
        }
    }
    return now
}
