package com.vcttracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vcttracker.data.Loaded
import com.vcttracker.model.ModelReport
import com.vcttracker.model.TrainedModel
import com.vcttracker.ui.components.EmptyNote
import com.vcttracker.ui.components.LoadedContent
import com.vcttracker.ui.components.MonoLabel
import com.vcttracker.ui.components.SectionHeader
import com.vcttracker.ui.components.Time
import com.vcttracker.ui.components.TopBar
import com.vcttracker.ui.components.rememberLoad
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct
import java.io.IOException
import kotlin.math.roundToInt

@Composable
fun ModelScreen() {
    val handle = rememberLoad<TrainedModel>("model") { _ ->
        val m = model() ?: throw IOException("model unavailable")
        Loaded(m, m.generatedAt, offline = false)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Forecasts")
        LoadedContent(handle) { loaded -> ModelBody(loaded.value) }
    }
}

private fun pct(x: Double) = "%.1f%%".format(x * 100)

@Composable
fun ModelBody(model: TrainedModel) {
    val c = Vct.colors
    val r = model.report
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                MonoLabel("Match forecasts")
                Spacer(Modifier.height(6.dp))
                Text("HOW OFTEN IT'S RIGHT", style = Vct.type.display, color = c.ink, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(
                    "Every forecast below was made before the match, using only earlier results. None of these series were used to tune the model.",
                    style = Vct.type.body, color = c.muted,
                )
            }
        }
        if (r == null) {
            item { EmptyNote("This model shipped without a backtest report.") }
        } else {
            item { Headline(r) }
            item { Baselines(r) }
            item { CalibrationChart(r) }
            if (r.live.isNotEmpty()) item { LiveTable(r) }
        }
        item { HowItWorks() }
        item {
            Text(
                "Model trained ${Time.ago(model.generatedAt)} on every VCT series since 2023. Forecasts are for fun, not betting advice.",
                style = Vct.type.small, color = c.faint,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun Headline(r: ModelReport) {
    val c = Vct.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column {
            Text(pct(r.accuracy), style = Vct.type.hero, color = c.ink)
            MonoLabel("Favourite won", color = c.faint)
        }
        Column(Modifier.padding(top = 10.dp)) {
            Text("${r.testMatches}", style = Vct.type.display, color = c.ink)
            MonoLabel("Series tested", color = c.faint)
            Spacer(Modifier.height(8.dp))
            Text("%.3f".format(r.logLoss), style = Vct.type.title, color = c.ink)
            MonoLabel("Log-loss (coin flip 0.693)", color = c.faint)
        }
    }
}

@Composable
private fun Baselines(r: ModelReport) {
    val c = Vct.colors
    Column {
        SectionHeader("Against the alternatives", Modifier.padding(horizontal = 20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            MonoLabel("Right, and log-loss", Modifier.weight(1f), color = c.faint)
            MonoLabel("Them", Modifier.width(64.dp), color = c.faint)
            MonoLabel("Model", Modifier.width(64.dp), color = c.faint)
        }
        r.baselines.forEach { b ->
            val better = b.modelLogLoss < b.logLoss
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).semantics(mergeDescendants = true) {
                    contentDescription = "${b.name}, ${b.matches} series: they were right ${pct(b.accuracy)}, the model ${pct(b.modelAccuracy)}"
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = Vct.type.bodyStrong, color = c.ink)
                    MonoLabel(
                        "${b.matches} series · %.3f vs %.3f".format(b.logLoss, b.modelLogLoss),
                        color = if (better) c.muted else c.faint,
                    )
                }
                Text(pct(b.accuracy), style = Vct.type.data, color = c.muted, modifier = Modifier.width(64.dp))
                Text(
                    pct(b.modelAccuracy),
                    style = Vct.type.data.copy(fontWeight = FontWeight.Medium),
                    color = c.ink,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

/**
 * Reliability chart: one row per confidence band. The bar is how often the favourite
 * actually won; the tick is what the model said. A well-calibrated model has them touch.
 */
@Composable
private fun CalibrationChart(r: ModelReport) {
    val c = Vct.colors
    if (r.calibration.isEmpty()) return
    Column {
        SectionHeader("When it says 70%, is it 70%?", Modifier.padding(horizontal = 20.dp))
        Text(
            "Bars show how often the favourite actually won. The tick marks what the model predicted.",
            style = Vct.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))
        r.calibration.forEach { (predicted, observed, n) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp).semantics(mergeDescendants = true) {
                    contentDescription = "Predicted ${pct(predicted)}, favourite won ${pct(observed)}, $n series"
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(96.dp)) {
                    Text("${(predicted * 100).roundToInt()}%", style = Vct.type.data, color = c.ink)
                    MonoLabel("$n series", color = c.faint)
                }
                // Axis runs 40–100%: favourites never sit below 50%, and a little headroom shows misses.
                BoxWithConstraints(Modifier.weight(1f).height(18.dp)) {
                    fun x(v: Double) = maxWidth * (((v - 0.4) / 0.6).toFloat().coerceIn(0f, 1f))
                    Box(Modifier.fillMaxWidth().height(18.dp).background(c.surface))
                    Box(Modifier.width(x(observed)).height(18.dp).clip(ChamferSmall).background(c.muted.copy(alpha = 0.55f), ChamferSmall))
                    Box(Modifier.offset(x = x(predicted) - 1.dp).width(2.dp).height(18.dp).background(c.ink))
                }
                Text(pct(observed), style = Vct.type.data, color = c.ink, modifier = Modifier.width(64.dp).padding(start = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 116.dp, end = 84.dp)) {
            MonoLabel("40%", Modifier.weight(1f), color = c.faint)
            MonoLabel("100%", color = c.faint)
        }
    }
}

/** How accuracy climbs as a match gives up information: maps, results, rounds. */
@Composable
private fun LiveTable(r: ModelReport) {
    val c = Vct.colors
    Column {
        SectionHeader("As the match unfolds", Modifier.padding(horizontal = 20.dp))
        Text(
            "The same unseen matches, forecast again each time something new is known.",
            style = Vct.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        r.live.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp).semantics(mergeDescendants = true) {
                    contentDescription = "${row.moment}: right ${pct(row.accuracy)} of ${row.forecasts}"
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.moment, style = Vct.type.bodyStrong, color = c.ink)
                    MonoLabel("${row.forecasts} forecasts · log-loss %.3f".format(row.logLoss), color = c.faint)
                }
                Text(pct(row.accuracy), style = Vct.type.data.copy(fontWeight = FontWeight.Medium), color = c.ink)
            }
        }
    }
}

@Composable
private fun HowItWorks() {
    val c = Vct.colors
    val steps = listOf(
        "Player ratings" to "Every player carries a skill estimate with an uncertainty attached. After each map, the round score updates everyone who played. A team is its current five, so roster moves carry their history with them.",
        "Maps and regions" to "Teams get an adjustment per map. Regions get a strength offset that only Masters and Champions results can move.",
        "Round to series" to "A round edge becomes map odds through the exact first-to-13, win-by-two maths. Maps are then combined into Bo1, Bo3 or Bo5 odds, averaging over how sure we are about each roster.",
        "Veto simulation" to "Each team's recent picks and bans play out the real VCT veto hundreds of times to find the maps most likely to be played.",
        "Live updates" to "Once play starts, maps already won count as won, and the live map's odds come from its exact round score and which side each team is on. Each live signal is kept only if it beat the pre-match forecast on unseen matches; agent comps and the post-veto map update didn't, so they're off.",
    )
    Column {
        SectionHeader("How a forecast is built", Modifier.padding(horizontal = 20.dp))
        steps.forEachIndexed { i, (title, body) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("%02d".format(i + 1), style = Vct.type.title, color = c.faint, modifier = Modifier.width(40.dp))
                Column {
                    Text(title, style = Vct.type.heading, color = c.ink)
                    Spacer(Modifier.height(2.dp))
                    Text(body, style = Vct.type.small, color = c.muted)
                }
            }
        }
    }
}
