package com.vcttracker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.HistoricEvent
import com.vcttracker.data.HistoryYear
import com.vcttracker.data.LiquipediaParser
import com.vcttracker.data.Loaded
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.OfflineNote
import com.vcttracker.ui.components.SectionHeader
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct

@Composable
fun HistoryScreen() {
    val handle = rememberLoad<List<HistoryYear>>("history") { force -> history(force) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Hall of champions", "Every VCT winner since 2021")
        LoadedContent(handle) { loaded -> HistoryBody(loaded) }
    }
}

@Composable
fun HistoryBody(loaded: Loaded<List<HistoryYear>>) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { OfflineNote(loaded) }
        items(loaded.value, key = { it.year }) { YearBlock(it) }
        item { Attribution() }
    }
}

@Composable
private fun YearBlock(y: HistoryYear) {
    val c = Vct.colors
    var open by rememberSaveable(y.year) { mutableStateOf(false) }
    val international = y.events.filter { it.isInternational }
        .sortedWith(compareBy<HistoricEvent> { !it.name.contains("Champions", true) }.thenBy { it.name })
    val regional = y.events.filterNot { it.isInternational }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(y.year, style = Vct.type.hero, color = c.ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(bottom = 10.dp).height(1.dp).background(c.line))
        }
        international.forEach { Trophy(it) }
        if (regional.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoLabel(if (open) "Hide regional events" else "Show ${regional.size} regional events", color = c.ink)
            }
            if (open) {
                regional.forEach { RegionalLine(it) }
            }
        }
    }
}

/** An international event: the winner is the headline. */
@Composable
private fun Trophy(e: HistoricEvent) {
    val c = Vct.colors
    val champions = e.name.contains("Champions", true)
    val decided = e.winner != "TBD"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(ChamferSmall)
            .background(if (champions && decided) c.ink else c.surface, ChamferSmall)
            .padding(16.dp),
    ) {
        val fg = if (champions && decided) c.background else c.ink
        val sub = if (champions && decided) c.background.copy(alpha = 0.65f) else c.faint
        MonoLabel(listOf(eventTitle(e.name), e.location).filter { it.isNotBlank() }.joinToString(" · "), color = sub)
        Spacer(Modifier.height(8.dp))
        Text(
            if (decided) e.winner.uppercase() else "TO BE DECIDED",
            style = Vct.type.display, color = if (decided) fg else sub,
        )
        if (decided) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(2.dp).background(c.spike))
                Spacer(Modifier.width(8.dp))
                Text("def. ${e.runnerUp} in the final", style = Vct.type.small, color = sub)
            }
        }
        Spacer(Modifier.height(10.dp))
        MonoLabel(listOf(e.dates, e.prize, if (e.participants.isNotBlank()) "${e.participants} teams" else "").filter { it.isNotBlank() }.joinToString(" · "), color = sub)
    }
}

@Composable
private fun RegionalLine(e: HistoricEvent) {
    val c = Vct.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eventTitle(e.name), style = Vct.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MonoLabel(e.dates, color = c.faint)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(e.winner, style = Vct.type.bodyStrong, color = if (e.winner == "TBD") c.faint else c.ink, maxLines = 1)
            if (e.winner != "TBD") MonoLabel("over ${e.runnerUp}", color = c.faint)
        }
    }
}

private fun eventTitle(name: String) = name
    .replace(Regex("^VCT \\d{4}:\\s*"), "")
    .replace(Regex("^VALORANT\\s+", RegexOption.IGNORE_CASE), "")
    .replace(Regex("^Champions Tour\\s+\\d{4}:\\s*"), "")

@Composable
private fun Attribution() {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        SectionHeader("Source")
        Text(
            "Tournament history from Liquipedia, licensed CC BY-SA 3.0. Tap to open the original page.",
            style = Vct.type.small, color = Vct.colors.muted,
            modifier = Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(LiquipediaParser.SOURCE_PAGE) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}
