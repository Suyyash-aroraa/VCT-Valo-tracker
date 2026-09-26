package com.vcttracker.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.Loaded
import com.vcttracker.data.RegionStanding
import com.vcttracker.data.StandingRow
import com.vcttracker.ui.components.ChoiceRow
import com.vcttracker.ui.components.EmptyNote
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.OfflineNote
import com.vcttracker.ui.components.TeamLogo
import com.vcttracker.ui.components.YearPicker
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.theme.Vct

@Composable
fun StandingsScreen(year: Int, years: List<Int>, onYear: (Int) -> Unit) {
    var regionIdx by rememberSaveable { mutableIntStateOf(0) }
    val handle = rememberLoad<List<RegionStanding>>("standings", year) { force -> standings(year, force) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Circuit points", "Road to Champions")
        YearPicker(years, year, onYear)
        LoadedContent(handle) { loaded -> StandingsBody(loaded, regionIdx) { regionIdx = it } }
    }
}

@Composable
fun StandingsBody(loaded: Loaded<List<RegionStanding>>, regionIdx: Int, onRegion: (Int) -> Unit) {
    val regions = loaded.value
    if (regions.isEmpty()) {
        EmptyNote("No circuit points were awarded this season.")
        return
    }
    val idx = regionIdx.coerceIn(0, regions.lastIndex)
    val region = regions[idx]
    val max = region.rows.maxOfOrNull { it.points }?.coerceAtLeast(1) ?: 1
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(12.dp))
            ChoiceRow(regions.map { it.title }, idx, onRegion)
            OfflineNote(loaded)
            MonoLabel(
                "Red bars have qualified for Champions",
                Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                color = Vct.colors.faint,
            )
        }
        itemsIndexed(region.rows, key = { i, r -> "$i-${r.team.name}" }) { i, r -> StandingLine(i + 1, r, max) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun StandingLine(rank: Int, r: StandingRow, max: Int) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = r.team.id != null, role = Role.Button) { nav.team(r.team.id) }
            .semantics(mergeDescendants = true) {
                contentDescription = "Rank $rank, ${r.team.name}, ${r.points} points" + if (r.qualified) ", qualified" else ""
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(rank), style = Vct.type.title,
            color = if (r.qualified) c.ink else c.faint, modifier = Modifier.width(34.dp),
        )
        TeamLogo(r.team.logo, r.team.name, 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.team.name, style = Vct.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(r.points.toString(), style = Vct.type.score, color = if (r.qualified) c.spikeText else c.ink, textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(4.dp)) {
                val f = (r.points.toFloat() / max).coerceIn(0.01f, 1f)
                Box(Modifier.weight(f).height(4.dp).background(if (r.qualified) c.spike else c.muted.copy(alpha = 0.45f)))
                if (f < 1f) Box(Modifier.weight(1f - f).height(4.dp).background(c.line))
            }
            if (r.country.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                MonoLabel(r.country, color = c.faint)
            }
        }
    }
}
