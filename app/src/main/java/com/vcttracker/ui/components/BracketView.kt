package com.vcttracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vcttracker.data.Bracket
import com.vcttracker.data.BracketMatch
import com.vcttracker.data.BracketSection
import com.vcttracker.data.BracketTeam
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct

private val COL_W = 172.dp
private val GAP = 26.dp
private val BOX_H = 58.dp
private val SLOT_H = 80.dp
private val LABEL_H = 26.dp

@Composable
fun BracketSectionView(section: BracketSection) {
    Column(Modifier.fillMaxWidth()) {
        if (section.title.isNotBlank()) SectionHeader(section.title, Modifier.padding(horizontal = 20.dp))
        section.brackets.forEach { b ->
            if (section.brackets.size > 1) {
                MonoLabel(
                    if (b.isLower) "Lower bracket" else "Upper bracket",
                    Modifier.padding(start = 20.dp, top = if (b.isLower) 20.dp else 8.dp, bottom = 4.dp),
                    color = Vct.colors.faint,
                )
            }
            BracketView(b)
        }
    }
}

@Composable
private fun BracketView(bracket: Bracket) {
    val c = Vct.colors
    val cols = bracket.columns.filter { it.matches.isNotEmpty() }
    if (cols.isEmpty()) return
    val maxN = cols.maxOf { it.matches.size }
    val bodyH = SLOT_H * maxN
    fun centerY(n: Int, k: Int): Dp = LABEL_H + (bodyH / n) * (k + 0.5f)

    Box(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        val width = COL_W * cols.size + GAP * (cols.size - 1)
        val lineColor = c.line
        Canvas(Modifier.width(width).height(LABEL_H + bodyH)) {
            val stroke = Stroke(width = 1.5.dp.toPx())
            for (i in 0 until cols.size - 1) {
                val n = cols[i].matches.size
                val next = cols[i + 1].matches.size
                val xOut = (COL_W * (i + 1) + GAP * i).toPx()
                val xIn = xOut + GAP.toPx()
                val xMid = xOut + GAP.toPx() / 2
                for (k in 0 until n) {
                    val j = (k.toLong() * next / n).toInt().coerceIn(0, next - 1)
                    val y1 = centerY(n, k).toPx()
                    val y2 = centerY(next, j).toPx()
                    val path = Path().apply {
                        moveTo(xOut, y1)
                        lineTo(xMid, y1)
                        lineTo(xMid, y2)
                        lineTo(xIn, y2)
                    }
                    drawPath(path, lineColor, style = stroke)
                }
            }
        }
        cols.forEachIndexed { i, col ->
            val x = (COL_W + GAP) * i
            MonoLabel(col.label, Modifier.offset(x = x).width(COL_W), color = c.faint)
            col.matches.forEachIndexed { k, m ->
                val y = centerY(col.matches.size, k) - BOX_H / 2
                BracketBox(m, Modifier.offset(x = x, y = y))
            }
        }
    }
}

@Composable
private fun BracketBox(m: BracketMatch, modifier: Modifier) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val is24 = is24Hour()
    val played = m.team1.score.isNotBlank() || (m.team2?.score?.isNotBlank() == true)
    val description = listOfNotNull(
        m.team1.team.name, m.team2?.team?.name,
    ).joinToString(" versus ") + if (played) ", ${m.team1.score} to ${m.team2?.score}" else ""
    Column(modifier.width(COL_W)) {
        Column(
            Modifier.width(COL_W).height(BOX_H)
                .clip(ChamferSmall)
                .background(c.surface, ChamferSmall)
                .clickable(enabled = m.matchId != null, role = Role.Button) { m.matchId?.let(nav::match) }
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            BracketLine(m.team1, Modifier.weight(1f))
            m.team2?.let { BracketLine(it, Modifier.weight(1f)) }
        }
        val startsAt = m.startsAt
        if (!played && startsAt != null && m.team2 != null) {
            Text(
                "${Time.day(startsAt)} ${Time.clock(startsAt, is24)}",
                style = Vct.type.label.copy(fontWeight = FontWeight.Normal),
                color = c.faint,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BracketLine(t: BracketTeam, modifier: Modifier) {
    val c = Vct.colors
    val tbd = t.team.name == "TBD"
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (tbd) Box(Modifier.size(16.dp).background(c.surfaceAlt, ChamferSmall))
        else TeamLogo(t.team.logo, t.team.name, 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            t.team.name,
            style = if (t.isWinner) Vct.type.small.copy(fontWeight = FontWeight.Bold) else Vct.type.small,
            color = when {
                tbd -> c.faint
                t.isLoser -> c.muted
                else -> c.ink
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            t.score,
            style = Vct.type.title.copy(fontSize = Vct.type.heading.fontSize),
            color = if (t.isWinner) c.spikeText else c.muted,
        )
    }
}
