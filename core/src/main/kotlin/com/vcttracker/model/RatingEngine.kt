package com.vcttracker.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Tunable settings of the rating filter. Defaults are overwritten by the tuned values. */
data class Hyper(
    /** Rounds within a map are correlated (economy, momentum); each counts as this fraction of an independent trial. */
    val roundWeight: Double = 0.35,
    /** Prior spread of a new player's skill, in round-logit units. */
    val playerSd: Double = 0.35,
    /** Where players we've never seen start (newly promoted rosters are usually weaker). */
    val newcomerMean: Double = -0.10,
    /** Daily skill drift, so old results count for less. */
    val driftPerDay: Double = 0.006,
    /** Extra uncertainty at the start of each season (rosters, meta and patches change). */
    val seasonSd: Double = 0.10,
    /** Prior spread of a team's map-specific offset. */
    val mapSd: Double = 0.15,
    val mapDriftPerDay: Double = 0.004,
    /** Prior spread of region strength offsets; only cross-region matches inform them. */
    val regionSd: Double = 0.20,
    val regionDriftPerDay: Double = 0.002,
    /** Shrinks the round-level edge before turning it into a map probability (maps are streakier than coin flips). */
    val mapTemperature: Double = 0.9,
    /** Prior spread of an agent's strength on a given map (the meta). 0 leaves agents out. */
    val agentSd: Double = 0.0,
    /** Prior spread of a player's skill on one agent relative to their baseline (comfort picks). 0 leaves it out. */
    val comfortSd: Double = 0.0,
    val agentDriftPerDay: Double = 0.003,
)

/** A Gaussian belief about one parameter. */
class Belief(var mean: Double, var variance: Double, var lastDay: Long)

/**
 * Online Bayesian ratings (assumed-density filtering with a Laplace step), observing
 * each map's round score as a down-weighted binomial of
 *   z = mean(team1 players) - mean(team2 players) + map offsets + region offsets.
 */
class RatingEngine(val hyper: Hyper) {
    val players = HashMap<String, Belief>()
    val teamMaps = HashMap<String, Belief>()
    val agentMaps = HashMap<String, Belief>()
    val playerAgents = HashMap<String, Belief>()
    val regions = HashMap<String, Belief>()
    val teamRegion = HashMap<String, String>()
    val rosters = HashMap<String, List<String>>()
    private var season = -1

    private class Term(val belief: Belief, val weight: Double, val driftVar: Double)

    private fun player(id: String, day: Long) =
        players.getOrPut(id) { Belief(hyper.newcomerMean, hyper.playerSd * hyper.playerSd, day) }

    private fun teamMap(team: String, map: String, day: Long) =
        teamMaps.getOrPut("$team|$map") { Belief(0.0, hyper.mapSd * hyper.mapSd, day) }

    private fun region(r: String, day: Long) =
        regions.getOrPut(r) { Belief(0.0, hyper.regionSd * hyper.regionSd, day) }

    /** Brings a belief up to [day]: skill wanders while a team isn't observed. */
    private fun age(b: Belief, day: Long, drift: Double) {
        val gap = max(0L, day - b.lastDay)
        if (gap > 0) {
            b.variance = min(b.variance + drift * drift * gap, 1.0)
            b.lastDay = day
        }
    }

    val currentSeason: Int get() = season

    /** Restores the season marker from a saved model without re-inflating uncertainty. */
    fun resumeSeason(year: Int) {
        season = year
    }

    fun startSeason(year: Int) {
        if (year == season) return
        if (season != -1) {
            val add = hyper.seasonSd * hyper.seasonSd
            players.values.forEach { it.variance += add }
            teamMaps.values.forEach { it.variance += add / 2 }
        }
        season = year
    }

    private fun terms(side: Side, sign: Double, map: String?, day: Long): List<Term> {
        val out = ArrayList<Term>()
        val ids = side.players.ifEmpty { rosters[side.team].orEmpty() }
        if (ids.isNotEmpty()) {
            val w = sign / ids.size
            ids.forEach { out += Term(player(it, day), w, hyper.driftPerDay) }
        }
        if (map != null) out += Term(teamMap(side.team, map, day), sign, hyper.mapDriftPerDay)
        // Agents are only known once agent select is over; before that these terms are left out.
        if (side.agents.isNotEmpty() && side.agents.size == side.players.size) {
            val w = sign / side.agents.size
            side.agents.forEachIndexed { i, agent ->
                if (agent.isBlank()) return@forEachIndexed
                if (map != null && hyper.agentSd > 0) {
                    out += Term(agentMaps.getOrPut("$agent|$map") { Belief(0.0, hyper.agentSd * hyper.agentSd, day) }, w, hyper.agentDriftPerDay)
                }
                if (hyper.comfortSd > 0) {
                    out += Term(playerAgents.getOrPut("${side.players[i]}|$agent") { Belief(0.0, hyper.comfortSd * hyper.comfortSd, day) }, w, hyper.agentDriftPerDay)
                }
            }
        }
        return out
    }

    /**
     * One side of a map: the org (for map habits and region), the five who played, and,
     * once agent select is over, the agent each of them locked (same order as [players]).
     */
    data class Side(
        val team: String,
        val players: List<String> = emptyList(),
        val region: String? = null,
        val agents: List<String> = emptyList(),
    )

    private fun allTerms(a: Side, b: Side, map: String?, day: Long): List<Term> {
        val t = terms(a, 1.0, map, day) + terms(b, -1.0, map, day)
        val ra = a.region ?: teamRegion[a.team]
        val rb = b.region ?: teamRegion[b.team]
        return if (ra != null && rb != null && ra != rb) {
            t + Term(region(ra, day), 1.0, hyper.regionDriftPerDay) + Term(region(rb, day), -1.0, hyper.regionDriftPerDay)
        } else t
    }

    /** Mean and variance of the round-logit edge of [a] over [b] on [map] (null = map-agnostic). */
    fun edge(a: Side, b: Side, map: String?, day: Long): Pair<Double, Double> {
        val t = allTerms(a, b, map, day)
        var mean = 0.0
        var variance = 0.0
        for (x in t) {
            val gap = max(0L, day - x.belief.lastDay)
            mean += x.weight * x.belief.mean
            variance += x.weight * x.weight * min(x.belief.variance + x.driftVar * x.driftVar * gap, 1.0)
        }
        return mean to variance
    }

    /** Probability [a] wins [map], integrating over our uncertainty about the edge. */
    fun mapWinProb(a: Side, b: Side, map: String?, day: Long): Double {
        val (m, v) = edge(a, b, map, day)
        return Quadrature.expect(m, v) { z -> SeriesMath.mapWin(SeriesMath.sigmoid(z * hyper.mapTemperature)) }
    }

    /** Feeds one played map: [rounds1] of the rounds went to [a]. */
    fun observe(a: Side, b: Side, map: String, rounds1: Int, rounds2: Int, day: Long) {
        val n = rounds1 + rounds2
        if (n == 0) return
        val t = allTerms(a, b, map, day)
        for (x in t) age(x.belief, day, x.driftVar)
        var mean = 0.0
        var s2 = 0.0
        for (x in t) {
            mean += x.weight * x.belief.mean
            s2 += x.weight * x.weight * x.belief.variance
        }
        val p = SeriesMath.sigmoid(mean)
        val grad = hyper.roundWeight * (rounds1 - n * p)
        val hess = hyper.roundWeight * n * p * (1 - p)
        val denom = 1.0 + hess * s2
        for (x in t) {
            val v = x.belief.variance
            x.belief.mean += v * x.weight * grad / denom
            x.belief.variance = max(v - v * v * x.weight * x.weight * hess / denom, 1e-5)
        }
        if (a.players.isNotEmpty()) rosters[a.team] = a.players
        if (b.players.isNotEmpty()) rosters[b.team] = b.players
    }

    fun teamStrength(team: String): Pair<Double, Double> {
        val ids = rosters[team].orEmpty()
        if (ids.isEmpty()) return hyper.newcomerMean to hyper.playerSd * hyper.playerSd
        val m = ids.sumOf { players[it]?.mean ?: hyper.newcomerMean } / ids.size
        val v = ids.sumOf { players[it]?.variance ?: (hyper.playerSd * hyper.playerSd) } / (ids.size * ids.size)
        return m to sqrt(v)
    }
}

/** Gauss–Hermite expectation of f(z) for z ~ N(mean, variance). */
object Quadrature {
    private val nodes = doubleArrayOf(-2.3506049736745, -1.3358490740137, -0.4360774119276, 0.4360774119276, 1.3358490740137, 2.3506049736745)
    private val weights = doubleArrayOf(0.0045300099055, 0.1570673203229, 0.7246295952244, 0.7246295952244, 0.1570673203229, 0.0045300099055)
    private const val SQRT_PI = 1.7724538509055159

    inline fun expect(mean: Double, variance: Double, crossinline f: (Double) -> Double): Double =
        expectImpl(mean, variance) { f(it) }

    fun expectImpl(mean: Double, variance: Double, f: (Double) -> Double): Double {
        if (variance <= 1e-12) return f(mean)
        val s = sqrt(2 * variance)
        var acc = 0.0
        for (i in nodes.indices) acc += weights[i] * f(mean + s * nodes[i])
        return acc / SQRT_PI
    }
}
