package com.vcttracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vcttracker.data.AgentStat
import com.vcttracker.data.Loaded
import com.vcttracker.data.PlayerDetail
import com.vcttracker.data.PlayerTeam
import com.vcttracker.ui.components.CountryTag
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.OfflineNote
import com.vcttracker.ui.components.RemoteImage
import com.vcttracker.ui.components.SectionHeader
import com.vcttracker.ui.components.TeamLogo
import com.vcttracker.ui.components.TopBar
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.theme.ChamferMedium
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct

@Composable
fun PlayerScreen(id: String) {
    val handle = rememberLoad<PlayerDetail>("player", id) { force -> player(id, force) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Player", onRefresh = handle.refresh)
        LoadedContent(handle) { loaded -> PlayerBody(loaded) }
    }
}

@Composable
fun PlayerBody(loaded: Loaded<PlayerDetail>) {
    val p = loaded.value
    LazyColumn(Modifier.fillMaxSize()) {
        item { OfflineNote(loaded) }
        item { Header(p) }
        if (p.currentTeams.isNotEmpty()) {
            item { SectionHeader("Plays for", Modifier.padding(horizontal = 20.dp)) }
            items(p.currentTeams) { TeamLine(it) }
        }
        if (p.agents.isNotEmpty()) {
            item { SectionHeader("Agents · last 60 days", Modifier.padding(horizontal = 20.dp)) }
            item { AgentTable(p.agents) }
        }
        if (p.recent.isNotEmpty()) {
            item { SectionHeader("Recent results", Modifier.padding(horizontal = 20.dp)) }
            items(p.recent.take(10), key = { it.matchId }) { TeamMatchRow(it) }
        }
        if (p.pastTeams.isNotEmpty()) {
            item { SectionHeader("Previously", Modifier.padding(horizontal = 20.dp)) }
            items(p.pastTeams) { TeamLine(it) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun Header(p: PlayerDetail) {
    val c = Vct.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(96.dp).clip(ChamferMedium).background(c.surfaceAlt), contentAlignment = Alignment.BottomCenter) {
            if (p.photo != null) {
                RemoteImage(p.photo, contentDescription = "Photo of ${p.alias}", contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
            } else {
                Text(p.alias.take(1).uppercase(), style = Vct.type.hero, color = c.faint)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.alias.uppercase(), style = Vct.type.hero.copy(fontSize = Vct.type.display.fontSize * 1.3f), color = c.ink, modifier = Modifier.semantics { heading() })
            if (p.realName.isNotBlank()) Text(p.realName, style = Vct.type.body, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountryTag(p.flag)
                Spacer(Modifier.width(6.dp))
                MonoLabel(p.country)
            }
        }
    }
}

@Composable
private fun TeamLine(t: PlayerTeam) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .clip(ChamferSmall).background(c.surface, ChamferSmall)
            .clickable(enabled = t.team.id != null, role = Role.Button) { nav.team(t.team.id) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamLogo(t.team.logo, t.team.name, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(t.team.name, style = Vct.type.bodyStrong, color = c.ink)
            if (t.note.isNotBlank()) MonoLabel(t.note, color = c.faint)
        }
    }
}

private val AGENT_COLS = listOf("Use", "Rnd", "R", "ACS", "K:D", "KAST", "ADR", "FK:FD")

@Composable
private fun AgentTable(agents: List<AgentStat>) {
    val c = Vct.colors
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(ChamferSmall).background(c.surface, ChamferSmall).padding(vertical = 6.dp)) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(52.dp))
            Row(Modifier.horizontalScroll(scroll)) {
                AGENT_COLS.forEach { MonoLabel(it, Modifier.width(if (it == "Use") 76.dp else 52.dp), color = c.faint) }
            }
        }
        agents.forEach { a ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 12.dp).size(28.dp).background(c.surfaceAlt, ChamferSmall), contentAlignment = Alignment.Center) {
                    RemoteImage("https://www.vlr.gg/img/vlr/game/agents/${agentFile(a.agent)}.png", contentDescription = a.agent, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Row(Modifier.horizontalScroll(scroll)) {
                    listOf(a.usage, a.rounds, a.rating, a.acs, a.kd, a.kast, a.adr, a.fkfd).forEachIndexed { i, v ->
                        Text(v, style = Vct.type.data, color = if (i == 2) c.ink else c.muted, modifier = Modifier.width(if (i == 0) 76.dp else 52.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}
