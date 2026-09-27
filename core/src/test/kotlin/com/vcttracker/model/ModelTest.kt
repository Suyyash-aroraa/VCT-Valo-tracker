package com.vcttracker.model

import com.vcttracker.data.Region
import com.vcttracker.data.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class SeriesMathTest {

    @Test
    fun evenRoundsGiveEvenMaps() {
        assertEquals(0.5, SeriesMath.mapWin(0.5), 1e-12)
        for (p in listOf(0.3, 0.45, 0.55, 0.7)) {
            assertEquals(1.0, SeriesMath.mapWin(p) + SeriesMath.mapWin(1 - p), 1e-12)
        }
    }

    @Test
    fun mapWinsAmplifyRoundEdges() {
        // A 55% round edge is worth far more than 55% over a first-to-13 race.
        val m = SeriesMath.mapWin(0.55)
        assertTrue(m > 0.65 && m < 0.72)
    }

    @Test
    fun mapWinMatchesSimulation() {
        val rng = java.util.Random(1)
        val p = 0.54
        var wins = 0
        val n = 200_000
        repeat(n) {
            var a = 0
            var b = 0
            while (true) {
                if (rng.nextDouble() < p) a++ else b++
                if (a >= 13 && a - b >= 2) { wins++; break }
                if (b >= 13 && b - a >= 2) break
            }
        }
        assertEquals(wins.toDouble() / n, SeriesMath.mapWin(p), 0.004)
    }

    @Test
    fun seriesMatchesClosedForms() {
        for (p in listOf(0.3, 0.5, 0.62)) {
            assertEquals(p, SeriesMath.seriesWin(listOf(p), 1), 1e-12)
            assertEquals(p * p * (3 - 2 * p), SeriesMath.seriesWin(List(3) { p }, 3), 1e-12)
            assertEquals(p * p * p * (10 - 15 * p + 6 * p * p), SeriesMath.seriesWin(List(5) { p }, 5), 1e-12)
        }
    }

    @Test
    fun seriesUsesMapsInOrder() {
        // Win map 1 for sure, lose map 2 for sure: the decider decides.
        assertEquals(0.4, SeriesMath.seriesWin(listOf(1.0, 0.0, 0.4), 3), 1e-12)
    }

    @Test
    fun quadratureMatchesKnownMoments() {
        assertEquals(1.0, Quadrature.expectImpl(1.0, 0.25) { it }, 1e-9)
        assertEquals(1.25, Quadrature.expectImpl(1.0, 0.25) { it * it }, 1e-9)
    }
}

class PredictorTest {

    private fun match(id: Int, day: Long, t1: String, t2: String, r1: Int, r2: Int, bo: Int = 1) = MatchRecord(
        id = "$id", time = Instant.ofEpochSecond(day * 86_400), eventId = "1", eventName = "Test",
        stage = Stage.STAGE_1, region = Region.EMEA, series = "",
        team1Id = t1, team1 = t1.uppercase(), team2Id = t2, team2 = t2.uppercase(),
        score1 = if (r1 > r2) 1 else 0, score2 = if (r1 > r2) 0 else 1, bestOf = bo,
        veto = emptyList(),
        maps = listOf(MapRecord("Ascent", 0, r1, r2, (1..5).map { "$t1$it" }, (1..5).map { "$t2$it" })),
        odds = emptyList(),
    )

    @Test
    fun repeatedWinsMakeAFavourite() {
        val p = Predictor(Hyper())
        var day = 20_000L
        repeat(10) { i -> p.observe(match(i, day++, "a", "b", 13, 7)) }
        val f = p.predict("a", "b", 3, day, vetoRuns = 50)
        assertTrue("expected a clear favourite, got ${f.team1Wins}", f.team1Wins > 0.7)
        val flipped = p.predict("b", "a", 3, day, vetoRuns = 50)
        assertEquals(1.0, f.team1Wins + flipped.team1Wins, 0.02)
    }

    @Test
    fun rosterSkillFollowsPlayersToANewOrg() {
        val p = Predictor(Hyper())
        var day = 20_000L
        repeat(10) { i -> p.observe(match(i, day++, "a", "b", 13, 5)) }
        // Team a's five players move to a brand new org "c".
        val moved = match(99, day++, "c", "d", 13, 11).let { m ->
            m.copy(maps = listOf(m.maps[0].copy(players1 = (1..5).map { "a$it" })))
        }
        p.observe(moved)
        val f = p.predict("c", "b", 3, day, vetoRuns = 50)
        assertTrue("new org should inherit the players' strength, got ${f.team1Wins}", f.team1Wins > 0.75)
    }

    @Test
    fun modelSurvivesARoundTrip() {
        val p = Predictor(Hyper())
        var day = 20_000L
        repeat(6) { i -> p.observe(match(i, day++, "a", "b", 13, 9)) }
        val before = p.predict("a", "b", 3, day, vetoRuns = 50)
        val restored = ModelIO.read(ModelIO.write(TrainedModel(p, Instant.EPOCH, null))).predictor
        val after = restored.predict("a", "b", 3, day, vetoRuns = 50)
        assertTrue(abs(before.team1Wins - after.team1Wins) < 1e-3)
    }
}

class VetoModelTest {
    @Test
    fun formatsProduceTheRightNumberOfMaps() {
        val v = VetoModel()
        listOf("Ascent", "Bind", "Haven", "Lotus", "Split", "Sunset", "Icebox").forEach { v.seeMap(it, 100) }
        assertEquals(1, v.simulate("a", "b", 1, 100, 20).first().size)
        assertEquals(3, v.simulate("a", "b", 3, 100, 20).first().size)
        assertEquals(5, v.simulate("a", "b", 5, 100, 20).first().size)
        assertTrue(v.simulate("a", "b", 3, 100, 20).all { it.distinct().size == it.size })
    }

    @Test
    fun habitsShapeWhatGetsPlayed() {
        val v = VetoModel()
        val maps = listOf("Ascent", "Bind", "Haven", "Lotus", "Split", "Sunset", "Icebox")
        maps.forEach { v.seeMap(it, 100) }
        repeat(20) { v.observe("a", "pick", "Lotus", 100); v.observe("a", "ban", "Bind", 100) }
        val sims = v.simulate("a", "b", 3, 100, 400)
        val lotus = sims.count { "Lotus" in it }.toDouble() / sims.size
        val bind = sims.count { "Bind" in it }.toDouble() / sims.size
        assertTrue("Lotus $lotus should beat Bind $bind", lotus > 0.7 && bind < 0.3)
    }
}

class LiveTest {

    @Test
    fun fromZeroZeroMatchesTheWholeMapFormula() {
        for (p in listOf(0.4, 0.5, 0.57)) {
            assertEquals(SeriesMath.mapWin(p), InMap(p, p, null).winFrom(0, 0), 1e-12)
            // Knowing sides changes nothing when both sides are equally strong.
            assertEquals(SeriesMath.mapWin(p), InMap(p, p, true).winFrom(0, 0), 1e-12)
        }
    }

    @Test
    fun scoresAndOvertimeBehave() {
        val m = InMap(0.5, 0.5, true)
        assertEquals(1.0, m.winFrom(13, 5), 0.0)
        assertEquals(0.0, m.winFrom(11, 13), 0.0)
        assertEquals(0.5, m.winFrom(12, 12), 1e-12)
        assertEquals(0.75, m.winFrom(13, 12), 1e-12) // win the next round, or back to a coin-flip tie
        assertTrue(m.winFrom(10, 4) > 0.95)
        // Overtime with lopsided sides: take the attack round, hold the defense round.
        val sided = InMap(0.7, 0.4, true)
        assertEquals(0.28 / (0.28 + 0.18), sided.winFrom(14, 14), 1e-12)
    }

    @Test
    fun sidesMatterWhenOneSideIsStronger() {
        // Team 1 is great on attack, poor on defense; halves swap at 13.
        val attackFirst = InMap(0.7, 0.35, true).winFrom(0, 0)
        val defenseFirst = InMap(0.7, 0.35, false).winFrom(0, 0)
        assertEquals(attackFirst, defenseFirst, 0.02) // over a full map the halves even out
        // Up 6–6 at the half with the strong side still to come is better than after it.
        assertTrue(InMap(0.7, 0.35, false).winFrom(6, 6) > InMap(0.7, 0.35, true).winFrom(6, 6))
    }

    @Test
    fun seriesStateCountsMapsAlreadyWon() {
        val p = Predictor(Hyper())
        val state = LiveState(
            bestOf = 3,
            maps = listOf(
                LiveMap("Ascent", 13, 7, finished = true),
                LiveMap("Bind", 12, 3, finished = false),
                LiveMap("Haven", 0, 0, finished = false),
            ),
        )
        val f = p.predictLive("a", "b", state, 20_000, preMatch = 0.5)
        assertTrue("1–0 up and 12–3 on map two should be near-certain, got ${f.team1Wins}", f.team1Wins > 0.97)
        assertEquals(1.0, f.maps[0].team1Wins, 0.0)
    }
}
