package com.vcttracker.model

import com.vcttracker.data.BracketMatch
import com.vcttracker.data.BracketTeam
import com.vcttracker.data.EventDetail
import com.vcttracker.data.GroupRow
import com.vcttracker.data.GroupTable
import com.vcttracker.data.MatchDay
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.Team

/** One stage page of an event, oldest stage first. */
data class EventStage(val slug: String, val label: String, val detail: EventDetail)

/**
 * An event's stage pages in playing order. [pages] maps each sub-page's slug to its parsed
 * page; vlr.gg lists the latest stage first, so the order is reversed.
 */
fun stagesOf(main: EventDetail, pages: Map<String, EventDetail>): List<EventStage> {
    if (main.subPages.isEmpty()) return listOf(EventStage("main", "Main event", main))
    return main.subPages.reversed().mapNotNull { sp ->
        val slug = sp.path.trimEnd('/').substringAfterLast('/')
        pages[slug]?.let { EventStage(slug, sp.label.ifBlank { slug.replace('-', ' ').replaceFirstChar { c -> c.uppercase() } }, it) }
    }
}

fun subPageSlug(path: String) = path.trimEnd('/').substringAfterLast('/')

/** What kind of stage a page is, and its shape; two events with the same kinds play the same format. */
fun stageKind(st: EventStage): String? = when {
    st.slug.contains("showmatch", true) -> null
    st.detail.groups.isNotEmpty() && st.slug.contains("swiss", true) -> "S:${groupShape(st.detail)}"
    st.detail.groups.isNotEmpty() -> "R:${groupShape(st.detail)}"
    st.detail.sections.isNotEmpty() -> "B:${bracketShape(st.detail)}"
    else -> null
}

fun formatKey(stages: List<EventStage>): String = stages.mapNotNull(::stageKind).joinToString(" > ")

/** A format learned from finished events: bracket wiring, seeding between stages, group rules. */
data class EventFormat(
    val key: String,
    /** By stage index (showmatches removed). */
    val brackets: Map<Int, BracketTemplate>,
    /** "stage:slot:pos" -> "earlierStage:output". Only slots every example agreed on. */
    val seeding: Map<String, String>,
    /** Round-robin stages: how many advance from each group. */
    val advancing: Map<Int, Int>,
    /** Swiss stages: (wins to qualify, losses to go out). */
    val swiss: Map<Int, Pair<Int, Int>>,
    val support: Int,
)

/** Turns messy prize notes into a destination: "Masters London Team Heretics is …" -> "Masters London". */
fun qualificationOf(note: String?): String? {
    if (note.isNullOrBlank()) return null
    Regex("Masters [A-Z][a-zA-Z]+").find(note)?.let { return it.value }
    if (note.contains("Champions", true) && !note.contains("slot", true)) return "Champions"
    return null
}

/**
 * Stage-by-stage knowledge from every finished event. Whole event formats change most years,
 * but their parts repeat: a playoff bracket shape, a Swiss table, "two groups of six feed an
 * eight-team bracket". A new event is forecast by assembling it from these parts.
 */
data class FormatBook(
    /** Bracket shape -> wiring. */
    val brackets: Map<String, BracketTemplate>,
    /** Swiss table shape -> (wins to qualify, losses to go out). */
    val swiss: Map<String, Pair<Int, Int>>,
    /** "previous stage kind > this stage kind" -> how many advance per group of the previous stage. */
    val advancing: Map<String, Int>,
    /** "previous stage kind > this bracket kind" -> "slot:pos" -> the previous stage's output that fills it. */
    val seeding: Map<String, Map<String, String>>,
) {
    /** Builds [stages]'s format from known parts. Null if a bracket shape has never been seen finished. */
    fun formatFor(allStages: List<EventStage>): EventFormat? {
        val stages = EventFormats.realStages(allStages)
        if (stages.isEmpty()) return null
        val kinds = stages.map { stageKind(it)!! }
        val brackets = HashMap<Int, BracketTemplate>()
        val swissRules = HashMap<Int, Pair<Int, Int>>()
        val adv = HashMap<Int, Int>()
        val seeds = HashMap<String, String>()
        stages.forEachIndexed { i, st ->
            val next = kinds.getOrNull(i + 1)
            when {
                kinds[i].startsWith("S:") -> swissRules[i] = swiss[groupShape(st.detail)] ?: EventFormats.defaultSwiss(st.detail.groups.first().rows.size)
                kinds[i].startsWith("R:") -> (next?.let { advancing["${kinds[i]} > $it"] } ?: inferAdvancing(stages, i))?.let { adv[i] = it }
                else -> {
                    brackets[i] = this.brackets[bracketShape(st.detail)] ?: return null
                    if (i > 0) seeding["${kinds[i - 1]} > ${kinds[i]}"]?.forEach { (slot, out) -> seeds["$i:$slot"] = "${i - 1}:$out" }
                }
            }
        }
        return EventFormat(formatKey(stages), brackets, seeds, adv, swissRules, 0)
    }

    /** With no precedent, a group stage sends on as many teams as the next bracket has open seeds. */
    private fun inferAdvancing(stages: List<EventStage>, i: Int): Int? {
        val next = stages.getOrNull(i + 1) ?: return null
        val t = brackets[bracketShape(next.detail)] ?: return null
        // Seeds already filled by teams that don't play the group stage were invited directly.
        val members = stages[i].detail.groups.flatMap { g -> g.rows.map { teamKey(it.team.name) } }.toSet()
        val slots = bracketSlots(next.detail).associateBy { it.key }
        var seeds = 0
        var known = 0
        for ((key, feeds) in t.feeds) feeds.forEachIndexed { pos, f ->
            val slot = slots[key]
            if (f != Feed.Seed || slot == null || slot.single) return@forEachIndexed
            seeds++
            val name = listOfNotNull(slot.match.team1, slot.match.team2).getOrNull(pos)?.team?.name
            if (!name.isNullOrBlank() && name != "TBD" && teamKey(name) !in members) known++
        }
        val groups = stages[i].detail.groups.size
        val open = seeds - known
        return if (groups > 0 && open > 0 && open % groups == 0) open / groups else null
    }

    companion object {
        fun learn(events: List<List<EventStage>>): FormatBook {
            val brackets = HashMap<String, MutableList<BracketTemplate>>()
            val swiss = HashMap<String, MutableList<Pair<Int, Int>>>()
            val advancing = HashMap<String, MutableList<Int>>()
            val seeding = HashMap<String, MutableList<Map<String, String>>>()
            for (ev in events) {
                val f = EventFormats.learn(ev) ?: continue
                val stages = EventFormats.realStages(ev)
                val kinds = stages.map { stageKind(it)!! }
                stages.forEachIndexed { i, st ->
                    f.brackets[i]?.let { brackets.getOrPut(it.shape) { ArrayList() } += it }
                    f.swiss[i]?.let { swiss.getOrPut(groupShape(st.detail)) { ArrayList() } += it }
                    val next = kinds.getOrNull(i + 1)
                    if (next != null) f.advancing[i]?.let { advancing.getOrPut("${kinds[i]} > $next") { ArrayList() } += it }
                    if (i > 0 && f.brackets[i] != null) {
                        val fromPrev = f.seeding.filter { (k, v) -> k.startsWith("$i:") && v.startsWith("${i - 1}:") }
                            .map { (k, v) -> k.substringAfter(':') to v.substringAfter(':') }.toMap()
                        seeding.getOrPut("${kinds[i - 1]} > ${kinds[i]}") { ArrayList() } += fromPrev
                    }
                }
            }
            fun <T> majority(xs: List<T>): T? = xs.groupingBy { it }.eachCount().maxByOrNull { it.value }
                ?.takeIf { it.value * 2 > xs.size || xs.size == 1 }?.key
            return FormatBook(
                brackets = brackets.mapNotNull { (k, v) -> Templates.merge(v)?.let { k to it } }.toMap(),
                swiss = swiss.mapNotNull { (k, v) -> majority(v)?.let { k to it } }.toMap(),
                advancing = advancing.mapNotNull { (k, v) -> majority(v)?.let { k to it } }.toMap(),
                // A seed slot's source is kept only where every example agrees.
                seeding = seeding.mapValues { (_, maps) ->
                    maps.first().filter { (slot, out) -> maps.all { it[slot] == out } }
                },
            )
        }
    }
}

object EventFormats {

    /** Swiss with no precedent: 8 teams play to 2 wins or 2 losses, 16 to 3 and 3. */
    fun defaultSwiss(teams: Int): Pair<Int, Int> {
        var n = 2
        while ((1 shl (n + 1)) <= teams) n++
        return (n - 1).coerceAtLeast(1) to (n - 1).coerceAtLeast(1)
    }

    /** Stages that decide something (no showmatches), each with only the tables that rank teams. */
    fun realStages(stages: List<EventStage>) = stages.filter { stageKind(it) != null }
        .map { st -> st.copy(detail = st.detail.copy(groups = rankingTables(st.detail.groups))) }

    /**
     * Some league stages show an overall table next to the group tables, and only the overall
     * one decides who goes through. When one table holds every team of the others, it's that one.
     */
    fun rankingTables(groups: List<GroupTable>): List<GroupTable> {
        if (groups.size < 2) return groups
        val overall = groups.maxBy { it.rows.size }
        val all = overall.rows.map { teamKey(it.team.name) }.toSet()
        val others = groups.filter { it !== overall }.flatMap { g -> g.rows.map { teamKey(it.team.name) } }
        return if (others.all { it in all }) listOf(overall) else groups
    }

    private fun placesByTeam(stages: List<EventStage>): Map<String, String> =
        stages.flatMap { it.detail.prizes }.mapNotNull { p -> p.team?.let { teamKey(it.name) to p.place } }.toMap()

    /** Every stage's outputs in a finished event, as "stage:output" -> team. */
    private fun outputs(stages: List<EventStage>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        stages.forEachIndexed { i, st ->
            when {
                st.detail.groups.isNotEmpty() && st.slug.contains("swiss", true) ->
                    st.detail.groups.first().rows.forEachIndexed { r, row -> out["$i:s:${r + 1}"] = teamKey(row.team.name) }
                st.detail.groups.isNotEmpty() ->
                    st.detail.groups.forEachIndexed { g, t -> t.rows.forEachIndexed { r, row -> out["$i:g$g:${r + 1}"] = teamKey(row.team.name) } }
                else -> bracketSlots(st.detail).filter { it.single }.forEach { s ->
                    s.match.team1.team.name.takeIf { it.isNotBlank() && it != "TBD" }?.let { out["$i:${s.key}"] = teamKey(it) }
                }
            }
        }
        return out
    }

    /** Reads a finished event's format. Null if any bracket can't be traced. */
    fun learn(allStages: List<EventStage>): EventFormat? {
        val stages = realStages(allStages)
        if (stages.isEmpty()) return null
        val places = placesByTeam(stages)
        val brackets = HashMap<Int, BracketTemplate>()
        val advancing = HashMap<Int, Int>()
        val swiss = HashMap<Int, Pair<Int, Int>>()
        val seeding = HashMap<String, String>()
        val outs = outputs(stages)
        stages.forEachIndexed { i, st ->
            when {
                st.detail.groups.isNotEmpty() && st.slug.contains("swiss", true) -> {
                    val rows = st.detail.groups.first().rows
                    val adv = rows.filter { it.advanced }
                    fun w(r: GroupRow) = r.record.substringBefore('–').substringBefore('-').trim().toIntOrNull() ?: 0
                    fun l(r: GroupRow) = r.record.substringAfter('–', r.record.substringAfter('-', "0")).trim().toIntOrNull() ?: 0
                    if (adv.isEmpty()) return null
                    swiss[i] = adv.minOf(::w) to rows.filterNot { it.advanced }.minOfOrNull(::l).let { it ?: 2 }
                }
                st.detail.groups.isNotEmpty() -> {
                    val perGroup = st.detail.groups.map { g -> g.rows.count { it.advanced } }.filter { it > 0 }
                    if (perGroup.isNotEmpty()) advancing[i] = perGroup.min()
                }
                else -> {
                    val t = Templates.learn(st.detail, places) ?: return null
                    brackets[i] = t
                    // Seed slots filled by an earlier stage's output.
                    val slots = bracketSlots(st.detail).associateBy { it.key }
                    for ((key, feeds) in t.feeds) feeds.forEachIndexed { pos, f ->
                        if (f != Feed.Seed) return@forEachIndexed
                        val m = slots[key]?.match ?: return@forEachIndexed
                        val team = listOfNotNull(m.team1, m.team2).getOrNull(pos)?.team?.name?.let(::teamKey) ?: return@forEachIndexed
                        val source = outs.entries.lastOrNull { (k, v) -> v == team && k.substringBefore(':').toInt() < i }?.key
                        if (source != null) seeding["$i:$key:$pos"] = source
                    }
                }
            }
        }
        return EventFormat(formatKey(stages), brackets, seeding, advancing, swiss, 1)
    }

    /** Combines finished examples of one format, keeping only what they all agree on. */
    fun merge(examples: List<EventFormat>): EventFormat? {
        if (examples.isEmpty()) return null
        val first = examples.first()
        val brackets = first.brackets.keys.associateWith { i -> Templates.merge(examples.mapNotNull { it.brackets[i] }) }
        if (brackets.values.any { it == null }) return null
        val seeding = first.seeding.filter { (k, v) -> examples.all { it.seeding[k] == v } }
        val advancing = first.advancing.filter { (k, v) -> examples.all { it.advancing[k] == v } }
        val swiss = first.swiss.filter { (k, v) -> examples.all { it.swiss[k] == v } }
        return EventFormat(first.key, brackets.mapValues { it.value!! }, seeding, advancing, swiss, examples.size)
    }

    /**
     * Builds the simulation for an event. With [asOfStart] every result and every team that
     * only got there by playing is hidden, as if forecasting before the first match (backtests).
     */
    fun plan(
        allStages: List<EventStage>,
        matches: List<MatchDay>,
        format: EventFormat,
        asOfStart: Boolean = false,
    ): List<StagePlan>? {
        val stages = realStages(allStages)
        if (formatKey(stages) != format.key) return null
        val earlierTeams = HashSet<String>()
        val bracketMatchIds = stages.flatMap { bracketSlots(it.detail) }.mapNotNull { it.match.matchId }.toSet()
        val plans = ArrayList<StagePlan>()
        stages.forEachIndexed { i, st ->
            val label = st.label
            val plan: StagePlan = when {
                st.detail.groups.isNotEmpty() -> {
                    val members = st.detail.groups.flatMap { g -> g.rows.map { teamKey(it.team.name) } }.toSet()
                    val stageMatches = matches.flatMap { it.matches }.filter { m ->
                        m.id !in bracketMatchIds && teamKey(m.team1.team.name) in members && teamKey(m.team2.team.name) in members
                    }
                    val remaining = stageMatches.filter { asOfStart || it.status != MatchStatus.COMPLETED }
                        .map { teamKey(it.team1.team.name) to teamKey(it.team2.team.name) }
                    val groups = if (asOfStart) st.detail.groups.map { g -> g.copy(rows = g.rows.map { it.copy(record = "0–0", maps = "0/0") }) } else st.detail.groups
                    if (st.slug.contains("swiss", true)) {
                        val (w, l) = format.swiss[i] ?: return null
                        StagePlan.Swiss(label, groups.first(), w, l, if (asOfStart) emptyList() else remaining)
                    } else {
                        StagePlan.RoundRobin(label, groups, remaining, format.advancing[i])
                    }
                }
                else -> {
                    val t = format.brackets[i] ?: return null
                    val detail = if (asOfStart) hideResults(st.detail, t, earlierTeams) else st.detail
                    StagePlan.Bracket(label, detail, t)
                }
            }
            earlierTeams += teamsIn(st)
            plans += plan
        }
        return plans
    }

    private fun teamsIn(st: EventStage): Set<String> =
        (st.detail.groups.flatMap { g -> g.rows.map { teamKey(it.team.name) } } +
            bracketSlots(st.detail).flatMap { s -> listOfNotNull(s.match.team1.team.name, s.match.team2?.team?.name) }
                .filter { it.isNotBlank() && it != "TBD" }.map(::teamKey)).toSet()

    /**
     * Strips results, and every team not known before play: only seed slots keep their team,
     * and not even those if the team got there by playing an earlier stage.
     */
    private fun hideResults(d: EventDetail, t: BracketTemplate, earned: Set<String>): EventDetail {
        val tbd = BracketTeam(Team("TBD"), "", isWinner = false, isLoser = false)
        val slots = bracketSlots(d).associateBy { it.key }
        fun keep(key: String, pos: Int, bt: BracketTeam): BracketTeam {
            val seed = t.feeds[key]?.getOrNull(pos) == Feed.Seed && slots[key]?.single == false
            return if (seed && teamKey(bt.team.name) !in earned) BracketTeam(bt.team, "", false, false) else tbd
        }
        return d.copy(sections = d.sections.mapIndexed { si, s ->
            s.copy(brackets = s.brackets.mapIndexed { bi, b ->
                b.copy(columns = b.columns.mapIndexed { ci, col ->
                    col.copy(matches = col.matches.mapIndexed { ri, m ->
                        val key = "$si.$bi.$ci.$ri"
                        BracketMatch(m.matchId, keep(key, 0, m.team1), m.team2?.let { keep(key, 1, it) }, m.startsAt)
                    })
                })
            })
        })
    }
}
