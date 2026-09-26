package com.vcttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcttracker.data.MatchSide
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.MatchSummary
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct
import java.time.Instant

/** One match as a compact scorebug tile. */
@Composable
fun MatchRow(match: MatchSummary, modifier: Modifier = Modifier, showEvent: Boolean = true, now: Instant = Instant.now()) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    val is24 = is24Hour()
    val done = match.status == MatchStatus.COMPLETED
    val description = buildString {
        append("${match.team1.team.name} versus ${match.team2.team.name}. ")
        when (match.status) {
            MatchStatus.LIVE -> append("Live, ${match.team1.score ?: 0} to ${match.team2.score ?: 0}. ")
            MatchStatus.COMPLETED -> append("Final, ${match.team1.score} to ${match.team2.score}. ")
            MatchStatus.UPCOMING -> match.startsAt?.let { append("Starts ${Time.day(it)} ${Time.clock(it, is24)}. ") }
        }
        append(match.eventName)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .clip(ChamferSmall)
            .background(c.surface, ChamferSmall)
            .clickable(role = Role.Button) { nav.match(match.id) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier.width(3.dp).fillMaxHeight()
                .background(if (match.status == MatchStatus.LIVE) c.spike else c.surface),
        )
        Column(
            Modifier.width(86.dp).padding(start = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (match.status) {
                MatchStatus.LIVE -> StatusTag(MatchStatus.LIVE)
                MatchStatus.COMPLETED -> MonoLabel("Final", color = c.faint)
                MatchStatus.UPCOMING -> {
                    Text(match.startsAt?.let { Time.clock(it, is24) } ?: "TBD", style = Vct.type.data, color = c.ink, maxLines = 1, softWrap = false)
                    match.startsAt?.let { MonoLabel(Time.until(it, now).removePrefix("in "), color = c.faint) }
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 10.dp, end = 14.dp)) {
            SideLine(match.team1, done, match.team2.isWinner)
            Spacer(Modifier.height(4.dp))
            SideLine(match.team2, done, match.team1.isWinner)
            val caption = if (showEvent) listOf(shortEvent(match.eventName), match.series) else listOf(match.series)
            val text = caption.filter { it.isNotBlank() }.joinToString(" · ")
            if (text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                MonoLabel(text, color = c.faint)
            }
        }
    }
}

@Composable
private fun SideLine(side: MatchSide, done: Boolean, otherWon: Boolean) {
    val c = Vct.colors
    val lost = done && otherWon
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(side.team.logo, side.team.name, 20.dp)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                side.team.name,
                style = if (side.isWinner) Vct.type.bodyStrong else Vct.type.body,
                color = if (lost) c.muted else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            CountryTag(side.team.flag)
        }
        Spacer(Modifier.width(8.dp))
        ScoreText(side.score, won = side.isWinner, lost = lost)
    }
}

/** "VCT 2026: Americas Stage 2" -> "Americas Stage 2"; "Valorant Champions 2026" -> "Champions 2026". */
fun shortEvent(name: String): String = name
    .replace(Regex("^VCT \\d{4}:\\s*"), "")
    .replace(Regex("^Champions Tour \\d{4}:\\s*"), "")
    .replace(Regex("^Valorant\\s+", RegexOption.IGNORE_CASE), "")
    .trim()
