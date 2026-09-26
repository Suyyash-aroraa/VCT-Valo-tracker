package com.vcttracker.model

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Exact probability bookkeeping from single rounds up to a whole series. */
object SeriesMath {

    fun sigmoid(x: Double): Double = if (x >= 0) 1.0 / (1.0 + exp(-x)) else exp(x).let { it / (1.0 + it) }

    fun logit(p: Double): Double {
        val q = p.coerceIn(1e-9, 1 - 1e-9)
        return ln(q / (1 - q))
    }

    /** MacKay's probit approximation: the mean of sigmoid(z) when z ~ N(mean, variance). */
    fun expectedSigmoid(mean: Double, variance: Double): Double =
        sigmoid(mean / sqrt(1.0 + Math.PI * variance / 8.0))

    private val binom = Array(26) { n -> DoubleArray(n + 1) }.also { t ->
        for (n in 0..25) {
            t[n][0] = 1.0
            for (k in 1..n) t[n][k] = t[n - 1].getOrElse(k - 1) { 0.0 } + t[n - 1].getOrElse(k) { 0.0 }
        }
    }

    /**
     * P(win a map) when each round is won with probability [p]: first to 13, and from 12–12
     * overtime is decided by winning two rounds in a row relative to the opponent.
     */
    fun mapWin(p: Double): Double {
        val q = 1 - p
        var win = 0.0
        // 13–k for k = 0..11: the last round is ours, so choose which k of the first 12+k we lost.
        for (k in 0..11) win += binom[12 + k][k] * Math.pow(p, 13.0) * Math.pow(q, k.toDouble())
        val tied = binom[24][12] * Math.pow(p, 12.0) * Math.pow(q, 12.0)
        val overtime = p * p / (p * p + q * q)
        return win + tied * overtime
    }

    /**
     * P(team 1 wins a best-of-[bestOf]) when the maps, in playing order, are won with
     * [mapProbs]. Maps can differ, so this walks the series state by state.
     */
    fun seriesWin(mapProbs: List<Double>, bestOf: Int): Double {
        val need = bestOf / 2 + 1
        // dist[a][b] = probability the series is at a–b and still going.
        var dist = mapOf((0 to 0) to 1.0)
        var won = 0.0
        for (p in mapProbs) {
            val next = HashMap<Pair<Int, Int>, Double>()
            for ((score, pr) in dist) {
                val (a, b) = score
                val win = a + 1 to b
                val loss = a to b + 1
                if (win.first == need) won += pr * p else next.merge(win, pr * p, Double::plus)
                if (loss.second != need) next.merge(loss, pr * (1 - p), Double::plus)
            }
            dist = next
            if (dist.isEmpty()) break
        }
        return won
    }
}
