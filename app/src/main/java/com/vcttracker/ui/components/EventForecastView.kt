package com.vcttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.model.EventForecast
import com.vcttracker.model.EventReport
import com.vcttracker.model.TeamOutlook
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct
import kotlin.math.roundToInt

/** Simulated shares: certain outcomes read as words, tiny ones don't round to a misleading 0%. */
private fun share(p: Double, alive: Boolean = true): String = when {
    p >= 0.9995 -> "In"
    p <= 0.0 -> if (alive) "<1%" else "Out"
    p < 0.005 -> "<1%"
    p > 0.995 -> ">99%"
    else -> "${(p * 100).roundToInt()}%"
}

/** "Swiss Stage" -> "Swiss", "Group Stage" -> "Groups": short enough for a chip. */
private fun stageShort(label: String): String {
    val l = label.lowercase()
    return when {
        "swiss" in l -> "Swiss"
        "group" in l -> "Groups"
        "play-in" in l || "play in" in l -> "Play-ins"
        "regular" in l -> "Season"
        else -> label.split(' ').first()
    }
}

/**
 * Who advances, qualifies and wins. Teams whose every chance is gone are listed last, faded.
 */
@Composable
fun EventForecastBody(f: EventForecast, report: EventReport?, modifier: Modifier = Modifier) {
    val c = Vct.colors
    val dests = f.destinations.keys.sorted()
    fun key(o: TeamOutlook) = if (f.hasWinner) o.win else dests.sumOf { o.qualify[it] ?: 0.0 }
    val alive = { o: TeamOutlook -> o.win > 0 || o.qualify.values.any { it > 0 } || o.advance.values.any { it in 0.0001..0.9999 } }
    val (live, out) = f.teams.sortedWith(
        compareByDescending<TeamOutlook> { key(it) }
            .thenByDescending { o -> dests.sumOf { o.qualify[it] ?: 0.0 } }
            .thenByDescending { it.advance.values.sum() },
    ).partition(alive)
    val decided = f.hasWinner && f.teams.any { it.win >= 0.9995 }

    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (decided) "This event is decided. The numbers below are final."
                else "Every match still to play, simulated ${"%,d".format(f.runs)} times with the match model. Results already in are kept.",
                style = Vct.type.small, color = c.muted,
            )
        }
        val favourite = live.firstOrNull()
        if (favourite != null && !decided) Favourite(f, favourite, dests)
        SectionHeader(if (f.hasWinner) "Title odds" else "Qualification odds", Modifier.padding(horizontal = 20.dp), trailing = if (f.hasWinner) "Win" else dests.firstOrNull())
        live.forEach { TeamLine(f, it, dests, faded = false) }
        if (out.isNotEmpty()) {
            SectionHeader("Out", Modifier.padding(horizontal = 20.dp))
            out.forEach { TeamLine(f, it, dests, faded = true) }
        }
        report?.let { ForecastTrackRecord(it) }
    }
}

@Composable
private fun Favourite(f: EventForecast, o: TeamOutlook, dests: List<String>) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val team = f.display[o.team]
    val name = team?.name ?: o.team
    val headline = if (f.hasWinner) o.win else dests.firstOrNull()?.let { o.qualify[it] } ?: 0.0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(ChamferSmall).background(c.surface, ChamferSmall)
            .clickable(enabled = team?.id != null, role = Role.Button) { nav.team(team?.id) }
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Favourite: $name, ${share(headline)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamLogo(team?.logo, name, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            MonoLabel("Favourite", color = c.faint)
            Text(name.uppercase(), style = Vct.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(share(headline), style = Vct.type.display, color = c.ink)
            MonoLabel(if (f.hasWinner) "to win" else "to qualify", color = c.faint)
        }
    }
}

@Composable
private fun TeamLine(f: EventForecast, o: TeamOutlook, dests: List<String>, faded: Boolean) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val team = f.display[o.team]
    val name = team?.name ?: o.team
    val main = if (f.hasWinner) o.win else dests.firstOrNull()?.let { o.qualify[it] } ?: 0.0
    val chips = buildList {
        f.stages.forEach { st -> o.advance[st]?.let { add("${stageShort(st)} ${share(it)}") } }
        // With no single winner the headline number already is the qualification.
        dests.drop(if (f.hasWinner) 0 else 1).forEach { d -> add("$d ${share(o.qualify[d] ?: 0.0, alive = !faded)}") }
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = team?.id != null, role = Role.Button) { nav.team(team?.id) }
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name: ${if (f.hasWinner) "win" else "qualify"} ${share(main, alive = !faded)}. ${chips.joinToString(", ")}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamLogo(team?.logo, name, 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Vct.type.bodyStrong, color = if (faded) c.muted else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                MonoLabel(chips.joinToString("  ·  "), color = c.faint)
            }
            if (!faded) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(c.line)) {
                    Box(Modifier.fillMaxWidth(main.toFloat().coerceIn(0f, 1f)).height(3.dp).background(c.ink))
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            share(main, alive = !faded),
            style = Vct.type.data.copy(fontWeight = FontWeight.Medium),
            color = if (faded) c.faint else c.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

/** How these forecasts have done before, so a 30% favourite is read the right way. */
@Composable
private fun ForecastTrackRecord(r: EventReport) {
    val c = Vct.colors
    SectionHeader("Track record", Modifier.padding(horizontal = 20.dp))
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Forecast before each of ${r.events} unseen events in 2025–26, the model gave the eventual winner " +
                "${(r.winnerChance * 100).roundToInt()}% on average, against ${(r.uniformChance * 100).roundToInt()}% for a random pick. " +
                "The favourite won ${r.favouriteWon} of ${r.withWinner}: a whole event is hard to call, so even the favourite rarely has better than a one-in-three chance.",
            style = Vct.type.small, color = c.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("%.3f".format(r.advanceLogLoss), "Advancing · base %.3f".format(r.advanceBase))
            Stat("%.3f".format(r.qualifyLogLoss), "Qualifying · base %.3f".format(r.qualifyBase))
        }
        MonoLabel("Log-loss, lower is better", color = c.faint)
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = Vct.type.title, color = Vct.colors.ink)
        MonoLabel(label, color = Vct.colors.faint)
    }
}
