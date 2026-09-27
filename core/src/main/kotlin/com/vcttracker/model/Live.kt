package com.vcttracker.model

import com.vcttracker.data.MatchDetail
import com.vcttracker.data.Side
import kotlin.math.max

/**
 * Exact chance of winning a map from any round score, when team 1 wins a round on attack
 * with [pAttack] and on defense with [pDefense]. Halves swap after round 12; overtime
 * starts at 12–12 and swaps sides every round until someone leads by two.
 */
class InMap(
    private val pAttack: Double,
    private val pDefense: Double,
    /** Whether team 1 attacked in the first half; null when unknown (sides then don't matter). */
    private val team1AttacksFirst: Boolean?,
) {
    private val memo = HashMap<Long, Double>()

    private fun roundProb(k: Int): Double {
        if (team1AttacksFirst == null) return (pAttack + pDefense) / 2
        val attacking = when {
            k < 12 -> team1AttacksFirst
            k < 24 -> !team1AttacksFirst
            else -> if ((k - 24) % 2 == 0) team1AttacksFirst else !team1AttacksFirst
        }
        return if (attacking) pAttack else pDefense
    }

    fun winFrom(a: Int, b: Int): Double {
        if (a >= 13 && a - b >= 2) return 1.0
        if (b >= 13 && b - a >= 2) return 0.0
        val k = a + b
        // From a tie in overtime each pair of rounds has one attack and one defense round:
        // take both and win, drop both and lose, split and go again.
        if (k >= 24 && a == b && k % 2 == 0) {
            val win = pAttack * pDefense
            val loss = (1 - pAttack) * (1 - pDefense)
            return if (win + loss == 0.0) 0.5 else win / (win + loss)
        }
        val key = (a.toLong() shl 32) or b.toLong()
        memo[key]?.let { return it }
        val p = roundProb(k)
        val v = p * winFrom(a + 1, b) + (1 - p) * winFrom(a, b + 1)
        memo[key] = v
        return v
    }
}

/** A map in a live series. Unplayed maps have 0–0 and no agents. */
data class LiveMap(
    val map: String,
    val rounds1: Int,
    val rounds2: Int,
    val finished: Boolean,
    val agents1: List<String> = emptyList(),
    val agents2: List<String> = emptyList(),
    val players1: List<String> = emptyList(),
    val players2: List<String> = emptyList(),
    val team1AttacksFirst: Boolean? = null,
) {
    val started: Boolean get() = rounds1 + rounds2 > 0 || agents1.isNotEmpty()
    val winner: Int? get() = if (!finished) null else if (rounds1 > rounds2) 1 else 2
}

/** Everything known about a series in progress (or about to start, once the veto is out). */
data class LiveState(val bestOf: Int, val maps: List<LiveMap>) {
    val wins1: Int get() = maps.count { it.winner == 1 }
    val wins2: Int get() = maps.count { it.winner == 2 }
    val current: LiveMap? get() = maps.firstOrNull { !it.finished && it.started }

    companion object {
        fun isFinished(r1: Int, r2: Int) = (r1 >= 13 || r2 >= 13) && kotlin.math.abs(r1 - r2) >= 2

        /** Reads the maps, scores, sides and agents off a match page. */
        fun from(d: MatchDetail, bestOf: Int): LiveState? {
            val maps = d.games.filter { !it.map.equals("TBD", true) && it.map.isNotBlank() }.map { g ->
                val r1 = g.score1.toIntOrNull() ?: 0
                val r2 = g.score2.toIntOrNull() ?: 0
                val first = g.rounds.firstOrNull()
                LiveMap(
                    map = g.map,
                    rounds1 = r1,
                    rounds2 = r2,
                    finished = isFinished(r1, r2),
                    agents1 = g.players1.map { it.agents.firstOrNull()?.lowercase().orEmpty() }.takeIf { a -> a.any { it.isNotBlank() } }.orEmpty(),
                    agents2 = g.players2.map { it.agents.firstOrNull()?.lowercase().orEmpty() }.takeIf { a -> a.any { it.isNotBlank() } }.orEmpty(),
                    players1 = g.players1.mapNotNull { it.id },
                    players2 = g.players2.mapNotNull { it.id },
                    team1AttacksFirst = first?.side?.let { side ->
                        val winnerAttacked = side == Side.ATTACK
                        if (first.winner == 1) winnerAttacked else !winnerAttacked
                    },
                )
            }
            return if (maps.isEmpty()) null else LiveState(bestOf, maps)
        }
    }
}

/** A live forecast, with what changed it. */
data class LiveForecast(
    val team1Wins: Double,
    /** The pre-match number, for comparison. */
    val preMatch: Double,
    /** Team 1's chance on each known map: finished maps are 0 or 1; the live map is from its score. */
    val maps: List<MapOdds>,
    /** Team 1's chance on the live map from 0–0 with the same agents, to show what the score changed. */
    val currentMapAtStart: Double?,
    /** How much the locked agents moved team 1's chance on the live map, in percentage points. */
    val agentShift: Double?,
)

/**
 * Updates a forecast with what is already known: the maps (once the veto is out), maps
 * already won, the live map's score and sides, and each player's agent.
 */
fun Predictor.predictLive(team1: String, team2: String, state: LiveState, day: Long, preMatch: Double): LiveForecast {
    val a = RatingEngine.Side(team1)
    val b = RatingEngine.Side(team2)
    val (edgeMean, edgeVar) = engine.edge(a, b, null, day)

    // Per map: the offset its map (and, if locked, its agents) add on top of the shared roster edge.
    fun sides(m: LiveMap, withAgents: Boolean) = RatingEngine.Side(team1, m.players1, agents = if (withAgents) m.agents1 else emptyList()) to
        RatingEngine.Side(team2, m.players2, agents = if (withAgents) m.agents2 else emptyList())

    fun delta(m: LiveMap, withAgents: Boolean): Double {
        val (sa, sb) = sides(m, withAgents)
        return engine.edge(sa, sb, m.map, day).first - edgeMean
    }

    val deltas = state.maps.map { delta(it, withAgents = true) }
    val deltasNoAgents = state.maps.map { delta(it, withAgents = false) }
    val sideBias = state.maps.map { attackBias(it.map) }

    fun mapProb(i: Int, z: Double, fromScore: Boolean, useAgents: Boolean = true): Double {
        val m = state.maps[i]
        if (m.finished) return if (m.winner == 1) 1.0 else 0.0
        val zz = (z + if (useAgents) deltas[i] else deltasNoAgents[i]) * hyper.mapTemperature
        val bias = sideBias[i]
        val inMap = InMap(SeriesMath.sigmoid(zz + bias), SeriesMath.sigmoid(zz - bias), m.team1AttacksFirst)
        return if (fromScore) inMap.winFrom(m.rounds1, m.rounds2) else inMap.winFrom(0, 0)
    }

    fun seriesFrom(mapProbs: List<Double>): Double {
        // Maps beyond the known ones (veto not out yet) play like an average map.
        val probs = mapProbs + List(max(0, state.bestOf - mapProbs.size)) { mapProbs.lastOrNull() ?: 0.5 }
        return SeriesMath.seriesWin(probs, state.bestOf)
    }

    val series = Quadrature.expectImpl(edgeMean, edgeVar) { z ->
        seriesFrom(state.maps.indices.map { mapProb(it, z, fromScore = true) })
    }
    val perMap = state.maps.indices.map { i ->
        Quadrature.expectImpl(edgeMean, edgeVar) { z -> mapProb(i, z, fromScore = true) }
    }
    val current = state.maps.indexOfFirst { !it.finished && it.started }
    val atStart = if (current >= 0) Quadrature.expectImpl(edgeMean, edgeVar) { z -> mapProb(current, z, fromScore = false) } else null
    val agentsModelled = hyper.agentSd > 0 || hyper.comfortSd > 0
    val agentShift = if (agentsModelled && current >= 0 && state.maps[current].agents1.isNotEmpty()) {
        val without = Quadrature.expectImpl(edgeMean, edgeVar) { z -> mapProb(current, z, fromScore = false, useAgents = false) }
        (atStart!! - without) * 100
    } else null

    return LiveForecast(
        team1Wins = series.coerceIn(0.001, 0.999),
        preMatch = preMatch,
        maps = state.maps.mapIndexed { i, m -> MapOdds(m.map, 1.0, perMap[i]) },
        currentMapAtStart = atStart,
        agentShift = agentShift,
    )
}

/**
 * Team 1's chance to win one map from a given round score, with rosters, agents (if
 * [useAgents] and known) and sides taken from [m].
 */
fun Predictor.mapChance(team1: String, team2: String, m: LiveMap, day: Long, useAgents: Boolean, r1: Int = 0, r2: Int = 0): Double {
    val sa = RatingEngine.Side(team1, m.players1, agents = if (useAgents) m.agents1 else emptyList())
    val sb = RatingEngine.Side(team2, m.players2, agents = if (useAgents) m.agents2 else emptyList())
    val (mean, variance) = engine.edge(sa, sb, m.map, day)
    val bias = attackBias(m.map)
    return Quadrature.expectImpl(mean, variance) { z ->
        val zz = z * hyper.mapTemperature
        InMap(SeriesMath.sigmoid(zz + bias), SeriesMath.sigmoid(zz - bias), m.team1AttacksFirst).winFrom(r1, r2)
    }
}

/** A finished map from the dataset, as the live model sees it at agent select. */
fun MapRecord.asLive(finished: Boolean = false): LiveMap = LiveMap(
    map = map,
    rounds1 = if (finished) rounds1 else 0,
    rounds2 = if (finished) rounds2 else 0,
    finished = finished,
    agents1 = agents1, agents2 = agents2, players1 = players1, players2 = players2,
    team1AttacksFirst = team1Attacked(0),
)
