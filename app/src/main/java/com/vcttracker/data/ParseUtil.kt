package com.vcttracker.data

import org.jsoup.nodes.Element
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val VLR = "https://www.vlr.gg"

/** vlr.gg renders list times for guests in US Central time. */
internal val VLR_LIST_ZONE: ZoneId = ZoneId.of("America/Chicago")

/** The "data-utc-ts" strings on match pages are actually US Eastern wall time. */
internal val VLR_MATCH_ZONE: ZoneId = ZoneId.of("America/New_York")

private val PLACEHOLDER_IMAGES = listOf("/img/vlr/tmp/vlr.png", "/img/base/ph/sil.png")

internal fun Element?.txt(): String = this?.text()?.trim().orEmpty()

internal fun Element?.own(): String = this?.ownText()?.trim().orEmpty()

internal fun absImage(src: String?): String? {
    if (src.isNullOrBlank()) return null
    if (PLACEHOLDER_IMAGES.any { src.contains(it) }) return null
    return when {
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> VLR + src
        else -> src
    }
}

internal fun Element?.img(): String? = absImage(this?.selectFirst("img")?.attr("src"))

/** "/753450/loud-vs-edg" -> "753450", "/team/1034/nrg" -> "1034". */
internal fun idFromHref(href: String?): String? {
    if (href.isNullOrBlank()) return null
    val path = href.removePrefix(VLR).removePrefix("https://vlr.gg")
    val parts = path.trim('/').split('/')
    return parts.firstOrNull { seg -> seg.isNotEmpty() && seg.all(Char::isDigit) }
}

internal fun Element?.flag(): String? {
    val el = this?.selectFirst(".flag") ?: return null
    return el.classNames().firstOrNull { it.startsWith("mod-") }?.removePrefix("mod-")
}

internal fun collapse(s: String): String = s.replace(Regex("\\s+"), " ").trim()

private val LIST_DATE = DateTimeFormatter.ofPattern("EEE, MMMM d, yyyy", Locale.US)
private val LIST_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val MATCH_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

internal fun parseListDate(label: String): LocalDate? = runCatching {
    LocalDate.parse(collapse(label.replace("Today", "").replace("Yesterday", "")), LIST_DATE)
}.getOrNull()

internal fun parseListTime(time: String): LocalTime? = runCatching {
    LocalTime.parse(time.trim().uppercase(Locale.US), LIST_TIME)
}.getOrNull()

internal fun listInstant(date: LocalDate?, time: LocalTime?): Instant? {
    if (date == null || time == null) return null
    return LocalDateTime.of(date, time).atZone(VLR_LIST_ZONE).toInstant()
}

internal fun parseMatchTs(ts: String?): Instant? = runCatching {
    LocalDateTime.parse(ts!!.trim(), MATCH_TS).atZone(VLR_MATCH_ZONE).toInstant()
}.getOrNull()

/**
 * Parses vlr.gg countdowns such as "12h 47m", "2d 11h" or "45m".
 * Returns null when the text has no recognisable units.
 */
internal fun parseEta(eta: String): Duration? {
    val parts = Regex("(\\d+)\\s*(mo|y|w|d|h|m)").findAll(eta.lowercase(Locale.US)).toList()
    if (parts.isEmpty()) return null
    var total = Duration.ZERO
    for (p in parts) {
        val n = p.groupValues[1].toLong()
        total += when (p.groupValues[2]) {
            "y" -> Duration.ofDays(365 * n)
            "mo" -> Duration.ofDays(30 * n)
            "w" -> Duration.ofDays(7 * n)
            "d" -> Duration.ofDays(n)
            "h" -> Duration.ofHours(n)
            else -> Duration.ofMinutes(n)
        }
    }
    return total
}

/** True when a countdown is precise to the minute (no day/week/month units). */
internal fun etaIsPrecise(eta: String): Boolean =
    Regex("\\d+\\s*m\\b").containsMatchIn(eta) && !Regex("\\d+\\s*(d|w|mo|y)\\b").containsMatchIn(eta)
