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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vcttracker.model.ModelReport
import com.vcttracker.model.Prediction
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct
import com.vcttracker.ui.theme.VctIcons
import kotlin.math.abs
import kotlin.math.roundToInt

private fun pct(p: Double) = "${(p * 100).roundToInt()}%"

/** A two-sided bar: the favourite's share in ink, the other side receding. */
@Composable
fun SplitBar(team1Share: Double, modifier: Modifier = Modifier, height: Int = 6) {
    val c = Vct.colors
    val f = team1Share.toFloat().coerceIn(0.02f, 0.98f)
    Row(modifier.fillMaxWidth().height(height.dp)) {
        Box(Modifier.weight(f).height(height.dp).background(if (f >= 0.5f) c.ink else c.muted.copy(alpha = 0.35f)))
        Spacer(Modifier.width(2.dp))
        Box(Modifier.weight(1f - f).height(height.dp).background(if (f < 0.5f) c.ink else c.muted.copy(alpha = 0.35f)))
    }
}

@Composable
fun ForecastCard(p: Prediction, team1: String, team2: String, report: ModelReport?, modifier: Modifier = Modifier) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val fav = if (p.team1Wins >= 0.5) team1 else team2
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Forecast", Modifier.padding(horizontal = 20.dp), trailing = "Bo${p.bestOf}")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(ChamferSmall).background(c.surface, ChamferSmall).padding(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = "Model forecast: $team1 ${pct(p.team1Wins)}, $team2 ${pct(1 - p.team1Wins)}"
                },
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    MonoLabel(tagOf(team1), color = c.muted)
                    Text(pct(p.team1Wins), style = Vct.type.display, color = if (p.team1Wins >= 0.5) c.ink else c.faint)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    MonoLabel(tagOf(team2), color = c.muted)
                    Text(pct(1 - p.team1Wins), style = Vct.type.display, color = if (p.team1Wins < 0.5) c.ink else c.faint, textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(10.dp))
            SplitBar(p.team1Wins)

            val likely = p.maps.filter { it.playedChance >= 0.05 }.take(5)
            if (likely.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Row {
                    MonoLabel("Likely maps", Modifier.weight(1f), color = c.faint)
                    MonoLabel("Played", Modifier.width(64.dp), color = c.faint)
                    MonoLabel("${tagOf(team1)} wins", Modifier.width(88.dp), color = c.faint)
                }
                likely.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {
                            contentDescription = "${m.map}: played in ${pct(m.playedChance)} of simulations, $team1 wins it ${pct(m.team1Wins)}"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.map.uppercase(), style = Vct.type.title, color = c.ink, modifier = Modifier.weight(1f))
                        Text(pct(m.playedChance), style = Vct.type.data, color = c.muted, modifier = Modifier.width(64.dp))
                        Column(Modifier.width(88.dp)) {
                            Text(pct(m.team1Wins), style = Vct.type.data, color = c.ink)
                            Spacer(Modifier.height(3.dp))
                            SplitBar(m.team1Wins, Modifier.width(72.dp), height = 3)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            MonoLabel("Why $fav", color = c.faint)
            Spacer(Modifier.height(6.dp))
            reasons(p, team1, team2).forEach { line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.padding(top = 8.dp).size(4.dp).background(c.muted))
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = Vct.type.small, color = c.ink)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.model() }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                report?.let { "Backtested on ${it.testMatches} unseen series: right ${pct(it.accuracy)} of the time. How it works" }
                    ?: "How the forecast works",
                style = Vct.type.small, color = c.muted, modifier = Modifier.weight(1f),
            )
            Icon(VctIcons.Chevron, null, tint = c.faint, modifier = Modifier.size(16.dp))
        }
    }
}

private fun possessive(name: String) = if (name.endsWith("s")) "$name'" else "$name's"

/** The factors behind a forecast, in plain words, strongest first. */
fun reasons(p: Prediction, team1: String, team2: String): List<String> {
    val f = p.factors
    val out = ArrayList<Pair<Double, String>>()
    fun side(x: Double) = if (x >= 0) team1 else team2
    val roundShare = 50 + abs(f.rosterEdge)
    out += abs(f.rosterEdge) * 2 to
        if (abs(f.rosterEdge) < 0.5) "The two rosters rate almost exactly even."
        else "${possessive(side(f.rosterEdge))} current five are expected to win about ${"%.1f".format(roundShare)}% of rounds on an average map."
    if (abs(f.mapPoolEdge) >= 1) {
        out += abs(f.mapPoolEdge) to "The likely maps shift the series ${abs(f.mapPoolEdge).roundToInt()} points toward ${side(f.mapPoolEdge)}."
    }
    if (abs(f.regionEdge) >= 0.5) {
        out += abs(f.regionEdge) * 2 to "${possessive(side(f.regionEdge))} region has the edge in recent international results."
    }
    if (f.uncertainty >= 0.6) {
        out += 100.0 to "At least one roster has little recent history, so this forecast is kept cautious."
    }
    return out.sortedByDescending { it.first }.map { it.second }.take(4)
}
