package com.vcttracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquipediaParserTest {

    @Test
    fun historyGroupsEventsByYearWithWinners() {
        val body = javaClass.classLoader!!.getResource("liquipedia_vct.json")!!.readText()
        val years = LiquipediaParser.parseHistory(body)
        assertEquals("2026", years.first().year)
        assertTrue(years.any { it.year == "2021" })

        val y2026 = years.first { it.year == "2026" }
        val champs = y2026.events.first { it.name == "VALORANT Champions 2026" }
        assertTrue(champs.isInternational)
        assertEquals("$2,250,000", champs.prize)
        assertEquals("Shanghai", champs.location)

        val pacific = y2026.events.first { it.name == "VCT 2026: Pacific Stage 2" }
        assertEquals("Global Esports", pacific.winner)
        assertEquals("Nongshim RedForce", pacific.runnerUp)
        assertEquals("Seoul / Busan", pacific.location)

        val champions2025 = years.first { it.year == "2025" }.events.first { it.name.startsWith("VALORANT Champions") }
        assertTrue(champions2025.winner != "TBD")
    }
}
