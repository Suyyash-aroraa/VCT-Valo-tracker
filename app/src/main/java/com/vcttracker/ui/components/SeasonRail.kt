package com.vcttracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.EventSummary
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.Region
import com.vcttracker.data.Season
import com.vcttracker.data.Stage
import com.vcttracker.data.VlrParser
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct

private val CIRCUIT = listOf(Stage.KICKOFF, Stage.MASTERS_1, Stage.STAGE_1, Stage.MASTERS_2, Stage.STAGE_2, Stage.CHAMPIONS)

private data class RailStop(
    val stage: Stage,
    val title: String,
    val subtitle: String,
    val dates: String,
    val status: MatchStatus,
    val events: List<EventSummary>,
)

private fun stops(season: Season): List<RailStop> = CIRCUIT.mapNotNull { stage ->
    val events = season.events.filter { it.stage == stage }
    if (events.isEmpty()) return@mapNotNull null
    val status = when {
        events.any { it.status == MatchStatus.LIVE } -> MatchStatus.LIVE
        events.all { it.status == MatchStatus.COMPLETED } -> MatchStatus.COMPLETED
        else -> MatchStatus.UPCOMING
    }
    val (title, subtitle) = when (stage) {
        Stage.MASTERS_1, Stage.MASTERS_2 -> "Masters" to VlrParser.mastersCity(events.first().name)
        Stage.CHAMPIONS -> "Champions" to "World final"
        Stage.KICKOFF -> "Kickoff" to "4 regions"
        else -> stage.label to "4 regions"
    }
    RailStop(stage, title, subtitle, events.first().dates, status, events)
}

/**
 * The season as a route: Kickoff to Champions, with the current stop marked.
 * Tapping a stop opens it (international) or reveals its regional events.
 */
@Composable
fun SeasonRail(season: Season, modifier: Modifier = Modifier) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val all = remember(season) { stops(season) }
    if (all.isEmpty()) return
    val current = all.indexOfFirst { it.status == MatchStatus.LIVE }
        .takeIf { it >= 0 } ?: all.indexOfFirst { it.status == MatchStatus.UPCOMING }.takeIf { it >= 0 } ?: all.lastIndex
    var expanded by rememberSaveable(season.year) { mutableStateOf<Int?>(null) }

    val stopWidth = 128.dp
    val density = LocalDensity.current
    // Open with the current stop in view, one stop of context to its left.
    val scroll = rememberScrollState(with(density) { (stopWidth * (current - 1).coerceAtLeast(0)).roundToPx() })

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(scroll).padding(horizontal = 12.dp)) {
            all.forEachIndexed { i, stop ->
                RailNode(
                    index = i,
                    count = all.size,
                    stop = stop,
                    width = stopWidth,
                    selected = expanded == i,
                    onClick = {
                        if (stop.events.size == 1) nav.event(stop.events.first().id)
                        else expanded = if (expanded == i) null else i
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = expanded != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val stop = expanded?.let { all.getOrNull(it) }
            if (stop != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    stop.events.sortedBy { it.region.ordinal }.forEach { e ->
                        Row(
                            Modifier.clip(ChamferSmall).background(c.surface, ChamferSmall)
                                .clickable(role = Role.Button) { nav.event(e.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TeamLogo(e.logo, e.region.short, 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (e.region == Region.INTERNATIONAL) shortEvent(e.name) else e.region.label,
                                style = Vct.type.small, color = c.ink,
                            )
                            if (e.status == MatchStatus.LIVE) {
                                Spacer(Modifier.width(8.dp))
                                LiveDot(6.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RailNode(
    index: Int,
    count: Int,
    stop: RailStop,
    width: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Vct.colors
    val reached = stop.status != MatchStatus.UPCOMING
    val passed = stop.status == MatchStatus.COMPLETED
    val stateWord = when (stop.status) {
        MatchStatus.LIVE -> "happening now"
        MatchStatus.COMPLETED -> "finished"
        MatchStatus.UPCOMING -> "upcoming"
    }
    Column(
        Modifier
            .width(width)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Stop ${index + 1} of $count: ${stop.title} ${stop.subtitle}, ${stop.dates}, $stateWord"
            }
            .padding(vertical = 8.dp),
    ) {
        MonoLabel("%02d".format(index + 1), modifier = Modifier.padding(start = 8.dp), color = if (stop.status == MatchStatus.LIVE) c.spikeText else c.faint)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
            val lineInk = c.ink
            val lineFaint = c.line
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val y = size.height / 2
                val nodeX = 8.dp.toPx() + 6.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                if (index > 0) {
                    drawLine(if (reached) lineInk else lineFaint, Offset(0f, y), Offset(nodeX, y), 2.dp.toPx(),
                        pathEffect = if (reached) null else dash)
                }
                if (index < count - 1) {
                    drawLine(if (passed) lineInk else lineFaint, Offset(nodeX, y), Offset(size.width, y), 2.dp.toPx(),
                        pathEffect = if (passed) null else dash)
                }
            }
            Box(Modifier.padding(start = 8.dp)) { RailMarker(stop.status) }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(start = 8.dp, end = 8.dp)) {
            Text(
                stop.title.uppercase(),
                style = Vct.type.title,
                color = if (stop.status == MatchStatus.UPCOMING) c.muted else c.ink,
                maxLines = 1,
            )
            Text(stop.subtitle, style = Vct.type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            MonoLabel(stop.dates, color = c.faint)
            if (selected) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(20.dp).height(2.dp).background(c.ink))
            }
        }
    }
}

@Composable
private fun RailMarker(status: MatchStatus) {
    val c = Vct.colors
    when (status) {
        MatchStatus.COMPLETED -> Box(Modifier.size(12.dp).background(c.ink, ChamferSmall))
        MatchStatus.UPCOMING -> Box(Modifier.size(12.dp).background(c.background).border(2.dp, c.faint))
        MatchStatus.LIVE -> {
            val t = rememberInfiniteTransition(label = "rail")
            val s by t.animateFloat(1f, 2.1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "s")
            val a by t.animateFloat(0.45f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "a")
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(12.dp)) {
                    drawCircle(c.spike.copy(alpha = a), radius = size.minDimension / 2 * s)
                }
                Box(Modifier.size(12.dp).background(c.spike, ChamferSmall))
            }
        }
    }
}
