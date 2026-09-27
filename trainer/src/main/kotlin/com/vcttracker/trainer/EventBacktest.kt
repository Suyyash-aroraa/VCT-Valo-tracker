package com.vcttracker.trainer

import com.vcttracker.data.EventSummary
import com.vcttracker.data.MatchDay
import com.vcttracker.data.MatchStatus
import com.vcttracker.model.EventFormats
import com.vcttracker.model.EventReport
import com.vcttracker.model.FormatBook
import com.vcttracker.model.forecastEvent
import com.vcttracker.model.stagesOf
import com.vcttracker.model.EventStage
import com.vcttracker.model.Hyper
import com.vcttracker.model.MatchRecord
import com.vcttracker.model.Predictor
import com.vcttracker.model.bracketSlots
import com.vcttracker.model.qualificationOf
import java.time.Instant
import java.time.LocalDate
import kotlin.math.ln

/** A finished event with everything needed to replay it. */
data class ArchivedEvent(
    val summary: EventSummary,
    val stages: List<EventStage>,
    val matches: List<MatchDay>,
    val start: Instant,
    val end: Instant,
)

/** Pre-event forecast vs outcome for one event. */
data class EventResult(
    val event: ArchivedEvent,
    val teams: Int,
    val winnerChance: Double?,
    val favouriteWon: Boolean,
    val winner: String?,
    val favourite: String?,
    /** (chance, advanced) per team per stage with an advancement rule. */
    val advance: List<Pair<Double, Boolean>>,
    val advanceBase: List<Double>,
    /** (chance, qualified) per team per qualification destination. */
    val qualify: List<Pair<Double, Boolean>>,
    val qualifyBase: List<Double>,
)

object EventBacktest {

    private fun teamKey(s: String) = s.trim().lowercase()

    fun load(archive: EventArchive): List<ArchivedEvent> {
        val out = ArrayList<ArchivedEvent>()
        for (year in 2023..LocalDate.now().year) {
            val season = archive.season(year) ?: continue
            for (e in season.events) {
                val raw = archive.stages(e.id)
                if (raw.isEmpty()) continue
                val stages = if (raw.size == 1 && raw[0].first == "main") stagesOf(raw[0].second, emptyMap())
                else stagesOf(raw.first().second, raw.toMap())
                val matches = archive.matches(e.id)
                val times = matches.flatMap { it.matches }.mapNotNull { it.startsAt }
                if (times.isEmpty()) continue
                out += ArchivedEvent(e, stages, matches, times.min(), times.max())
            }
        }
        return out.sortedBy { it.start }
    }

    /** Stage formats learned from every finished event that ended before [before]. */
    fun book(events: List<ArchivedEvent>, before: Instant): FormatBook =
        FormatBook.learn(events.filter { it.summary.status == MatchStatus.COMPLETED && it.end < before }.map { it.stages })

    fun names(data: List<MatchRecord>): Map<String, String> =
        data.flatMap { listOf(teamKey(it.team1) to it.team1Id, teamKey(it.team2) to it.team2Id) }.toMap()

    /** Actual advancement per stage label: teams that got out of it. */
    private fun advancedIn(e: ArchivedEvent): Map<String, Set<String>> {
        val stages = EventFormats.realStages(e.stages)
        return stages.associate { st ->
            st.label to when {
                st.detail.groups.isNotEmpty() -> st.detail.groups.flatMap { g -> g.rows.filter { it.advanced }.map { teamKey(it.team.name) } }.toSet()
                else -> bracketSlots(st.detail).filter { it.single }.map { teamKey(it.match.team1.team.name) }.toSet()
            }
        }
    }

    fun run(data: List<MatchRecord>, hyper: Hyper, events: List<ArchivedEvent>, from: Instant, runs: Int, log: (String) -> Unit): List<EventResult> {
        val predictor = Predictor(hyper)
        val ids = names(data)
        var next = 0
        val out = ArrayList<EventResult>()
        for (e in events) {
            // Learn from every match before the event starts, nothing after.
            while (next < data.size && data[next].time < e.start) predictor.observe(data[next++])
            if (e.start < from || e.summary.status != MatchStatus.COMPLETED) continue
            val forecast = forecastEvent(predictor, book(events, e.start), e.stages, e.matches, ids, e.start, runs, asOfStart = true)
            if (forecast == null) {
                log("  skip ${e.summary.name}: a bracket shape not seen finished before")
                continue
            }
            val outlook = forecast.teams
            val byTeam = outlook.associateBy { it.team }
            val winner = e.stages.flatMap { it.detail.prizes }.firstOrNull { it.place.startsWith("1st") }?.team?.name?.let(::teamKey)
            val teams = byTeam.keys.size
            val fav = outlook.maxByOrNull { it.win }?.team

            val adv = ArrayList<Pair<Double, Boolean>>()
            val advBase = ArrayList<Double>()
            for ((label, actual) in advancedIn(e)) {
                val entrants = outlook.filter { it.advance.containsKey(label) && (it.advance[label] ?: 0.0) >= 0.0 }
                    .filter { o -> e.stages.first { it.label == label }.let { st -> teamIn(st, o.team) } }
                if (entrants.isEmpty() || actual.isEmpty()) continue
                val base = actual.size.toDouble() / entrants.size
                for (o in entrants) {
                    adv += (o.advance[label] ?: 0.0) to (o.team in actual)
                    advBase += base
                }
            }
            val qual = ArrayList<Pair<Double, Boolean>>()
            val qualBase = ArrayList<Double>()
            val actualPlace = e.stages.flatMap { it.detail.prizes }.mapNotNull { p -> p.team?.let { teamKey(it.name) to p.place } }.toMap()
            val notes = e.stages.flatMap { it.detail.prizes }.mapNotNull { p -> qualificationOf(p.note)?.let { p.place to it } }.toMap()
            for (dest in notes.values.toSet()) {
                val spots = e.stages.flatMap { it.detail.prizes }.count { qualificationOf(it.note) == dest }
                for (o in outlook) {
                    val qualified = actualPlace[o.team]?.let { notes[it] == dest } == true
                    qual += (o.qualify[dest] ?: 0.0) to qualified
                    qualBase += spots.toDouble() / teams
                }
            }
            if (System.getenv("VCT_DEBUG_EVENT")?.let { e.summary.name.contains(it) } == true) {
                outlook.forEach { o -> println("   %-22s win %.3f adv %s places %s".format(o.team, o.win, o.advance.mapValues { "%.2f".format(it.value) }, o.places.mapValues { "%.2f".format(it.value) })) }
                println("   actual advanced: ${advancedIn(e)}")
            }
            val nameOf = { k: String? -> k?.let { forecast.display[it]?.name ?: it } }
            out += EventResult(e, teams, winner?.let { byTeam[it]?.win ?: 0.0 }, fav == winner, nameOf(winner), nameOf(fav), adv, advBase, qual, qualBase)
            log("  ${e.summary.name}: winner %s at %.1f%%, favourite %s".format(winner, (winner?.let { byTeam[it]?.win } ?: 0.0) * 100, fav))
        }
        return out
    }

    private fun teamIn(st: EventStage, team: String): Boolean =
        st.detail.groups.any { g -> g.rows.any { teamKey(it.team.name) == team } } ||
            bracketSlots(st.detail).any { s -> !s.single && listOfNotNull(s.match.team1.team.name, s.match.team2?.team?.name).any { teamKey(it) == team } }

    /** Mean log-loss, clipping so a 0% on something that happened doesn't blow up. */
    fun logLoss(ps: List<Pair<Double, Boolean>>): Double =
        ps.map { (p, y) -> val q = p.coerceIn(0.005, 0.995); if (y) -ln(q) else -ln(1 - q) }.average()

    fun report(results: List<EventResult>): EventReport {
        val withWinner = results.filter { it.winnerChance != null }
        val adv = results.flatMap { it.advance }
        val advBase = results.flatMap { r -> r.advance.indices.map { i -> r.advanceBase[i] to r.advance[i].second } }
        val q = results.flatMap { it.qualify }
        val qBase = results.flatMap { r -> r.qualify.indices.map { i -> r.qualifyBase[i] to r.qualify[i].second } }
        fun avg(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.average()
        return EventReport(
            events = results.size,
            winnerChance = avg(withWinner.map { it.winnerChance!! }),
            uniformChance = avg(withWinner.map { 1.0 / it.teams }),
            winnerLogLoss = avg(withWinner.map { -ln(it.winnerChance!!.coerceAtLeast(0.005)) }),
            uniformLogLoss = avg(withWinner.map { ln(it.teams.toDouble()) }),
            favouriteWon = withWinner.count { it.favouriteWon },
            withWinner = withWinner.size,
            advanceN = adv.size,
            advanceLogLoss = if (adv.isEmpty()) 0.0 else logLoss(adv),
            advanceBase = if (adv.isEmpty()) 0.0 else logLoss(advBase),
            qualifyN = q.size,
            qualifyLogLoss = if (q.isEmpty()) 0.0 else logLoss(q),
            qualifyBase = if (q.isEmpty()) 0.0 else logLoss(qBase),
        )
    }

    fun summary(results: List<EventResult>): List<String> {
        val r = report(results)
        return listOf(
            "Events forecast: ${r.events}",
            "Winner: model gave the actual winner %.1f%% on average (uniform %.1f%%); winner log-loss %.3f vs uniform %.3f; favourite won %d of %d".format(
                r.winnerChance * 100, r.uniformChance * 100, r.winnerLogLoss, r.uniformLogLoss, r.favouriteWon, r.withWinner),
            "Advancing from a stage: log-loss %.3f vs %.3f for the no-skill baseline (%d team-stages)".format(r.advanceLogLoss, r.advanceBase, r.advanceN),
            "Qualifying: log-loss %.3f vs %.3f for the no-skill baseline (%d team-destinations)".format(r.qualifyLogLoss, r.qualifyBase, r.qualifyN),
        )
    }
}
