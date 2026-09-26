package com.vcttracker.model

import com.vcttracker.data.Region
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Everything the app shows about one forecast. */
data class Prediction(
    /** Probability that team 1 wins the series. */
    val team1Wins: Double,
    val bestOf: Int,
    /** For each map in the pool: chance it gets played, and team 1's chance to win it. */
    val maps: List<MapOdds>,
    val factors: Factors,
)

data class MapOdds(val map: String, val playedChance: Double, val team1Wins: Double)

/** The inputs behind a forecast, from team 1's point of view, in plain units. */
data class Factors(
    /** Roster strength edge in round-win percentage points on an average map. */
    val rosterEdge: Double,
    /** How much the likely maps move the needle, in series percentage points. */
    val mapPoolEdge: Double,
    /** 0 = rosters well known, 1 = mostly guesswork. */
    val uncertainty: Double,
    val regionEdge: Double,
)

/**
 * Walks through matches in time order, keeping ratings and map habits up to date,
 * and forecasts any matchup from the current state.
 *
 * There is deliberately no calibration layer on top: the backtest showed the ratings'
 * own probabilities are already well calibrated, and a logistic layer with form,
 * head-to-head and rest signals made forecasts slightly worse, not better.
 */
class Predictor(val hyper: Hyper) {
    val engine = RatingEngine(hyper)
    val veto = VetoModel()
    val names = HashMap<String, String>()

    fun day(r: MatchRecord) = r.time.epochSecond / 86_400

    /**
     * Forecast [team1] vs [team2]. Rosters come from each team's latest match unless given.
     * [vetoRuns] trades accuracy for speed when simulating which maps get played.
     */
    fun predict(
        team1: String,
        team2: String,
        bestOf: Int,
        day: Long,
        vetoRuns: Int = 300,
        roster1: List<String> = emptyList(),
        roster2: List<String> = emptyList(),
    ): Prediction {
        val a = RatingEngine.Side(team1, roster1)
        val b = RatingEngine.Side(team2, roster2)
        val (edgeMean, edgeVar) = engine.edge(a, b, null, day)
        val generic = engine.mapWinProb(a, b, null, day)

        // Each map adds its own offset (and its own uncertainty) on top of the shared roster edge.
        val pool = veto.pool(day)
        val mapDelta = HashMap<String, Pair<Double, Double>>()
        for (m in pool) {
            val (mean, variance) = engine.edge(a, b, m, day)
            mapDelta[m] = (mean - edgeMean) to max(variance - edgeVar, 0.0)
        }
        fun mapProb(m: String, shared: Double): Double {
            val (delta, v) = mapDelta[m] ?: (0.0 to 0.0)
            return Quadrature.expectImpl(shared + delta, v) { z -> SeriesMath.mapWin(SeriesMath.sigmoid(z * hyper.mapTemperature)) }
        }
        val mapWin = pool.associateWith { m -> Quadrature.expectImpl(edgeMean, edgeVar) { z -> mapProb(m, z) } }

        // Identical vetoes are common; weight each distinct map order by how often it came up.
        val sims = veto.simulate(team1, team2, bestOf, day, vetoRuns)
        val orders = sims.groupingBy { it }.eachCount()
        val series = if (orders.isEmpty()) {
            SeriesMath.seriesWin(List(bestOf) { generic }, bestOf)
        } else {
            // Maps share the same rosters, so their results move together: integrate the shared
            // roster edge outside the series calculation rather than per map.
            Quadrature.expectImpl(edgeMean, edgeVar) { z ->
                val perMap = HashMap<String, Double>()
                orders.entries.sumOf { (maps, count) ->
                    count * SeriesMath.seriesWin(maps.map { m -> perMap.getOrPut(m) { mapProb(m, z) } }, bestOf)
                } / sims.size
            }
        }
        val playedChance = HashMap<String, Double>()
        orders.forEach { (maps, count) -> maps.forEach { m -> playedChance.merge(m, count.toDouble() / sims.size, Double::plus) } }
        val genericSeries = SeriesMath.seriesWin(List(bestOf) { generic }, bestOf)

        val r1 = engine.teamRegion[team1]
        val r2 = engine.teamRegion[team2]
        val regionEdge = if (r1 != null && r2 != null && r1 != r2) {
            (engine.regions[r1]?.mean ?: 0.0) - (engine.regions[r2]?.mean ?: 0.0)
        } else 0.0

        return Prediction(
            team1Wins = series.coerceIn(0.01, 0.99),
            bestOf = bestOf,
            maps = pool.map { MapOdds(it, playedChance[it] ?: 0.0, mapWin.getValue(it)) }
                .sortedByDescending { it.playedChance },
            factors = Factors(
                rosterEdge = (SeriesMath.sigmoid(edgeMean) - 0.5) * 100,
                mapPoolEdge = (series - genericSeries) * 100,
                uncertainty = min(1.0, sqrt(edgeVar) / (hyper.playerSd * 0.9)),
                regionEdge = (SeriesMath.sigmoid(regionEdge) - 0.5) * 100,
            ),
        )
    }

    /** Learns from a finished match. Call strictly in time order. */
    fun observe(r: MatchRecord) {
        val day = day(r)
        engine.startSeason(r.time.atZone(java.time.ZoneOffset.UTC).year)
        names[r.team1Id] = r.team1
        names[r.team2Id] = r.team2
        if (r.region != Region.INTERNATIONAL) {
            engine.teamRegion[r.team1Id] = r.region.name
            engine.teamRegion[r.team2Id] = r.region.name
        }
        for (m in r.maps) {
            val a = RatingEngine.Side(r.team1Id, m.players1)
            val b = RatingEngine.Side(r.team2Id, m.players2)
            engine.observe(a, b, m.map, m.rounds1, m.rounds2, day)
            veto.seeMap(m.map, day)
        }
        for (v in r.veto) {
            val team = when (v.team) { 1 -> r.team1Id; 2 -> r.team2Id; else -> null }
            if (team != null) veto.observe(team, v.action, v.map, day) else veto.seeMap(v.map, day)
        }
    }
}
