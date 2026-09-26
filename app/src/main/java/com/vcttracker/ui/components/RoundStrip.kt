package com.vcttracker.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.Round
import com.vcttracker.data.RoundEnd
import com.vcttracker.data.Side
import com.vcttracker.ui.theme.Vct
import kotlin.math.cos
import kotlin.math.sin

private val CELL = 17.dp
private val CELL_GAP = 3.dp
private val HALF_GAP = 12.dp

/**
 * Every round of a map as two rows of cells. The winner's cell is filled with the side
 * they won on (amber attack, teal defense) and marked with how the round ended.
 */
@Composable
fun RoundStrip(rounds: List<Round>, team1: String, team2: String, modifier: Modifier = Modifier) {
    if (rounds.isEmpty()) return
    val c = Vct.colors
    val summary = "Round by round. " + rounds.joinToString(". ") { r ->
        "Round ${r.number} to ${if (r.winner == 1) team1 else team2}" +
            (r.side?.let { if (it == Side.ATTACK) " on attack" else " on defense" } ?: "") +
            (r.end?.let { ", " + it.name.lowercase() } ?: "")
    }
    Column(modifier.fillMaxWidth().semantics { contentDescription = summary }) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.width(44.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                RowTag(team1)
                RowTag(team2)
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(end = 20.dp)) {
                val width = stripWidth(rounds)
                val numberColor = c.faint
                val empty = c.surfaceAlt
                val attack = c.attack
                val defense = c.defense
                val glyph = c.background
                val numbers = androidx.compose.ui.text.rememberTextMeasurer()
                val labelStyle = Vct.type.label.copy(color = numberColor, fontSize = Vct.type.label.fontSize * 0.8f)
                Canvas(Modifier.width(width).height(14.dp + CELL * 2 + CELL_GAP)) {
                    var x = 0f
                    rounds.forEachIndexed { i, r ->
                        if (i > 0 && isHalfBreak(rounds[i - 1].number)) x += (HALF_GAP - CELL_GAP).toPx()
                        if (r.number == 1 || r.number % 6 == 0 || isHalfBreak(r.number - 1)) {
                            drawText(numbers, r.number.toString(), Offset(x, 0f), labelStyle)
                        }
                        for (row in 1..2) {
                            val top = 14.dp.toPx() + (row - 1) * (CELL + CELL_GAP).toPx()
                            val won = r.winner == row
                            val fill = when {
                                !won -> empty
                                r.side == Side.ATTACK -> attack
                                r.side == Side.DEFENSE -> defense
                                else -> numberColor
                            }
                            drawRect(fill, Offset(x, top), Size(CELL.toPx(), CELL.toPx()))
                            if (won) drawRoundGlyph(r.end, glyph, Offset(x + CELL.toPx() / 2, top + CELL.toPx() / 2), CELL.toPx() * 0.28f)
                        }
                        x += (CELL + CELL_GAP).toPx()
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        RoundLegend(Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun RowTag(name: String) {
    Box(Modifier.height(CELL), contentAlignment = Alignment.CenterStart) {
        Text(tagOf(name), style = Vct.type.label, color = Vct.colors.muted, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Short tag for a team name: "EDward Gaming" -> "EDG", "LOUD" -> "LOUD". */
fun tagOf(name: String): String {
    val words = name.split(' ').filter { it.isNotBlank() }
    return when {
        words.size == 1 -> name.take(4).uppercase()
        else -> words.joinToString("") { w -> w.filter(Char::isUpperCase).ifEmpty { w.take(1) } }.take(4).uppercase()
    }
}

private fun isHalfBreak(number: Int) = number == 12 || number == 24

private fun stripWidth(rounds: List<Round>) =
    (CELL + CELL_GAP) * rounds.size + (HALF_GAP - CELL_GAP) * rounds.zipWithNext().count { isHalfBreak(it.first.number) }

fun DrawScope.drawRoundGlyph(end: RoundEnd?, color: Color, center: Offset, r: Float) {
    val stroke = 1.6.dp.toPx()
    when (end) {
        RoundEnd.ELIMINATION -> {
            drawLine(color, center + Offset(-r, -r), center + Offset(r, r), stroke)
            drawLine(color, center + Offset(-r, r), center + Offset(r, -r), stroke)
        }
        RoundEnd.DETONATE -> {
            for (i in 0 until 8) {
                val a = Math.PI * i / 4
                val inner = r * 0.35f
                drawLine(
                    color,
                    center + Offset((cos(a) * inner).toFloat(), (sin(a) * inner).toFloat()),
                    center + Offset((cos(a) * r * 1.15f).toFloat(), (sin(a) * r * 1.15f).toFloat()),
                    stroke,
                )
            }
        }
        RoundEnd.DEFUSE -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x, center.y - r * 1.1f)
                lineTo(center.x + r, center.y)
                lineTo(center.x, center.y + r * 1.1f)
                lineTo(center.x - r, center.y)
                close()
            }
            drawPath(p, color, style = Stroke(stroke))
        }
        RoundEnd.TIME -> {
            drawCircle(color, r * 1.05f, center, style = Stroke(stroke))
            drawLine(color, center, center + Offset(0f, -r * 0.7f), stroke)
            drawLine(color, center, center + Offset(r * 0.5f, 0f), stroke)
        }
        null -> drawCircle(color, r * 0.4f, center)
    }
}

@Composable
private fun RoundLegend(modifier: Modifier = Modifier) {
    val c = Vct.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(c.attack, "Attack")
        Swatch(c.defense, "Defense")
        listOf(
            RoundEnd.ELIMINATION to "Elim",
            RoundEnd.DETONATE to "Spike",
            RoundEnd.DEFUSE to "Defuse",
            RoundEnd.TIME to "Time",
        ).forEach { (end, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ink = c.muted
                Canvas(Modifier.size(12.dp)) { drawRoundGlyph(end, ink, center, size.minDimension * 0.34f) }
                Spacer(Modifier.width(5.dp))
                MonoLabel(label, color = c.faint)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawRect(color) }
        Spacer(Modifier.width(5.dp))
        MonoLabel(label, color = Vct.colors.faint)
    }
}
