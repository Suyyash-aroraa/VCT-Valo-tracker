package com.vcttracker.model

import kotlin.math.pow
import kotlin.random.Random

/**
 * Each team's map habits: recency-weighted counts of what they pick and ban.
 * Simulating the veto with these habits gives the likely maps of an upcoming series.
 */
class VetoModel(private val halfLifeDays: Double = 120.0, private val smoothing: Double = 0.5) {

    class Habits {
        val picks = HashMap<String, Double>()
        val bans = HashMap<String, Double>()
        var day = 0L
    }

    val teams = HashMap<String, Habits>()

    /** When each map was last seen in a VCT veto; the active pool is whatever showed up recently. */
    val lastSeen = HashMap<String, Long>()

    private fun decay(h: Habits, day: Long) {
        if (day <= h.day) return
        val f = 0.5.pow((day - h.day) / halfLifeDays)
        h.picks.replaceAll { _, v -> v * f }
        h.bans.replaceAll { _, v -> v * f }
        h.day = day
    }

    fun observe(team: String, action: String, map: String, day: Long) {
        lastSeen[map] = maxOf(lastSeen[map] ?: 0L, day)
        if (action != "pick" && action != "ban") return
        val h = teams.getOrPut(team) { Habits().also { it.day = day } }
        decay(h, day)
        val bucket = if (action == "pick") h.picks else h.bans
        bucket[map] = (bucket[map] ?: 0.0) + 1.0
    }

    fun seeMap(map: String, day: Long) {
        lastSeen[map] = maxOf(lastSeen[map] ?: 0L, day)
    }

    /** Maps seen in the last [windowDays]; VCT runs a seven-map pool. */
    fun pool(day: Long, windowDays: Long = 75): List<String> =
        lastSeen.filter { day - it.value <= windowDays }.keys.sorted()

    private fun weight(team: String, map: String, pick: Boolean, day: Long): Double {
        val h = teams[team] ?: return 1.0
        val f = 0.5.pow((day - h.day).coerceAtLeast(0) / halfLifeDays)
        val counts = if (pick) h.picks else h.bans
        return (counts[map] ?: 0.0) * f + smoothing
    }

    private fun choose(options: List<String>, team: String, pick: Boolean, day: Long, rng: Random): String {
        val w = options.map { weight(team, it, pick, day) }
        var r = rng.nextDouble() * w.sum()
        for (i in options.indices) {
            r -= w[i]
            if (r <= 0) return options[i]
        }
        return options.last()
    }

    /**
     * Simulates [runs] vetoes and returns the maps each one produced, in playing order.
     * Formats follow VCT: Bo1 bans down to one map; Bo3 is ban-ban-pick-pick-ban-ban-decider;
     * Bo5 is ban-ban-pick-pick-pick-pick-decider. Who starts is a coin flip.
     */
    fun simulate(team1: String, team2: String, bestOf: Int, day: Long, runs: Int, seed: Int = 7): List<List<String>> {
        val pool = pool(day)
        if (pool.size < bestOf) return emptyList()
        val rng = Random(seed)
        return List(runs) {
            val first = if (rng.nextBoolean()) team1 else team2
            val second = if (first == team1) team2 else team1
            val left = pool.toMutableList()
            val played = ArrayList<String>()
            val steps: List<Pair<String, Boolean>> = when (bestOf) {
                1 -> List(left.size - 1) { i -> (if (i % 2 == 0) first else second) to false }
                5 -> listOf(first to false, second to false, first to true, second to true, first to true, second to true)
                else -> listOf(first to false, second to false, first to true, second to true, first to false, second to false)
            }
            for ((team, pick) in steps) {
                if (left.size <= 1) break
                val m = choose(left, team, pick, day, rng)
                left.remove(m)
                if (pick) played += m
            }
            if (played.size < bestOf && left.isNotEmpty()) played += left.first()
            played.take(bestOf)
        }
    }
}
