package com.vcttracker.trainer

import com.vcttracker.model.Baseline
import com.vcttracker.model.Hyper
import com.vcttracker.model.MatchRecord
import com.vcttracker.model.ModelReport
import com.vcttracker.model.LiveMap
import com.vcttracker.model.LiveRow
import com.vcttracker.model.LiveState
import com.vcttracker.model.Predictor
import com.vcttracker.model.asLive
import com.vcttracker.model.mapChance
import com.vcttracker.model.predictLive
import com.vcttracker.model.SeriesMath
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/** One forecast made before the match, and what actually happened. */
data class Forecast(
    val match: MatchRecord,
    /** The model's pre-match probability that team 1 wins. */
    val final: Double,
    val elo: Double,
    /** Bookmaker probability for team 1, when vlr.gg kept the odds. */
    val odds: Double?,
    /** Map-level: (team-1 win probability given the actual map, did team 1 win). */
    val maps: List<Pair<Double, Boolean>>,
    val live: LiveChecks? = null,
) {
    val team1Won: Boolean get() = match.winner == 1
}

/** The same match forecast again at each point a live viewer would see new information. */
data class LiveChecks(
    /** Series chance once the veto is known (maps in order, before any is played). */
    val postVeto: Double?,
    /** Series chance after map 1 is over. */
    val afterMap1: Double?,
    /** Per played map at 0–0: (without agents, with agents, team 1 won). */
    val mapStart: List<Triple<Double, Double, Boolean>>,
    /** Mid-map: (rounds played so far, chance, team 1 won the map). */
    val inMap: List<Triple<Int, Double, Boolean>>,
)

/** Classic series-level team Elo, the usual esports baseline. */
class TeamElo(private val k: Double = 32.0) {
    private val r = HashMap<String, Double>()
    fun prob(a: String, b: String) = 1.0 / (1.0 + 10.0.pow(((r[b] ?: 1500.0) - (r[a] ?: 1500.0)) / 400.0))
    fun update(a: String, b: String, aWon: Boolean) {
        val p = prob(a, b)
        val d = k * ((if (aWon) 1.0 else 0.0) - p)
        r[a] = (r[a] ?: 1500.0) + d
        r[b] = (r[b] ?: 1500.0) - d
    }
}

object Backtest {

    /** Bookmakers' average overround on vlr.gg (measured: 1/1.42 + 1/2.74 ≈ 1.069). */
    private const val OVERROUND = 1.068

    /** Converts what vlr kept of the odds into team 1's implied probability. */
    fun impliedTeam1(odds: List<Pair<Double?, Double?>>): Double? {
        val ps = odds.mapNotNull { (o1, o2) ->
            when {
                o1 != null && o2 != null -> (1 / o1) / (1 / o1 + 1 / o2)
                o1 != null -> ((1 / o1) / OVERROUND).coerceAtMost(0.99)
                o2 != null -> 1 - ((1 / o2) / OVERROUND).coerceAtMost(0.99)
                else -> null
            }
        }
        return if (ps.isEmpty()) null else ps.average()
    }

    /**
     * Walks every match in order: forecast first (using only the past), then learn from it.
     * Matches before [from] only train the state.
     */
    fun run(
        data: List<MatchRecord>,
        hyper: Hyper,
        from: Instant,
        vetoRuns: Int,
        until: Instant = Instant.MAX,
        onPredictor: (Predictor) -> Unit = {},
        withLive: Boolean = false,
    ): List<Forecast> {
        val predictor = Predictor(hyper)
        val elo = TeamElo()
        val out = ArrayList<Forecast>()
        for (m in data) {
            if (m.time >= until) break
            val day = predictor.day(m)
            if (m.time >= from && m.maps.isNotEmpty()) {
                predictor.engine.startSeason(m.time.atZone(ZoneOffset.UTC).year)
                val p = predictor.predict(m.team1Id, m.team2Id, m.bestOf, day, vetoRuns)
                val maps = m.maps.map { mp ->
                    val a = com.vcttracker.model.RatingEngine.Side(m.team1Id, mp.players1)
                    val b = com.vcttracker.model.RatingEngine.Side(m.team2Id, mp.players2)
                    predictor.engine.mapWinProb(a, b, mp.map, day) to (mp.rounds1 > mp.rounds2)
                }
                val live = if (withLive) liveChecks(predictor, m, day, p.team1Wins) else null
                out += Forecast(m, p.team1Wins, elo.prob(m.team1Id, m.team2Id), impliedTeam1(m.odds), maps, live)
            }
            predictor.observe(m)
            elo.update(m.team1Id, m.team2Id, m.winner == 1)
        }
        onPredictor(predictor)
        return out
    }

    /** Maps in playing order as fixed by the veto: those played, then any pick or decider left over. */
    private fun vetoOrder(m: MatchRecord): List<String> {
        val played = m.maps.map { it.map }
        val rest = m.veto.filter { it.action == "pick" || it.action == "remains" }.map { it.map }.filter { it !in played }
        return (played + rest).take(m.bestOf)
    }

    private fun liveChecks(p: Predictor, m: MatchRecord, day: Long, preMatch: Double): LiveChecks {
        val order = vetoOrder(m)
        val byName = m.maps.associateBy { it.map }
        fun unplayed(name: String) = byName[name]?.asLive() ?: LiveMap(name, 0, 0, finished = false)
        val postVeto = if (order.size == m.bestOf) {
            p.predictLive(m.team1Id, m.team2Id, LiveState(m.bestOf, order.map { unplayed(it) }.map { it.copy(agents1 = emptyList(), agents2 = emptyList()) }), day, preMatch).team1Wins
        } else null
        val afterMap1 = if (order.size == m.bestOf && m.maps.size >= 2 && m.maps[0].rounds1 + m.maps[0].rounds2 > 0) {
            val maps = order.mapIndexed { i, name ->
                if (i == 0) m.maps[0].asLive(finished = true) else unplayed(name).copy(agents1 = emptyList(), agents2 = emptyList())
            }
            p.predictLive(m.team1Id, m.team2Id, LiveState(m.bestOf, maps), day, preMatch).team1Wins
        } else null
        val starts = m.maps.filter { it.agents1.size == 5 && it.agents2.size == 5 }.map { mp ->
            val lm = mp.asLive()
            Triple(p.mapChance(m.team1Id, m.team2Id, lm, day, useAgents = false), p.mapChance(m.team1Id, m.team2Id, lm, day, useAgents = true), mp.rounds1 > mp.rounds2)
        }
        val inMap = ArrayList<Triple<Int, Double, Boolean>>()
        for (mp in m.maps) {
            val total = mp.rounds.length / 2
            for (k in listOf(6, 12, 18)) {
                if (total <= k) continue
                val r1 = (0 until k).count { mp.rounds[it * 2] == '1' }
                val chance = p.mapChance(m.team1Id, m.team2Id, mp.asLive(), day, useAgents = true, r1 = r1, r2 = k - r1)
                inMap += Triple(k, chance, mp.rounds1 > mp.rounds2)
            }
        }
        return LiveChecks(postVeto, afterMap1, starts, inMap)
    }

    /** Random search over the agent terms only, scored by map log-loss at agent select (pre-test maps). */
    fun tuneAgents(data: List<MatchRecord>, base: Hyper, from: Instant, until: Instant, trials: Int, log: (String) -> Unit): Hyper {
        fun loss(h: Hyper): Double {
            val f = run(data, h, from, vetoRuns = 10, until = until, withLive = true)
            val maps = f.flatMap { it.live?.mapStart.orEmpty() }
            return score(maps.map { it.second }, maps.map { it.third }).logLoss
        }
        val rng = Random(7)
        val off = base.copy(agentSd = 0.0, comfortSd = 0.0)
        var best = off
        var bestLoss = loss(off)
        log("agents off: map logloss=%.4f".format(bestLoss))
        repeat(trials) { t ->
            val local = t >= trials / 2 && (best.agentSd > 0 || best.comfortSd > 0)
            fun pick(cur: Double, lo: Double, hi: Double) =
                if (local) (cur * (0.7 + rng.nextDouble() * 0.6)).coerceIn(lo, hi) else lo + rng.nextDouble() * (hi - lo)
            val h = base.copy(
                agentSd = if (rng.nextDouble() < 0.15) 0.0 else pick(best.agentSd.coerceAtLeast(0.02), 0.0, 0.5),
                comfortSd = if (rng.nextDouble() < 0.15) 0.0 else pick(best.comfortSd.coerceAtLeast(0.02), 0.0, 0.5),
                agentDriftPerDay = pick(best.agentDriftPerDay, 0.0005, 0.02),
            )
            val l = loss(h)
            if (l < bestLoss) {
                bestLoss = l
                best = h
                log("trial ${t + 1}: map logloss=%.4f agentSd=%.3f comfortSd=%.3f drift=%.4f".format(l, h.agentSd, h.comfortSd, h.agentDriftPerDay))
            }
        }
        return best
    }

    // ------------------------------------------------------------------ metrics

    data class Score(val n: Int, val accuracy: Double, val logLoss: Double, val brier: Double)

    fun score(ps: List<Double>, ys: List<Boolean>): Score {
        if (ps.isEmpty()) return Score(0, Double.NaN, Double.NaN, Double.NaN)
        var correct = 0.0
        var ll = 0.0
        var br = 0.0
        for (i in ps.indices) {
            val p = ps[i].coerceIn(1e-6, 1 - 1e-6)
            val y = ys[i]
            correct += when {
                abs(p - 0.5) < 1e-9 -> 0.5
                (p > 0.5) == y -> 1.0
                else -> 0.0
            }
            ll -= if (y) ln(p) else ln(1 - p)
            br += (p - (if (y) 1.0 else 0.0)).pow(2)
        }
        return Score(ps.size, correct / ps.size, ll / ps.size, br / ps.size)
    }

    /** The live checkpoints, in the order a viewer meets them. */
    fun liveRows(test: List<Forecast>): List<LiveRow> {
        val rows = ArrayList<LiveRow>()
        fun row(name: String, ps: List<Double>, ys: List<Boolean>) {
            if (ps.isEmpty()) return
            val s = score(ps, ys)
            rows += LiveRow(name, s.n, s.accuracy, s.logLoss)
        }
        val withVeto = test.filter { it.live?.postVeto != null }
        row("Series, before the veto", withVeto.map { it.final }, withVeto.map { it.team1Won })
        row("Series, maps known", withVeto.map { it.live!!.postVeto!! }, withVeto.map { it.team1Won })
        val after1 = test.filter { it.live?.afterMap1 != null }
        row("Series, after map 1", after1.map { it.live!!.afterMap1!! }, after1.map { it.team1Won })
        val starts = test.flatMap { it.live?.mapStart.orEmpty() }
        row("Map at 0–0, without agents", starts.map { it.first }, starts.map { it.third })
        row("Map at 0–0, with agents", starts.map { it.second }, starts.map { it.third })
        val inMap = test.flatMap { it.live?.inMap.orEmpty() }
        for (k in listOf(6, 12, 18)) {
            val at = inMap.filter { it.first == k }
            row("Map after $k rounds", at.map { it.second }, at.map { it.third })
        }
        return rows
    }

    fun calibration(ps: List<Double>, ys: List<Boolean>): List<Triple<Double, Double, Int>> {
        // Fold to the favourite's side so buckets run 50–100%.
        val fav = ps.indices.map { i -> if (ps[i] >= 0.5) ps[i] to ys[i] else 1 - ps[i] to !ys[i] }
        return (5 until 10).mapNotNull { b ->
            val lo = b / 10.0
            val bucket = fav.filter { it.first >= lo && it.first < lo + 0.1 || (b == 9 && it.first >= 1.0) }
            if (bucket.isEmpty()) null
            else Triple(bucket.map { it.first }.average(), bucket.count { it.second }.toDouble() / bucket.size, bucket.size)
        }
    }

    // ------------------------------------------------------------------ tuning

    /** Random search over the filter settings, scored by raw series log-loss on the tuning window. */
    fun tune(
        data: List<MatchRecord>, from: Instant, until: Instant, trials: Int, log: (String) -> Unit,
        start: Hyper = Hyper(),
    ): Hyper {
        val rng = Random(2024)
        var best = start
        var bestLoss = evaluate(data, best, from, until)
        log("default: logloss=%.4f".format(bestLoss))
        repeat(trials) { t ->
            // Half the trials explore widely, half refine around the current best.
            val local = t >= trials / 2
            fun pick(cur: Double, lo: Double, hi: Double): Double =
                if (local) (cur * (0.75 + rng.nextDouble() * 0.5)).coerceIn(lo, hi) else lo + rng.nextDouble() * (hi - lo)
            val h = Hyper(
                roundWeight = pick(best.roundWeight, 0.1, 2.0),
                playerSd = pick(best.playerSd, 0.15, 1.0),
                newcomerMean = pick(best.newcomerMean, -0.3, 0.0),
                driftPerDay = pick(best.driftPerDay, 0.001, 0.02),
                seasonSd = pick(best.seasonSd, 0.0, 0.3),
                mapSd = pick(best.mapSd, 0.03, 0.6),
                mapDriftPerDay = pick(best.mapDriftPerDay, 0.0005, 0.03),
                regionSd = pick(best.regionSd, 0.05, 0.4),
                regionDriftPerDay = pick(best.regionDriftPerDay, 0.0002, 0.015),
                mapTemperature = pick(best.mapTemperature, 0.2, 1.2),
            )
            val loss = evaluate(data, h, from, until)
            if (loss < bestLoss) {
                bestLoss = loss
                best = h
                log("trial ${t + 1}: logloss=%.4f  %s".format(loss, h))
            }
        }
        return best
    }

    private fun evaluate(data: List<MatchRecord>, h: Hyper, from: Instant, until: Instant): Double {
        val f = run(data, h, from, vetoRuns = 40, until = until)
        return score(f.map { it.final }, f.map { it.team1Won }).logLoss
    }

    fun report(test: List<Forecast>, trainedOn: Int, testFrom: String): ModelReport {
        val ys = test.map { it.team1Won }
        val model = score(test.map { it.final }, ys)
        val baselines = ArrayList<Baseline>()
        fun add(name: String, subset: List<Forecast>, prob: (Forecast) -> Double) {
            val s = score(subset.map(prob), subset.map { it.team1Won })
            val m = score(subset.map { it.final }, subset.map { it.team1Won })
            baselines += Baseline(name, s.n, s.accuracy, s.logLoss, m.accuracy, m.logLoss)
        }
        add("Coin flip", test) { 0.5 }
        add("Team Elo", test) { it.elo }
        val withOdds = test.filter { it.odds != null }
        if (withOdds.isNotEmpty()) add("Bookmaker odds", withOdds) { it.odds!! }
        return ModelReport(
            live = liveRows(test),
            trainedOn = trainedOn, testFrom = testFrom, testMatches = model.n,
            accuracy = model.accuracy, logLoss = model.logLoss, brier = model.brier,
            baselines = baselines,
            calibration = calibration(test.map { it.final }, ys),
        )
    }
}
