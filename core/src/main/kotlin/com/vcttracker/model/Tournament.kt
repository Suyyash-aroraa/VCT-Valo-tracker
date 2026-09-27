package com.vcttracker.model

import com.vcttracker.data.BracketMatch
import com.vcttracker.data.EventDetail
import com.vcttracker.data.GroupTable
import java.util.Locale
import kotlin.random.Random

/*
 * Tournament forecasting.
 *
 * vlr.gg shows brackets but not their wiring: it never says where a match's winner or loser
 * goes next. Formats repeat, though, so the wiring is learned from finished events: in a
 * completed bracket each team's previous match (and whether they won it) shows exactly which
 * slot feeds which. The same goes for how group placements seed the next stage, and for
 * which final placement each elimination round is worth.
 */

internal fun teamKey(name: String) = name.trim().lowercase(Locale.US)

/** Where a bracket slot's team comes from. */
sealed interface Feed {
    /** The team vlr.gg placed there (or, before seeding, whoever the previous stage sends). */
    data object Seed : Feed
    data class Winner(val match: String) : Feed
    data class Loser(val match: String) : Feed
}

/** One bracket slot: a match, or a single "Qualified" placeholder. */
data class Slot(val key: String, val label: String, val single: Boolean, val match: BracketMatch)

/** Every slot of a bracket stage, keyed "section.bracket.column.row". */
fun bracketSlots(d: EventDetail): List<Slot> = buildList {
    d.sections.forEachIndexed { s, section ->
        section.brackets.forEachIndexed { b, bracket ->
            bracket.columns.forEachIndexed { c, col ->
                col.matches.forEachIndexed { r, m -> add(Slot("$s.$b.$c.$r", col.label, m.team2 == null, m)) }
            }
        }
    }
}

/** A bracket stage's layout as a string: two stages with the same shape play the same format. */
fun bracketShape(d: EventDetail): String = d.sections.joinToString("|") { s ->
    s.brackets.joinToString(";") { b -> b.columns.joinToString(",") { "${it.label.trim()}=${it.matches.size}" } }
}

/** Round-robin and Swiss tables, by size. */
fun groupShape(d: EventDetail): String = d.groups.joinToString(",") { it.rows.size.toString() }

/** How a bracket stage is wired, learned from finished events with the same shape. */
data class BracketTemplate(
    val shape: String,
    /** Slot key -> where its team(s) come from (two feeds for a match, one for a Qualified slot). */
    val feeds: Map<String, List<Feed>>,
    /** Slot keys in playing order. */
    val order: List<String>,
    /** Loser of this match, if it's their last, finishes in this place (e.g. "5th–6th"). */
    val eliminations: Map<String, String>,
    /** The match whose winner wins the stage (a grand final), if there is one. */
    val finalMatch: String?,
    /** How many finished events agreed on this wiring. */
    val support: Int,
)

object Templates {

    private fun playOrder(slots: List<Slot>): List<Slot> {
        val matches = slots.filter { !it.single }
        val singles = slots.filter { it.single }
        // Timestamps order play; column order breaks ties and covers missing times.
        return matches.sortedWith(compareBy({ it.match.startsAt?.epochSecond ?: Long.MAX_VALUE }, { it.key.split('.')[2].toInt() })) + singles
    }

    private fun teamsOf(s: Slot) = listOfNotNull(s.match.team1.team.name, s.match.team2?.team?.name)
        .filter { it.isNotBlank() && it != "TBD" }.map(::teamKey)

    private fun winnerOf(s: Slot): String? = when {
        s.match.team1.isWinner -> teamKey(s.match.team1.team.name)
        s.match.team2?.isWinner == true -> teamKey(s.match.team2!!.team.name)
        else -> null
    }

    /**
     * Reads the wiring off a finished bracket stage. Returns null if any match is unfinished
     * or a team's path can't be traced.
     */
    fun learn(d: EventDetail, places: Map<String, String>): BracketTemplate? {
        val slots = bracketSlots(d)
        if (slots.none { !it.single }) return null
        val ordered = playOrder(slots)
        val lastSeen = HashMap<String, Slot>()
        val feeds = LinkedHashMap<String, List<Feed>>()
        val lastMatchOf = HashMap<String, Slot>()
        for (s in ordered) {
            val teams = if (s.single) teamsOf(s).take(1) else teamsOf(s)
            // An empty single slot is a bye placeholder, not part of the wiring.
            if (s.single && teams.isEmpty()) continue
            if (!s.single && (teams.size != 2 || winnerOf(s) == null)) return null
            feeds[s.key] = teams.map { t ->
                val prev = lastSeen[t]
                when {
                    prev == null -> Feed.Seed
                    winnerOf(prev) == t -> Feed.Winner(prev.key)
                    else -> Feed.Loser(prev.key)
                }
            }
            if (!s.single) teams.forEach { lastSeen[it] = s; lastMatchOf[it] = s }
        }
        // A team's last match is where it went out (or won). Its prize place labels that exit.
        val eliminations = HashMap<String, String>()
        for ((team, slot) in lastMatchOf) {
            val place = places[team] ?: continue
            if (winnerOf(slot) != team) eliminations[slot.key] = place
        }
        // A "Qualified" slot is worth a placement too when it's where the event ends (Kickoffs).
        for (s in ordered) if (s.single) teamsOf(s).firstOrNull()?.let { places[it] }?.let { eliminations[s.key] = it }
        val qualifiedFrom = ordered.filter { it.single }.mapNotNull { (feeds[it.key]?.firstOrNull() as? Feed.Winner)?.match }.toSet()
        val finalMatch = ordered.lastOrNull { !it.single && it.label.contains("Final", true) && it.key !in qualifiedFrom }
            ?.takeIf { f -> ordered.none { o -> feeds[o.key]?.any { it == Feed.Winner(f.key) || it == Feed.Loser(f.key) } == true } }
            ?.key
        return BracketTemplate(bracketShape(d), feeds, ordered.map { it.key }, eliminations, finalMatch, 1)
    }

    /**
     * Combines finished examples of one shape. The wiring most of them agree on wins (which of
     * a match's two teams vlr lists first doesn't count as a difference); with no majority the
     * shape isn't trusted. Examples are oldest first, so the latest agreeing one is kept.
     */
    fun merge(examples: List<BracketTemplate>): BracketTemplate? {
        if (examples.isEmpty()) return null
        fun wiring(t: BracketTemplate) = t.feeds.mapValues { (_, f) -> f.map { it.toString() }.sorted() }
        val groups = examples.groupBy(::wiring)
        val (_, agreeing) = groups.maxBy { it.value.size }
        if (agreeing.size * 2 <= examples.size && examples.size > 1) return null
        val latest = agreeing.last()
        // Placement labels can differ slightly between events; keep the most common per match.
        val elim = agreeing.flatMap { it.eliminations.keys }.toSet().associateWith { k ->
            agreeing.mapNotNull { it.eliminations[k] }.groupingBy { it }.eachCount().maxBy { it.value }.key
        }
        return latest.copy(eliminations = elim, support = agreeing.size)
    }
}

/** A team's chances across one simulated event. */
data class TeamOutlook(
    val team: String,
    val teamId: String?,
    /** Chance to get out of each stage it plays in, by stage label. */
    val advance: Map<String, Double>,
    val win: Double,
    /** Chance of each final placement label ("1st", "2nd", "3rd–4th", …). */
    val places: Map<String, Double>,
    /** Chance to earn each qualification named in the prize notes (e.g. "Champions"). */
    val qualify: Map<String, Double>,
)

/** One stage of an event as the simulator sees it. */
sealed interface StagePlan {
    val label: String

    data class Bracket(override val label: String, val detail: EventDetail, val template: BracketTemplate) : StagePlan

    data class RoundRobin(
        override val label: String,
        val groups: List<GroupTable>,
        /** Matches still to play, by team key. */
        val remaining: List<Pair<String, String>>,
        /** Played results already in the table are kept; these are (team, wins, losses, mapDiff). */
        val advancing: Int?,
    ) : StagePlan

    data class Swiss(
        override val label: String,
        val table: GroupTable,
        val winsToQualify: Int,
        val lossesToExit: Int,
        val remaining: List<Pair<String, String>>,
    ) : StagePlan
}

/**
 * Plays an event out many times. Matches already played keep their real result; everything
 * else is drawn from [winProb]. Stages hand their qualifiers to the next stage through
 * [seeding] (learned from past events), unless vlr.gg already shows the next stage's teams.
 */
class TournamentSim(
    private val stages: List<StagePlan>,
    /** For a stage index and slot key+position, which earlier output feeds it: "stage:output". */
    private val seeding: Map<String, String>,
    private val winProb: (String, String, Int) -> Double,
    private val prizeNotes: Map<String, String>,
    private val teamIds: Map<String, String>,
) {
    private class Run {
        /** Outputs of each stage: ordered qualifier lists, by "stageIndex:output". */
        val outputs = HashMap<String, String>()
        val advanced = HashMap<String, MutableSet<String>>()
        val place = HashMap<String, String>()
        var champion: String? = null
    }

    private fun bestOf(label: String): Int = if (label.contains("Grand Final", true) || label.contains("Lower Final", true)) 5 else 3

    private fun play(a: String, b: String, bo: Int, rng: Random): Boolean = rng.nextDouble() < winProb(a, b, bo)

    private fun runBracket(i: Int, st: StagePlan.Bracket, run: Run, rng: Random) {
        val slots = bracketSlots(st.detail).associateBy { it.key }
        // Seed slots with no known team and no learned source are filled at random from the
        // previous stage's qualifiers that haven't been placed yet.
        val pool = ArrayDeque(
            (run.advanced.filterValues { i > 0 && stages[i - 1].label in it }.keys - shownTeams(st) - seededFrom(i, st, run)).shuffled(rng),
        )
        val result = HashMap<String, Pair<String, String>>() // key -> (winner, loser)
        val lastLost = HashMap<String, String>()
        val alive = HashSet<String>()
        for (key in st.template.order) {
            val slot = slots[key] ?: continue
            val feeds = st.template.feeds[key] ?: continue
            val shown = listOfNotNull(slot.match.team1, slot.match.team2)
            val teams = feeds.mapIndexed { pos, feed ->
                when (feed) {
                    is Feed.Winner -> result[feed.match]?.first
                    is Feed.Loser -> result[feed.match]?.second
                    Feed.Seed -> shown.getOrNull(pos)?.team?.name?.takeIf { it.isNotBlank() && it != "TBD" }?.let(::teamKey)
                        ?: seeding["$i:$key:$pos"]?.let { run.outputs[it] }
                        ?: pool.removeFirstOrNull()
                }
            }
            if (teams.any { it == null }) continue
            if (slot.single) {
                val t = teams[0]!!
                run.outputs["$i:$key"] = t
                run.advanced.getOrPut(t) { HashSet() } += st.label
                // Where an event ends in "Qualified" slots, those slots are its placements.
                if (i == stages.lastIndex) st.template.eliminations[key]?.let { place ->
                    run.place.putIfAbsent(t, place)
                    if (place.startsWith("1st")) run.champion = t
                }
                continue
            }
            val (a, b) = teams[0]!! to teams[1]!!
            alive += a; alive += b
            // A finished match keeps its real result.
            val real = when {
                slot.match.team1.isWinner && teamKey(slot.match.team1.team.name) == a -> a to b
                slot.match.team1.isWinner && teamKey(slot.match.team1.team.name) == b -> b to a
                slot.match.team2?.isWinner == true && teamKey(slot.match.team2!!.team.name) == a -> a to b
                slot.match.team2?.isWinner == true && teamKey(slot.match.team2!!.team.name) == b -> b to a
                else -> null
            }
            val wl = real ?: if (play(a, b, bestOf(slot.label), rng)) a to b else b to a
            result[key] = wl
            lastLost[wl.second] = key
        }
        for ((team, key) in lastLost) {
            // Knocked out here only if they never played again.
            val later = result.entries.any { (k, wl) -> st.template.order.indexOf(k) > st.template.order.indexOf(key) && (wl.first == team || wl.second == team) }
            if (!later) st.template.eliminations[key]?.let { run.place.putIfAbsent(team, it) }
        }
        st.template.finalMatch?.let { f ->
            result[f]?.let { (w, l) ->
                run.champion = w
                run.place.putIfAbsent(w, "1st")
                run.place.putIfAbsent(l, "2nd")
            }
        }
    }

    private fun shownTeams(st: StagePlan.Bracket): Set<String> = bracketSlots(st.detail).flatMap { s ->
        listOfNotNull(s.match.team1.team.name, s.match.team2?.team?.name)
    }.filter { it.isNotBlank() && it != "TBD" }.map(::teamKey).toSet()

    private fun seededFrom(i: Int, st: StagePlan.Bracket, run: Run): Set<String> =
        seeding.filterKeys { it.startsWith("$i:") }.values.mapNotNull { run.outputs[it] }.toSet()

    /** Standings: wins, then map difference; exact ties are split at random. */
    private fun runRoundRobin(i: Int, st: StagePlan.RoundRobin, run: Run, rng: Random) {
        val wins = HashMap<String, Int>()
        val maps = HashMap<String, Int>()
        for (g in st.groups) for (r in g.rows) {
            val k = teamKey(r.team.name)
            wins[k] = r.record.substringBefore('–').substringBefore('-').trim().toIntOrNull() ?: 0
            val (mw, ml) = r.maps.split('/').map { it.trim().toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 }) to (it.getOrElse(1) { 0 }) }
            maps[k] = mw - ml
        }
        for ((a, b) in st.remaining) {
            val aWins = play(a, b, 3, rng)
            val (w, l) = if (aWins) a to b else b to a
            wins[w] = (wins[w] ?: 0) + 1
            wins.putIfAbsent(l, 0)
            val sweep = rng.nextDouble() < 0.55
            maps[w] = (maps[w] ?: 0) + if (sweep) 2 else 1
            maps[l] = (maps[l] ?: 0) - if (sweep) 2 else 1
        }
        st.groups.forEachIndexed { g, table ->
            val ranked = table.rows.map { teamKey(it.team.name) }
                .sortedWith(compareByDescending<String> { wins[it] ?: 0 }.thenByDescending { maps[it] ?: 0 }.thenBy { rng.nextDouble() })
            ranked.forEachIndexed { rank, t ->
                run.outputs["$i:g$g:${rank + 1}"] = t
                if (st.advancing != null && rank < st.advancing) run.advanced.getOrPut(t) { HashSet() } += st.label
            }
        }
    }

    private fun runSwiss(i: Int, st: StagePlan.Swiss, run: Run, rng: Random) {
        val w = HashMap<String, Int>()
        val l = HashMap<String, Int>()
        val met = HashSet<String>()
        for (r in st.table.rows) {
            val k = teamKey(r.team.name)
            w[k] = r.record.substringBefore('–').substringBefore('-').trim().toIntOrNull() ?: 0
            l[k] = r.record.substringAfter('–', r.record.substringAfter('-', "0")).trim().toIntOrNull() ?: 0
        }
        fun open(t: String) = (w[t] ?: 0) < st.winsToQualify && (l[t] ?: 0) < st.lossesToExit
        fun result(a: String, b: String) {
            met += "$a|$b"; met += "$b|$a"
            if (play(a, b, 3, rng)) { w[a] = (w[a] ?: 0) + 1; l[b] = (l[b] ?: 0) + 1 } else { w[b] = (w[b] ?: 0) + 1; l[a] = (l[a] ?: 0) + 1 }
        }
        st.remaining.filter { open(it.first) && open(it.second) }.forEach { (a, b) -> result(a, b) }
        // Later rounds pair teams on the same record, avoiding rematches where possible.
        repeat(10) {
            val pools = w.keys.filter(::open).groupBy { (w[it] ?: 0) to (l[it] ?: 0) }
            if (pools.isEmpty()) return@repeat
            for (pool in pools.values) {
                val left = pool.shuffled(rng).toMutableList()
                while (left.size >= 2) {
                    val a = left.removeAt(0)
                    val b = left.firstOrNull { "$a|$it" !in met } ?: left.first()
                    left.remove(b)
                    result(a, b)
                }
            }
        }
        val ranked = w.keys.sortedWith(compareByDescending<String> { (w[it] ?: 0) - (l[it] ?: 0) }.thenByDescending { w[it] ?: 0 }.thenBy { rng.nextDouble() })
        ranked.forEachIndexed { rank, t ->
            run.outputs["$i:s:${rank + 1}"] = t
            if ((w[t] ?: 0) >= st.winsToQualify) run.advanced.getOrPut(t) { HashSet() } += st.label
        }
    }

    fun simulate(runs: Int = 2000, seed: Int = 11): List<TeamOutlook> {
        val rng = Random(seed)
        val teams = HashSet<String>()
        val advance = HashMap<String, HashMap<String, Int>>()
        val wins = HashMap<String, Int>()
        val places = HashMap<String, HashMap<String, Int>>()
        repeat(runs) {
            val run = Run()
            stages.forEachIndexed { i, st ->
                when (st) {
                    is StagePlan.Bracket -> runBracket(i, st, run, rng)
                    is StagePlan.RoundRobin -> runRoundRobin(i, st, run, rng)
                    is StagePlan.Swiss -> runSwiss(i, st, run, rng)
                }
            }
            run.outputs.values.forEach { teams += it }
            run.place.keys.forEach { teams += it }
            run.advanced.forEach { (t, s) -> s.forEach { label -> advance.getOrPut(t) { HashMap() }.merge(label, 1, Int::plus) } }
            run.champion?.let { wins.merge(it, 1, Int::plus) }
            run.place.forEach { (t, p) -> places.getOrPut(t) { HashMap() }.merge(p, 1, Int::plus) }
        }
        val stageLabels = stages.map { it.label }
        return teams.map { t ->
            val pl = places[t].orEmpty().mapValues { it.value.toDouble() / runs }
            TeamOutlook(
                team = t,
                teamId = teamIds[t],
                advance = stageLabels.associateWith { (advance[t]?.get(it) ?: 0).toDouble() / runs }
                    .filterKeys { label -> stages.first { it.label == label } !is StagePlan.Bracket || (stages.first { it.label == label } as StagePlan.Bracket).template.finalMatch == null },
                win = (wins[t] ?: 0).toDouble() / runs,
                places = pl,
                qualify = prizeNotes.entries.groupBy({ it.value }, { it.key }).mapValues { (_, placeLabels) ->
                    placeLabels.sumOf { pl[it] ?: 0.0 }
                },
            )
        }.sortedByDescending { it.win }
    }
}
