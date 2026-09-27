package com.vcttracker

import com.vcttracker.data.VlrParser
import com.vcttracker.model.EventForecast
import com.vcttracker.model.ModelIO
import com.vcttracker.model.eventForecast
import com.vcttracker.model.stagesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shipped model forecasting Valorant Champions 2026 from saved vlr.gg pages. */
class EventForecastTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val model by lazy { ModelIO.read(File("src/main/assets/model.json").readText()) }

    fun champions(): EventForecast? {
        val groups = VlrParser.parseEvent(fixture("champs26_group-stage.html"), "2766")
        val playoffs = VlrParser.parseEvent(fixture("champs26_playoffs.html"), "2766")
        val stages = stagesOf(playoffs, mapOf("group-stage" to groups, "playoffs" to playoffs))
        return model.eventForecast(stages, VlrParser.parseMatchList(fixture("champs26_matches.html")), runs = 1000)
    }

    @Test
    fun shippedModelCarriesTheFormatBook() {
        assertTrue("event gate", model.gates.events)
        val book = assertNotNull(model.book).let { model.book!! }
        assertTrue(book.brackets.isNotEmpty())
        // Writing and reading the model keeps every learned bracket.
        val again = ModelIO.read(ModelIO.write(model))
        assertEquals(book.brackets.keys, again.book!!.brackets.keys)
        assertEquals(book.brackets.values.map { it.feeds }, again.book!!.brackets.values.map { it.feeds })
        assertEquals(book.swiss, again.book!!.swiss)
    }

    @Test
    fun championsForecastIsCoherent() {
        val f = assertNotNull(champions()).let { champions()!! }
        assertTrue(f.hasWinner)
        assertEquals(16, f.teams.size)
        // Exactly one champion per simulation.
        assertEquals(1.0, f.teams.sumOf { it.win }, 1e-9)
        // Two teams go through from each of the four groups.
        val stage = f.stages.first()
        assertEquals(8.0, f.teams.sumOf { it.advance[stage] ?: 0.0 }, 1e-9)
        // Nobody wins without getting out of the groups first.
        f.teams.forEach { assertTrue(it.win <= (it.advance[stage] ?: 0.0) + 1e-9) }
    }
}
