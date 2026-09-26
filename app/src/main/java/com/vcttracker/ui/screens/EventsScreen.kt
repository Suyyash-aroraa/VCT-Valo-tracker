package com.vcttracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.EventSummary
import com.vcttracker.data.Loaded
import com.vcttracker.data.Region
import com.vcttracker.data.Season
import com.vcttracker.data.Stage
import com.vcttracker.data.VlrParser
import com.vcttracker.ui.components.ChoiceRow
import com.vcttracker.ui.components.EmptyNote
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.OfflineNote
import com.vcttracker.ui.components.SectionHeader
import com.vcttracker.ui.components.StatusTag
import com.vcttracker.ui.components.TeamLogo
import com.vcttracker.ui.components.YearPicker
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.components.shortEvent
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct

private val REGION_FILTERS = listOf(null, Region.INTERNATIONAL, Region.AMERICAS, Region.EMEA, Region.PACIFIC, Region.CHINA)

@Composable
fun ScreenTitle(title: String, kicker: String) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        MonoLabel(kicker)
        Spacer(Modifier.height(4.dp))
        Text(title.uppercase(), style = Vct.type.hero, color = Vct.colors.ink, modifier = Modifier.semantics { heading() })
    }
}

@Composable
fun EventsScreen(year: Int, years: List<Int>, onYear: (Int) -> Unit) {
    var regionIdx by rememberSaveable { mutableIntStateOf(0) }
    val handle = rememberLoad<Season>(year) { force -> season(year, force) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Events", "Valorant Champions Tour")
        YearPicker(years, year, onYear)
        Spacer(Modifier.height(12.dp))
        ChoiceRow(
            options = REGION_FILTERS.map { it?.label ?: "All" },
            selected = regionIdx,
            onSelect = { regionIdx = it },
        )
        LoadedContent(handle) { loaded -> EventsList(loaded, REGION_FILTERS[regionIdx]) }
    }
}

@Composable
fun EventsList(loaded: Loaded<Season>, region: Region?) {
            val events = loaded.value.events.filter { region == null || it.region == region }
            // Latest stage first: that is what people open the tab for.
            val stages = events.groupBy { it.stage }.toSortedMap(compareByDescending { it.ordinal })
            LazyColumn(Modifier.fillMaxSize()) {
                item { OfflineNote(loaded) }
                if (events.isEmpty()) item { EmptyNote("No events listed for this filter yet.") }
                stages.forEach { (stage, list) ->
                    item(key = "h-${stage.name}") {
                        SectionHeader(stageTitle(stage, list), Modifier.padding(horizontal = 20.dp), trailing = stageIndex(stage))
                    }
                    items(list.sortedBy { it.region.ordinal }, key = { it.id }) { EventRow(it) }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
}

private fun stageTitle(stage: Stage, events: List<EventSummary>): String = when (stage) {
    Stage.MASTERS_1, Stage.MASTERS_2 -> "Masters ${VlrParser.mastersCity(events.first().name)}"
    Stage.OTHER -> "Other events"
    else -> stage.label
}

/** Position in the six-stop circuit, e.g. "03 / 06". */
private fun stageIndex(stage: Stage): String? =
    if (stage == Stage.OTHER) null else "%02d / 06".format(stage.ordinal + 1)

@Composable
private fun EventRow(e: EventSummary) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .clip(ChamferSmall)
            .background(c.surface, ChamferSmall)
            .clickable(role = Role.Button) { nav.event(e.id) }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamLogo(e.logo, e.name, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (e.region == Region.INTERNATIONAL) shortEvent(e.name) else e.region.label,
                style = Vct.type.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            MonoLabel(listOf(e.dates, e.prize).filter { it.isNotBlank() && it != "TBD" }.joinToString(" · "), color = c.faint)
        }
        Spacer(Modifier.width(10.dp))
        StatusTag(e.status)
    }
}
