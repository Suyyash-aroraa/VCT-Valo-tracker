package com.vcttracker.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.Game
import com.vcttracker.data.Loaded
import com.vcttracker.data.MatchDetail
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.PlayerLine
import com.vcttracker.data.SideValue
import com.vcttracker.data.StreamLink
import com.vcttracker.data.Team
import com.vcttracker.data.VetoStep
import com.vcttracker.model.LiveForecast
import com.vcttracker.model.ModelReport
import com.vcttracker.model.Prediction
import com.vcttracker.ui.components.ChoiceRow
import com.vcttracker.ui.components.ForecastCard
import com.vcttracker.ui.components.LiveForecastCard
import com.vcttracker.ui.components.Ui
import com.vcttracker.ui.components.repository
import androidx.compose.runtime.produceState
import com.vcttracker.ui.components.CountryTag
import com.vcttracker.ui.components.EmptyNote
import com.vcttracker.ui.components.LiveDot
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.OfflineNote
import com.vcttracker.ui.components.RemoteImage
import com.vcttracker.ui.components.RoundStrip
import com.vcttracker.ui.components.SectionHeader
import com.vcttracker.ui.components.TeamLogo
import com.vcttracker.ui.components.Time
import com.vcttracker.ui.components.TopBar
import com.vcttracker.ui.components.is24Hour
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.components.rememberNow
import com.vcttracker.ui.components.shortEvent
import com.vcttracker.ui.components.tagOf
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct
import com.vcttracker.ui.theme.VctIcons
import java.util.Locale

@Composable
fun MatchScreen(id: String) {
    val handle = rememberLoad<MatchDetail>(
        "match", id,
        pollSeconds = { m -> if (m.status == MatchStatus.LIVE) 20 else null },
    ) { force -> match(id, force) }
    var mapIdx by rememberSaveable(id) { mutableStateOf<Int?>(null) }

    // Forecasts only make sense before the result is known.
    val repo = repository()
    val detail = (handle.state as? Ui.Ready)?.data?.value
    // Keyed on the whole page, so every live refresh (new round, new map, agents locked) re-forecasts.
    val forecast by produceState<Forecasts>(Forecasts(), detail) {
        if (detail != null && detail.status != MatchStatus.COMPLETED) {
            value = Forecasts(repo.forecast(detail), repo.liveForecast(detail), repo.model()?.report)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Match", onRefresh = handle.refresh)
        LoadedContent(handle) { loaded -> MatchBody(loaded, mapIdx, forecast.pre, forecast.report, forecast.live) { mapIdx = it } }
    }
}

@Composable
fun MatchBody(
    loaded: Loaded<MatchDetail>,
    mapIdx: Int?,
    forecast: Prediction? = null,
    report: ModelReport? = null,
    live: LiveForecast? = null,
    onMap: (Int) -> Unit,
) {
    val m = loaded.value
    val played = m.games.filter { it.played }
    // Default to the map being played right now, else the overview.
    val defaultIdx = if (m.status == MatchStatus.LIVE && played.isNotEmpty()) played.size else 0
    val selected = (mapIdx ?: defaultIdx).coerceIn(0, played.size)
    val game: Game? = if (selected == 0) m.overall else played.getOrNull(selected - 1)

    LazyColumn(Modifier.fillMaxSize()) {
        item { OfflineNote(loaded) }
        item { Scorebug(m) }
        when {
            m.status == MatchStatus.COMPLETED -> Unit
            live != null -> item {
                LiveForecastCard(live, m.games.filter { !it.map.equals("TBD", true) }, m.team1.name, m.team2.name, isLive = m.status == MatchStatus.LIVE)
            }
            forecast != null -> item { ForecastCard(forecast, m.team1.name, m.team2.name, report) }
        }
        if (m.veto.isNotEmpty()) item { VetoStrip(m.veto) }
        if (m.games.isNotEmpty()) {
            item {
                SectionHeader("Maps", Modifier.padding(horizontal = 20.dp))
                MapList(m, onMap)
            }
        }
        if (played.isNotEmpty()) {
            item {
                SectionHeader("Stats", Modifier.padding(horizontal = 20.dp))
                ChoiceRow(
                    options = listOf("All maps") + played.map { "${it.number} ${it.map}" },
                    selected = selected,
                    onSelect = onMap,
                )
            }
            if (game != null && selected > 0) item { MapHeader(game, m) }
            if (game != null && game.rounds.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(14.dp))
                    RoundStrip(game.rounds, m.team1.name, m.team2.name)
                }
            }
            if (game != null) item { PlayerTables(game, m.team1, m.team2) }
        } else if (m.status == MatchStatus.UPCOMING) {
            item { EmptyNote("Stats appear here once the first map starts.") }
        }
        if (m.streams.isNotEmpty() && m.status != MatchStatus.COMPLETED) {
            item { Links("Watch", m.streams) }
        }
        if (m.vods.isNotEmpty()) item { Links("VODs", m.vods) }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun Scorebug(m: MatchDetail) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val is24 = is24Hour()
    val now = rememberNow(15_000)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            Modifier.clickable(enabled = m.eventId != null, role = Role.Button) { m.eventId?.let(nav::event) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamLogo(m.eventLogo, m.eventName, 20.dp)
            Spacer(Modifier.width(8.dp))
            MonoLabel(listOf(shortEvent(m.eventName), m.series).filter { it.isNotBlank() }.joinToString(" · "))
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBlock(m.team1, Modifier.weight(1f), alignEnd = false)
            Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val score1 = m.score1
                if (m.status == MatchStatus.UPCOMING || score1 == null) {
                    Text("VS", style = Vct.type.hero.copy(fontSize = Vct.type.display.fontSize), color = c.faint)
                } else {
                    val s1 = score1.toIntOrNull() ?: 0
                    val s2 = m.score2?.toIntOrNull() ?: 0
                    val done = m.status == MatchStatus.COMPLETED
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Score ${m.team1.name} ${m.score1}, ${m.team2.name} ${m.score2}"
                        },
                    ) {
                        Text(score1, style = Vct.type.hero, color = if (done && s1 < s2) c.faint else c.ink)
                        Text(":", style = Vct.type.display, color = c.faint, modifier = Modifier.padding(horizontal = 6.dp))
                        Text(m.score2.orEmpty(), style = Vct.type.hero, color = if (done && s2 < s1) c.faint else c.ink)
                    }
                }
                Spacer(Modifier.height(6.dp))
                when (m.status) {
                    MatchStatus.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(6.dp); Spacer(Modifier.width(6.dp)); MonoLabel("Live", color = c.spikeText)
                    }
                    MatchStatus.COMPLETED -> MonoLabel("Final")
                    MatchStatus.UPCOMING -> MonoLabel(m.startsAt?.let { Time.until(it, now) } ?: "TBD", color = c.ink)
                }
            }
            TeamBlock(m.team2, Modifier.weight(1f), alignEnd = true)
        }
        Spacer(Modifier.height(18.dp))
        val facts = listOfNotNull(
            m.startsAt?.let { Time.full(it, is24) },
            m.format,
            m.patch,
        )
        Text(facts.joinToString(" · ").uppercase(Locale.getDefault()), style = Vct.type.label, color = c.faint)
    }
}

@Composable
private fun TeamBlock(team: Team, modifier: Modifier, alignEnd: Boolean) {
    val nav = LocalNavigator.current
    Column(
        modifier.clickable(enabled = team.id != null, role = Role.Button) { nav.team(team.id) }.padding(vertical = 4.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        TeamLogo(team.logo, team.name, 56.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            team.name.uppercase(), style = Vct.type.title, color = Vct.colors.ink,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start, maxLines = 2,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun VetoStrip(veto: List<VetoStep>) {
    val c = Vct.colors
    Column {
        SectionHeader("Map veto", Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            veto.forEach { v ->
                val pick = v.action == "pick"
                val decider = v.action == "remains"
                Column(
                    Modifier
                        .background(if (pick || decider) c.ink else c.background, ChamferSmall)
                        .border(1.dp, if (pick || decider) c.ink else c.line, ChamferSmall)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    MonoLabel(
                        if (decider) "Decider" else "${v.team} ${v.action}",
                        color = if (pick || decider) c.background.copy(alpha = 0.7f) else c.faint,
                    )
                    Text(
                        v.map, style = Vct.type.heading,
                        color = if (pick || decider) c.background else c.muted,
                        textDecoration = if (v.action == "ban") TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapList(m: MatchDetail, onOpen: (Int) -> Unit) {
    val c = Vct.colors
    var playedIndex = 0
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        m.games.forEach { g ->
            val idx = if (g.played) ++playedIndex else null
            val s1 = g.score1.toIntOrNull()
            val s2 = g.score2.toIntOrNull()
            val live = m.status == MatchStatus.LIVE && g.played && idx == m.games.count { it.played }
            Row(
                Modifier.fillMaxWidth().clip(ChamferSmall).background(c.surface, ChamferSmall)
                    .clickable(enabled = idx != null, role = Role.Button) { idx?.let(onOpen) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoLabel("%d".format(g.number), Modifier.width(18.dp), color = c.faint)
                Column(Modifier.weight(1f)) {
                    Text(g.map.uppercase(), style = Vct.type.title, color = if (g.played) c.ink else c.faint)
                    val picker = when (g.pickedBy) { 1 -> m.team1.name; 2 -> m.team2.name; else -> null }
                    val sub = listOfNotNull(
                        picker?.let { "${tagOf(it)} pick" } ?: if (!g.played && m.status == MatchStatus.COMPLETED) "Not played" else "Decider",
                        g.duration.takeIf { it.isNotBlank() && it != "-" },
                    ).joinToString(" · ")
                    MonoLabel(sub, color = c.faint)
                }
                if (live) {
                    LiveDot(6.dp); Spacer(Modifier.width(10.dp))
                }
                if (g.played && s1 != null && s2 != null) {
                    Text(g.score1, style = Vct.type.score, color = if (s1 > s2) c.spikeText else c.muted)
                    Text(" – ", style = Vct.type.score, color = c.faint)
                    Text(g.score2, style = Vct.type.score, color = if (s2 > s1) c.spikeText else c.muted)
                }
            }
        }
    }
}

@Composable
private fun MapHeader(g: Game, m: MatchDetail) {
    val c = Vct.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        HalfSplit(tagOf(m.team1.name), g.team1Attack, g.team1Defense, Modifier.weight(1f), alignEnd = false)
        Text(g.map.uppercase(), style = Vct.type.display, color = c.ink)
        HalfSplit(tagOf(m.team2.name), g.team2Attack, g.team2Defense, Modifier.weight(1f), alignEnd = true)
    }
}

@Composable
private fun HalfSplit(tag: String, atk: String, def: String, modifier: Modifier, alignEnd: Boolean) {
    val c = Vct.colors
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        MonoLabel(tag, color = c.ink)
        Row {
            Text("ATK $atk", style = Vct.type.data, color = c.attack)
            Spacer(Modifier.width(8.dp))
            Text("DEF $def", style = Vct.type.data, color = c.defense)
        }
    }
}

// ------------------------------------------------------------------ stats

private data class Col(val key: String, val alt: String?, val label: String)

private val COLUMNS = listOf(
    Col("rating2", "rating", "R"),
    Col("acs", null, "ACS"),
    Col("kills", null, "K"),
    Col("deaths", null, "D"),
    Col("assists", null, "A"),
    Col("kd-diff", null, "+/–"),
    Col("kast", null, "KAST"),
    Col("adr", null, "ADR"),
    Col("hsp", null, "HS%"),
    Col("fb", null, "FK"),
    Col("fd", null, "FD"),
)

private fun PlayerLine.value(col: Col, side: Int): String {
    val v: SideValue = stats[col.key] ?: col.alt?.let { stats[it] } ?: return ""
    return when (side) {
        1 -> v.attack
        2 -> v.defense
        else -> v.both
    }
}

@Composable
private fun PlayerTables(g: Game, team1: Team, team2: Team) {
    var side by rememberSaveable { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val all = g.players1 + g.players2
    val best = all.maxByOrNull { it.value(COLUMNS[0], side).toDoubleOrNull() ?: -1.0 }
    Column(Modifier.padding(top = 18.dp)) {
        ChoiceRow(listOf("Both sides", "Attack", "Defense"), side, { side = it })
        Spacer(Modifier.height(8.dp))
        TeamTable(team1, g.players1, side, scroll, best)
        TeamTable(team2, g.players2, side, scroll, best)
        Text(
            "Red rating marks the top performer. Swipe the table sideways for KAST, ADR, HS% and first kills.",
            style = Vct.type.small, color = Vct.colors.faint,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TeamTable(team: Team, players: List<PlayerLine>, side: Int, scroll: ScrollState, best: PlayerLine?) {
    if (players.isEmpty()) return
    val c = Vct.colors
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(ChamferSmall).background(c.surface, ChamferSmall)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(138.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(team.logo, team.name, 18.dp)
                Spacer(Modifier.width(8.dp))
                MonoLabel(tagOf(team.name), color = c.ink)
            }
            Row(Modifier.horizontalScroll(scroll).padding(end = 12.dp)) {
                COLUMNS.forEach { col ->
                    MonoLabel(col.label, Modifier.width(48.dp), color = c.faint)
                }
            }
        }
        players.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = p.id != null, role = Role.Button) { nav.player(p.id) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.width(138.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentIcons(p.agents)
                    Spacer(Modifier.width(8.dp))
                    Text(p.name, style = Vct.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    CountryTag(p.flag)
                }
                Row(Modifier.horizontalScroll(scroll).padding(end = 12.dp)) {
                    COLUMNS.forEachIndexed { i, col ->
                        val v = p.value(col, side)
                        val color = when {
                            i == 0 && p == best -> c.spikeText
                            col.key.endsWith("diff") && v.startsWith("+") -> c.defense
                            col.key.endsWith("diff") && v.startsWith("-") -> c.attack
                            else -> c.ink
                        }
                        Text(
                            v.ifBlank { "–" },
                            style = if (i == 0) Vct.type.data.copy(fontWeight = FontWeight.Medium) else Vct.type.data,
                            color = color,
                            modifier = Modifier.width(48.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentIcons(agents: List<String>) {
    val c = Vct.colors
    Box(Modifier.size(24.dp).background(c.surfaceAlt, ChamferSmall), contentAlignment = Alignment.Center) {
        agents.firstOrNull()?.let { agent ->
            RemoteImage(
                url = "https://www.vlr.gg/img/vlr/game/agents/${agentFile(agent)}.png",
                contentDescription = agent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

internal fun agentFile(name: String) = name.lowercase(Locale.US).filter { it.isLetterOrDigit() }

// ------------------------------------------------------------------ links

@Composable
private fun Links(title: String, links: List<StreamLink>) {
    val c = Vct.colors
    val uri = LocalUriHandler.current
    Column {
        SectionHeader(title, Modifier.padding(horizontal = 20.dp))
        links.take(12).forEach { l ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { runCatching { uri.openUri(l.url) } }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VctIcons.Play, null, tint = c.spike, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(12.dp))
                Text(l.label, style = Vct.type.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                CountryTag(l.flag)
                Spacer(Modifier.width(10.dp))
                MonoLabel(host(l.url), color = c.faint)
                Spacer(Modifier.width(8.dp))
                Icon(VctIcons.External, null, tint = c.faint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun host(url: String): String = when {
    "twitch" in url -> "Twitch"
    "youtu" in url -> "YouTube"
    "kick.com" in url -> "Kick"
    else -> url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

/** Pre-match and live forecasts for one match page, plus the backtest they're judged by. */
private data class Forecasts(
    val pre: Prediction? = null,
    val live: LiveForecast? = null,
    val report: ModelReport? = null,
)
