package com.vcttracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vcttracker.data.EventDetail
import com.vcttracker.data.EventSummary
import com.vcttracker.data.Loaded
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.MatchSummary
import com.vcttracker.data.Season
import com.vcttracker.data.TodayData
import com.vcttracker.ui.components.LiveDot
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.MatchRow
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.OfflineNote
import com.vcttracker.ui.components.SeasonRail
import com.vcttracker.ui.components.SectionHeader
import com.vcttracker.ui.components.Time
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.components.rememberNow
import com.vcttracker.ui.components.shortEvent
import com.vcttracker.ui.theme.Vct
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen() {
    val handle = rememberLoad<TodayData>(
        "today",
        pollSeconds = { d -> if (d.upcoming.any { day -> day.matches.any { it.status == MatchStatus.LIVE } }) 30 else 120 },
    ) { force -> today(force) }
    val now = rememberNow()

    LoadedContent(handle) { loaded -> TodayBody(loaded, now) }
}

@Composable
fun TodayBody(loaded: Loaded<TodayData>, now: Instant) {
    val data = loaded.value
    val all = data.upcoming.flatMap { it.matches }
    val live = all.filter { it.status == MatchStatus.LIVE }
    val next = all.filter { it.status == MatchStatus.UPCOMING }.take(14)
    val recent = data.results.flatMap { it.matches }.take(12)

    LazyColumn(Modifier.fillMaxSize()) {
        item { Masthead() }
        item { OfflineNote(loaded) }
        item { Hero(data.featured, data.season, now) }
        data.season?.let { season ->
            item {
                SectionHeader("The circuit · ${season.year}", Modifier.padding(horizontal = 20.dp))
                SeasonRail(season)
            }
        }
        if (live.isNotEmpty()) {
            item {
                SectionHeader(
                    "Live now", Modifier.padding(horizontal = 20.dp),
                    accent = Vct.colors.spikeText, trailing = live.size.toString(),
                    leading = { LiveDot() },
                )
            }
            items(live, key = { "live-" + it.id }) { MatchRow(it, now = now) }
        }
        byDay("Up next", next, now, emptyText = "No VCT matches are scheduled in the next few days.")
        byDay("Results", recent, now, emptyText = null)
        item {
            Text(
                "Match data from vlr.gg. Updated ${Time.ago(loaded.fetchedAt, now)}.",
                style = Vct.type.small, color = Vct.colors.faint,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            )
        }
    }
}

private fun LazyListScope.byDay(title: String, matches: List<MatchSummary>, now: Instant, emptyText: String?) {
    if (matches.isEmpty()) {
        if (emptyText != null) {
            item { SectionHeader(title, Modifier.padding(horizontal = 20.dp)) }
            item { Text(emptyText, style = Vct.type.body, color = Vct.colors.muted, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        return
    }
    item { SectionHeader(title, Modifier.padding(horizontal = 20.dp)) }
    val groups = matches.groupBy { m -> m.startsAt?.let(Time::day) ?: "Time to be decided" }
    groups.forEach { (day, list) ->
        item(key = "$title-$day") {
            MonoLabel(day, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp), color = Vct.colors.faint)
        }
        items(list, key = { "$title-" + it.id }) { MatchRow(it, now = now) }
    }
}

@Composable
private fun Masthead() {
    val c = Vct.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("VCT", style = Vct.type.title, color = c.ink)
        Box(Modifier.padding(horizontal = 6.dp).width(8.dp).height(8.dp).background(c.spike))
        Text("TRACKER", style = Vct.type.title, color = c.muted)
        Spacer(Modifier.weight(1f))
        MonoLabel(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(LocalDate.now()))
    }
}

@Composable
private fun Hero(featured: EventDetail?, season: Season?, now: Instant) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    if (featured != null) {
        val stage = featured.subPages.firstOrNull { it.active }?.label
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.event(featured.id) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot()
                Spacer(Modifier.width(8.dp))
                MonoLabel(
                    listOfNotNull("On now", featured.location.ifBlank { null }).joinToString(" · "),
                    color = c.spikeText,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                shortEvent(featured.name).uppercase(),
                style = Vct.type.hero, color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Meta("Stage", stage ?: "—")
                Meta("Prize", featured.prize.ifBlank { "TBD" })
                Meta("Dates", featured.dates.substringBefore(",").ifBlank { "TBD" })
            }
            val progress = dateProgress(featured.dates)
            if (progress != null) {
                Spacer(Modifier.height(16.dp))
                ProgressTrack(progress.first, progress.second)
            }
        }
        return
    }

    // Nothing live: point at what comes next, or close out the season.
    val next = season?.events.orEmpty().filter { it.status == MatchStatus.UPCOMING }
        .minByOrNull { it.stage.ordinal }
    val label: String
    val title: String
    val event: EventSummary?
    if (next != null) {
        label = "Next up"
        title = shortEvent(next.name)
        event = next
    } else {
        label = "Season ${season?.year ?: ""} complete"
        event = season?.events?.firstOrNull { it.stage == com.vcttracker.data.Stage.CHAMPIONS }
        title = event?.let { shortEvent(it.name) } ?: "Off-season"
    }
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = event != null, role = Role.Button) { event?.let { nav.event(it.id) } }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        MonoLabel(label)
        Spacer(Modifier.height(10.dp))
        Text(title.uppercase(), style = Vct.type.hero, color = c.ink, modifier = Modifier.semantics { heading() })
        if (event != null) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Meta("Dates", event.dates)
                Meta("Prize", event.prize.ifBlank { "TBD" })
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Column {
        MonoLabel(label, color = Vct.colors.faint)
        Spacer(Modifier.height(2.dp))
        Text(value, style = Vct.type.bodyStrong, color = Vct.colors.ink)
    }
}

@Composable
private fun ProgressTrack(day: Int, total: Int) {
    val c = Vct.colors
    Column {
        Row(Modifier.fillMaxWidth().height(4.dp)) {
            val f = (day.toFloat() / total).coerceIn(0.02f, 1f)
            Box(Modifier.weight(f).height(4.dp).background(c.spike))
            if (f < 1f) Box(Modifier.weight(1f - f).height(4.dp).background(c.line))
        }
        Spacer(Modifier.height(6.dp))
        MonoLabel("Day $day of $total", color = c.faint)
    }
}

/** "Sep 24 – Oct 18, 2026" -> (day index today, total days). */
internal fun dateProgress(dates: String, today: LocalDate = LocalDate.now()): Pair<Int, Int>? {
    val m = Regex("([A-Z][a-z]{2})\\s+(\\d{1,2})\\s*[–—-]\\s*(?:([A-Z][a-z]{2})\\s+)?(\\d{1,2}),\\s*(\\d{4})").find(dates)
        ?: return null
    val (m1, d1, m2, d2, y) = m.destructured
    val fmt = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US)
    return runCatching {
        val end = LocalDate.parse("${m2.ifEmpty { m1 }} $d2 $y", fmt)
        var start = LocalDate.parse("$m1 $d1 $y", fmt)
        if (start.isAfter(end)) start = start.minusYears(1)
        val total = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
        val day = (today.toEpochDay() - start.toEpochDay() + 1).toInt().coerceIn(1, total)
        day to total
    }.getOrNull()
}
