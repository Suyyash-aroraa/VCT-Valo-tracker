package com.vcttracker.model

import com.vcttracker.data.MatchDay
import com.vcttracker.data.PrizeRow
import com.vcttracker.data.Team
import java.time.Instant

/** A whole event, simulated from where it stands now. */
data class EventForecast(
    /** Best chance to win first. */
    val teams: List<TeamOutlook>,
    /** Display name, logo and id per team key. */
    val display: Map<String, Team>,
    /** Stages a team can advance out of, in playing order. */
    val stages: List<String>,
    /** Qualifications the event awards (e.g. "Champions", "Masters London"), with the number of spots. */
    val destinations: Map<String, Int>,
    /** Whether the event decides a single winner (Kickoffs end in several qualification slots). */
    val hasWinner: Boolean,
    val runs: Int,
)

/** Series win probabilities between team keys, cached, from the predictor's state on [day]. */
fun Predictor.seriesOdds(ids: Map<String, String>, day: Long, vetoRuns: Int = 40): (String, String, Int) -> Double {
    val cache = HashMap<Triple<String, String, Int>, Double>()
    return { a, b, bo ->
        cache.getOrPut(Triple(a, b, bo)) {
            predict(ids[a] ?: "new:$a", ids[b] ?: "new:$b", bo, day, vetoRuns).team1Wins
        }
    }
}

/** Prize place -> qualification it earns ("1st" -> "Champions"). */
fun qualificationsOf(prizes: List<PrizeRow>): Map<String, String> =
    prizes.mapNotNull { p -> qualificationOf(p.note)?.let { p.place to it } }.toMap()

/**
 * Simulates an event's remaining matches. Results already played are kept; everything still
 * to come is drawn from the match model. Null when a stage's format has never been seen finished.
 */
fun forecastEvent(
    predictor: Predictor,
    book: FormatBook,
    allStages: List<EventStage>,
    matches: List<MatchDay>,
    teamIdsByName: Map<String, String>,
    now: Instant = Instant.now(),
    runs: Int = 2000,
    asOfStart: Boolean = false,
): EventForecast? {
    val format = book.formatFor(allStages) ?: return null
    val plan = EventFormats.plan(allStages, matches, format, asOfStart) ?: return null
    val stages = EventFormats.realStages(allStages)
    val prizes = stages.flatMap { it.detail.prizes }.ifEmpty { allStages.flatMap { it.detail.prizes } }
    val notes = qualificationsOf(prizes)
    val display = HashMap<String, Team>()
    fun see(t: Team?) {
        if (t == null || t.name.isBlank() || t.name == "TBD") return
        val k = teamKey(t.name)
        val old = display[k]
        if (old == null || (old.logo == null && t.logo != null) || (old.id == null && t.id != null)) {
            display[k] = t.copy(logo = t.logo ?: old?.logo, id = t.id ?: old?.id)
        }
    }
    allStages.forEach { st ->
        st.detail.groups.forEach { g -> g.rows.forEach { see(it.team) } }
        bracketSlots(st.detail).forEach { s -> see(s.match.team1.team); see(s.match.team2?.team) }
        st.detail.teams.forEach { see(it.team) }
        st.detail.prizes.forEach { see(it.team) }
    }
    matches.flatMap { it.matches }.forEach { see(it.team1.team); see(it.team2.team) }
    val ids = display.mapNotNull { (k, t) -> (t.id?.takeIf { it in predictor.names } ?: teamIdsByName[k])?.let { k to it } }.toMap()
    val odds = predictor.seriesOdds(ids, now.epochSecond / 86_400)
    val outlook = TournamentSim(plan, emptyMap(), odds, notes, ids).simulate(runs)
    val last = plan.last()
    val hasWinner = last !is StagePlan.Bracket || last.template.finalMatch != null ||
        last.template.eliminations.values.any { it.startsWith("1st") }
    return EventForecast(
        teams = outlook,
        display = display,
        stages = plan.filter { p -> p !is StagePlan.Bracket || p.template.finalMatch == null }.map { it.label },
        destinations = notes.values.groupingBy { it }.eachCount(),
        hasWinner = hasWinner,
        runs = runs,
    )
}
