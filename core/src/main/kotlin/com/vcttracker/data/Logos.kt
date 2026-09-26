package com.vcttracker.data

import java.util.Locale

/** vlr.gg match lists carry no team logos, so we borrow them from event pages. */
fun EventDetail.logoMap(): Map<String, String> {
    val map = HashMap<String, String>()
    fun add(team: Team?) {
        val logo = team?.logo ?: return
        if (team.name.isNotBlank()) map.putIfAbsent(key(team.name), logo)
    }
    teams.forEach { add(it.team) }
    groups.forEach { g -> g.rows.forEach { add(it.team) } }
    prizes.forEach { add(it.team) }
    sections.forEach { s ->
        s.brackets.forEach { b -> b.columns.forEach { c -> c.matches.forEach { add(it.team1.team); add(it.team2?.team) } } }
    }
    return map
}

private fun key(name: String) = name.trim().lowercase(Locale.US)

fun List<MatchDay>.withLogos(logos: Map<String, String>): List<MatchDay> {
    if (logos.isEmpty()) return this
    fun MatchSide.fill() = if (team.logo != null) this else copy(team = team.copy(logo = logos[key(team.name)]))
    return map { day -> day.copy(matches = day.matches.map { it.copy(team1 = it.team1.fill(), team2 = it.team2.fill()) }) }
}
