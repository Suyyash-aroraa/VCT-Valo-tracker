package com.vcttracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.Loaded
import com.vcttracker.data.RosterMember
import com.vcttracker.data.TeamDetail
import com.vcttracker.data.TeamMatch
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
import com.vcttracker.ui.components.shortEvent
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct

@Composable
fun TeamScreen(id: String) {
    val handle = rememberLoad<TeamDetail>("team", id) { force -> team(id, force) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Team", onRefresh = handle.refresh)
        LoadedContent(handle) { loaded -> TeamBody(loaded) }
    }
}

@Composable
fun TeamBody(loaded: Loaded<TeamDetail>) {
    val t = loaded.value
    val players = t.roster.filterNot { it.isStaff }
    val staff = t.roster.filter { it.isStaff }
    val form = t.recent.take(5).mapNotNull { it.won }
    LazyColumn(Modifier.fillMaxSize()) {
        item { OfflineNote(loaded) }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                TeamLogo(t.logo, t.name, 72.dp)
                Spacer(Modifier.height(16.dp))
                Text(t.name.uppercase(), style = Vct.type.hero.copy(fontSize = Vct.type.display.fontSize * 1.3f), color = Vct.colors.ink, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoLabel(listOf(t.tag, t.country).filter { it.isNotBlank() }.joinToString(" · "))
                    if (form.isNotEmpty()) {
                        Spacer(Modifier.width(14.dp))
                        FormGuide(form)
                    }
                }
            }
        }
        if (players.isNotEmpty()) {
            item { SectionHeader("Roster", Modifier.padding(horizontal = 20.dp), trailing = players.size.toString()) }
            items(players.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { PlayerCard(it, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (staff.isNotEmpty()) {
            item { SectionHeader("Staff", Modifier.padding(horizontal = 20.dp)) }
            items(staff) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = Vct.type.bodyStrong, color = Vct.colors.ink, modifier = Modifier.weight(1f))
                    s.role?.let { MonoLabel(it, color = Vct.colors.faint) }
                }
            }
        }
        if (t.upcoming.isNotEmpty()) {
            item { SectionHeader("Upcoming", Modifier.padding(horizontal = 20.dp)) }
            items(t.upcoming, key = { "u" + it.matchId }) { TeamMatchRow(it) }
        }
        if (t.recent.isNotEmpty()) {
            item { SectionHeader("Recent results", Modifier.padding(horizontal = 20.dp)) }
            items(t.recent.take(15), key = { "r" + it.matchId }) { TeamMatchRow(it) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

/** Last five results, oldest on the left. */
@Composable
fun FormGuide(results: List<Boolean>) {
    val c = Vct.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Recent form: " + results.joinToString(", ") { if (it) "win" else "loss" }
        },
    ) {
        results.reversed().forEach { won ->
            Box(
                Modifier.size(16.dp)
                    .then(if (won) Modifier.background(c.ink, ChamferSmall) else Modifier.border(1.dp, c.line, ChamferSmall)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (won) "W" else "L", style = Vct.type.label, color = if (won) c.background else c.muted)
            }
        }
    }
}

@Composable
private fun PlayerCard(p: RosterMember, modifier: Modifier) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Row(
        modifier.clip(ChamferSmall).background(c.surface, ChamferSmall)
            .clickable(enabled = p.id != null, role = Role.Button) { nav.player(p.id) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(ChamferSmall).background(c.surfaceAlt), contentAlignment = Alignment.BottomCenter) {
            if (p.photo != null) {
                RemoteImage(p.photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp))
            } else {
                Text(p.name.take(1).uppercase(), style = Vct.type.title, color = c.faint, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = Vct.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                CountryTag(p.flag)
            }
            Text(
                listOfNotNull(p.role?.takeIf { it.isNotBlank() }, p.realName.ifBlank { null }).joinToString(" · "),
                style = Vct.type.small, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TeamMatchRow(m: TeamMatch) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .clip(ChamferSmall).background(c.surface, ChamferSmall)
            .clickable(role = Role.Button) { nav.match(m.matchId) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tag = when (m.won) { true -> "W"; false -> "L"; null -> "·" }
        Text(tag, style = Vct.type.title, color = if (m.won == true) c.spikeText else c.faint, modifier = Modifier.width(22.dp))
        TeamLogo(m.opponent.logo, m.opponent.name, 24.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("vs ${m.opponent.name}", style = Vct.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MonoLabel(listOf(shortEvent(m.eventName), m.series).filter { it.isNotBlank() }.joinToString(" · "), color = c.faint)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (m.scoreFor != null) Text("${m.scoreFor}–${m.scoreAgainst}", style = Vct.type.score.copy(fontSize = Vct.type.heading.fontSize * 1.2f), color = c.ink)
            MonoLabel(m.date.substringBefore(' '), color = c.faint)
        }
    }
}
