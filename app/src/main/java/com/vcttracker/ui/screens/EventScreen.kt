package com.vcttracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.EventDetail
import com.vcttracker.data.EventTeam
import com.vcttracker.data.GroupTable
import com.vcttracker.data.Loaded
import com.vcttracker.data.MatchDay
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.PrizeRow
import com.vcttracker.model.EventForecast
import com.vcttracker.model.ModelReport
import com.vcttracker.ui.components.BracketSectionView
import com.vcttracker.ui.components.ChoiceRow
import com.vcttracker.ui.components.CountryTag
import com.vcttracker.ui.components.EmptyNote
import com.vcttracker.ui.components.EventForecastBody
import com.vcttracker.ui.components.SkeletonList
import com.vcttracker.ui.components.Ui
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.MatchRow
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.OfflineNote
import com.vcttracker.ui.components.SectionHeader
import com.vcttracker.ui.components.TabStrip
import com.vcttracker.ui.components.TeamLogo
import com.vcttracker.ui.components.TopBar
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.components.rememberNow
import com.vcttracker.ui.components.shortEvent
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct

private val TABS = listOf("Bracket", "Matches", "Forecast", "Prizes", "Teams")

@Composable
fun EventScreen(id: String) {
    var subPath by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }
    val handle = rememberLoad<EventDetail>(id, subPath) { force -> event(id, subPath, force) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Event", onRefresh = handle.refresh)
        LoadedContent(handle) { loaded -> EventBody(id, loaded, tab, { tab = it }, { subPath = it }) }
    }
}

@Composable
fun EventBody(id: String, loaded: Loaded<EventDetail>, tab: Int, onTab: (Int) -> Unit, onSubPage: (String) -> Unit) {
    val e = loaded.value
    LazyColumn(Modifier.fillMaxSize()) {
        item { OfflineNote(loaded) }
        item { EventHeader(e) }
        item { TabStrip(TABS, tab, onTab) }
        when (tab) {
            0 -> bracketTab(e, onSubPage)
            1 -> item { EventMatches(id) }
            2 -> item { EventForecastTab(id) }
            3 -> prizesTab(e.prizes)
            4 -> teamsTab(e.teams)
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun EventHeader(e: EventDetail) {
    val c = Vct.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(e.logo, e.name, 52.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                e.subtitle.ifBlank { "Valorant Champions Tour" },
                style = Vct.type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(shortEvent(e.name).uppercase(), style = Vct.type.display, color = c.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Fact("Dates", e.dates)
            Fact("Prize", e.prize)
            Fact("Where", e.location)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        MonoLabel(label, color = Vct.colors.faint)
        Spacer(Modifier.height(2.dp))
        Text(value, style = Vct.type.bodyStrong, color = Vct.colors.ink, maxLines = 2)
    }
}

private fun LazyListScope.bracketTab(e: EventDetail, onSubPage: (String) -> Unit) {
    if (e.subPages.size > 1) {
        item {
            Spacer(Modifier.height(14.dp))
            val active = e.subPages.indexOfFirst { it.active }.coerceAtLeast(0)
            ChoiceRow(
                options = e.subPages.map { it.label },
                selected = active,
                onSelect = { onSubPage(e.subPages[it].path) },
            )
            e.subPages.getOrNull(active)?.dates?.takeIf { it.isNotBlank() }?.let {
                MonoLabel(it, Modifier.padding(start = 20.dp, top = 8.dp), color = Vct.colors.faint)
            }
        }
    }
    if (e.groups.isEmpty() && e.sections.isEmpty()) {
        item { EmptyNote("The bracket for this stage hasn't been drawn yet.") }
    }
    if (e.groups.isNotEmpty()) {
        item { SectionHeader("Groups", Modifier.padding(horizontal = 20.dp)) }
        items(e.groups, key = { "g-" + it.title }) { GroupTableView(it) }
    }
    items(e.sections.size, key = { "s-$it" }) { BracketSectionView(e.sections[it]) }
}

@Composable
private fun GroupTableView(table: GroupTable) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(ChamferSmall).background(c.surface, ChamferSmall).padding(vertical = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(table.title.uppercase(), style = Vct.type.title, color = c.ink, modifier = Modifier.weight(1f))
            HeaderCell("W–L", 44)
            HeaderCell("Maps", 48)
            HeaderCell("Δ", 40)
        }
        table.rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .clickable(enabled = r.team.id != null, role = Role.Button) { nav.team(r.team.id) }
                    .padding(end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(if (r.advanced) c.ink else c.surface))
                Spacer(Modifier.width(11.dp))
                Text("${i + 1}", style = Vct.type.data, color = c.faint, modifier = Modifier.width(18.dp))
                TeamLogo(r.team.logo, r.team.name, 22.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.team.name, style = Vct.type.bodyStrong, color = if (r.eliminated) c.muted else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.country.isNotBlank()) Text(r.country, style = Vct.type.small, color = c.faint, maxLines = 1)
                }
                DataCell(r.record, 44, strong = true)
                DataCell(r.maps, 48)
                DataCell(r.diff, 40)
            }
        }
        MonoLabel("Bar marks teams that advanced", Modifier.padding(start = 14.dp, top = 6.dp), color = c.faint)
    }
}

@Composable
private fun HeaderCell(text: String, width: Int) {
    MonoLabel(text, Modifier.width(width.dp), color = Vct.colors.faint)
}

@Composable
private fun DataCell(text: String, width: Int, strong: Boolean = false) {
    Text(
        text,
        style = if (strong) Vct.type.data.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) else Vct.type.data,
        color = if (strong) Vct.colors.ink else Vct.colors.muted,
        modifier = Modifier.width(width.dp),
        textAlign = TextAlign.Start,
        maxLines = 1,
    )
}

@Composable
private fun EventMatches(id: String) {
    val handle = rememberLoad<List<MatchDay>>("em", id) { force -> eventMatches(id, force) }
    val now = rememberNow()
    Column {
        when (val s = handle.state) {
            is com.vcttracker.ui.components.Ui.Ready -> {
                val days = s.data.value
                if (days.isEmpty()) EmptyNote("No matches are listed for this event yet.")
                // What's still to play comes first; played days follow, newest first.
                val (open, played) = days.partition { d -> d.matches.any { it.status != MatchStatus.COMPLETED } }
                if (open.isNotEmpty()) SectionHeader("To play", Modifier.padding(horizontal = 20.dp))
                open.forEach { day -> MatchDayBlock(day, now) }
                if (played.isNotEmpty()) SectionHeader("Played", Modifier.padding(horizontal = 20.dp))
                played.asReversed().forEach { day -> MatchDayBlock(day, now) }
            }
            is com.vcttracker.ui.components.Ui.Failed -> EmptyNote(s.message)
            com.vcttracker.ui.components.Ui.Loading -> com.vcttracker.ui.components.SkeletonList(4)
        }
    }
}

@Composable
private fun EventForecastTab(id: String) {
    val handle = rememberLoad<EventForecast?>("ef", id) { force -> eventForecast(id, force) }
    val report = rememberLoad<ModelReport?>("model-report") { _ ->
        Loaded(model()?.report, java.time.Instant.now(), offline = false)
    }
    when (val s = handle.state) {
        is Ui.Ready -> {
            val f = s.data.value
            if (f == null) {
                EmptyNote(
                    "No forecast for this event yet. It's made once every stage's format has been seen played out " +
                        "in an earlier event, so brand-new formats, and brackets that haven't been drawn, have to wait.",
                )
            } else {
                EventForecastBody(f, (report.state as? Ui.Ready)?.data?.value?.events)
            }
        }
        is Ui.Failed -> EmptyNote(s.message)
        Ui.Loading -> Column {
            MonoLabel("Simulating the rest of the event…", Modifier.padding(start = 20.dp, top = 16.dp), color = Vct.colors.faint)
            SkeletonList(6)
        }
    }
}

@Composable
private fun MatchDayBlock(day: MatchDay, now: java.time.Instant) {
    MonoLabel(day.label, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp), color = Vct.colors.faint)
    day.matches.forEach { MatchRow(it, showEvent = false, now = now) }
}

private fun LazyListScope.prizesTab(rows: List<PrizeRow>) {
    if (rows.isEmpty()) {
        item { EmptyNote("Prize distribution hasn't been published.") }
        return
    }
    item {
        Spacer(Modifier.height(10.dp))
        val hasPoints = rows.any { it.points != null }
        if (hasPoints) {
            MonoLabel("Circuit points count toward Champions qualification", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = Vct.colors.faint)
        }
    }
    items(rows.size) { i -> PrizeLine(rows[i]) }
}

@Composable
private fun PrizeLine(r: PrizeRow) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val winner = r.place.startsWith("1st")
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = r.team?.id != null, role = Role.Button) { nav.team(r.team?.id) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            r.place.replace(" ", ""),
            style = Vct.type.title,
            color = if (winner) c.spikeText else c.ink,
            modifier = Modifier.width(78.dp),
            maxLines = 1,
        )
        TeamLogo(r.team?.logo, r.team?.name ?: "TBD", 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.team?.name ?: "To be decided", style = Vct.type.bodyStrong, color = if (r.team == null) c.faint else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(r.prize.ifBlank { null }, r.note).joinToString(" · ")
            if (sub.isNotBlank()) MonoLabel(sub, color = c.faint)
        }
        r.points?.let {
            Text(
                it, style = Vct.type.label, color = c.background,
                modifier = Modifier.background(c.ink, ChamferSmall).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun LazyListScope.teamsTab(teams: List<EventTeam>) {
    if (teams.isEmpty()) {
        item { EmptyNote("Participants haven't been announced.") }
        return
    }
    item { Spacer(Modifier.height(10.dp)) }
    items(teams.chunked(2)) { pair ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { TeamCard(it, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TeamCard(t: EventTeam, modifier: Modifier) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Column(
        modifier.clip(ChamferSmall).background(c.surface, ChamferSmall)
            .clickable(enabled = t.team.id != null, role = Role.Button) { nav.team(t.team.id) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(t.team.logo, t.team.name, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(t.team.name, style = Vct.type.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        t.players.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = p.id != null, role = Role.Button) { nav.player(p.id) }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.name, style = Vct.type.small, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                CountryTag(p.flag)
            }
        }
        t.note?.let {
            Spacer(Modifier.height(8.dp))
            MonoLabel(it, color = c.faint)
        }
    }
}
