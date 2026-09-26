package com.vcttracker.trainer

import com.vcttracker.data.Region
import com.vcttracker.data.Stage
import com.vcttracker.data.VlrParser
import com.vcttracker.model.MatchRecord
import com.vcttracker.model.VetoRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DatasetTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun recordResolvesVetoTeamsAndKeepsPlayedMapsOnly() {
        val detail = VlrParser.parseMatch(fixture("match.html"), "753450")
        val rec = Dataset.record(detail, "Valorant Champions 2026", Stage.CHAMPIONS, Region.INTERNATIONAL)!!
        assertEquals(3, rec.bestOf)
        assertEquals(1, rec.winner)
        assertEquals(listOf("Lotus", "Summit"), rec.maps.map { it.map })
        assertEquals(13 to 8, rec.maps[0].rounds1 to rec.maps[0].rounds2)
        assertEquals(5, rec.maps[0].players1.size)
        // EDG picked Lotus (team 2), LOUD picked Summit (team 1).
        assertEquals(VetoRecord(2, "ban", "Ascent"), rec.veto[0])
        assertEquals(VetoRecord(1, "ban", "Split"), rec.veto[1])
        assertEquals(VetoRecord(0, "remains", "Sunset"), rec.veto.last())
        assertEquals(listOf(1.40 to null, 1.41 to null), rec.odds)
        assertEquals(rec, MatchRecord.fromJson(JSONObject(rec.toJson().toString())))
    }
}
