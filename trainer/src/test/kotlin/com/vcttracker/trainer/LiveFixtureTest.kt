package com.vcttracker.trainer

import com.vcttracker.data.MatchStatus
import com.vcttracker.data.VlrParser
import com.vcttracker.model.LiveState
import com.vcttracker.model.ModelIO
import com.vcttracker.model.liveForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A real page captured mid-match: 100 Thieves vs T1, map 1 (Summit) in progress. */
class LiveFixtureTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun readsTheLiveStateOffTheMatchPage() {
        val d = VlrParser.parseMatch(fixture("match_live.html"), "753444")
        assertEquals(MatchStatus.LIVE, d.status)
        assertEquals("Bo3", d.format)
        val state = LiveState.from(d, 3)!!
        assertEquals(listOf("Summit", "Ascent", "Sunset"), state.maps.map { it.map })
        val live = state.current!!
        assertEquals("Summit", live.map)
        assertTrue(live.rounds1 + live.rounds2 > 0)
        assertEquals(5, live.agents1.size)
        assertEquals(5, live.agents2.size)
        assertNotNull(live.team1AttacksFirst)
        println("state: $state")

        val model = ModelIO.read(File("../app/src/main/assets/model.json").readText())
        val f = model.liveForecast(d)!!
        println("pre-match %.3f -> live %.3f; maps %s; map at start %.3f; agents %s".format(
            f.preMatch, f.team1Wins, f.maps.map { "${it.map}=%.3f".format(it.team1Wins) }, f.currentMapAtStart, f.agentShift))
        assertTrue(f.team1Wins in 0.0..1.0)
    }
}
