package com.vcttracker.model

import com.vcttracker.data.Region
import com.vcttracker.data.Stage
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One map as it was actually played. Team indices are 1 and 2, as on the match page. */
data class MapRecord(
    val map: String,
    val pickedBy: Int,
    val rounds1: Int,
    val rounds2: Int,
    val players1: List<String>,
    val players2: List<String>,
    /** Agent each player locked, in the same order as [players1] / [players2]. */
    val agents1: List<String> = emptyList(),
    val agents2: List<String> = emptyList(),
    /**
     * Every round in order, two characters each: the winning team ('1' or '2') and the side
     * it won on ('a' attack, 'd' defense, '?' unknown). "2a1d" = team 2 won round 1 on attack, …
     */
    val rounds: String = "",
) {
    /** Team 1's side in round [i] (0-based), inferred from who won it and on which side. */
    fun team1Attacked(i: Int): Boolean? {
        if (rounds.length < i * 2 + 2) return null
        val winner = rounds[i * 2]
        return when (rounds[i * 2 + 1]) {
            'a' -> winner == '1'
            'd' -> winner != '1'
            else -> null
        }
    }
}

/** A veto step with the team resolved to 1 or 2 (0 for the leftover decider). */
data class VetoRecord(val team: Int, val action: String, val map: String)

data class MatchRecord(
    val id: String,
    val time: Instant,
    val eventId: String,
    val eventName: String,
    val stage: Stage,
    val region: Region,
    val series: String,
    val team1Id: String,
    val team1: String,
    val team2Id: String,
    val team2: String,
    val score1: Int,
    val score2: Int,
    val bestOf: Int,
    val veto: List<VetoRecord>,
    val maps: List<MapRecord>,
    /** Decimal odds per bookmaker; after the match only the winner's side survives. */
    val odds: List<Pair<Double?, Double?>>,
) {
    val winner: Int get() = if (score1 > score2) 1 else 2

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", time.epochSecond).put("ev", eventId).put("evn", eventName)
        .put("st", stage.name).put("rg", region.name).put("se", series)
        .put("t1", JSONArray(listOf(team1Id, team1))).put("t2", JSONArray(listOf(team2Id, team2)))
        .put("s", JSONArray(listOf(score1, score2))).put("bo", bestOf)
        .put("veto", JSONArray(veto.map { JSONArray(listOf(it.team, it.action, it.map)) }))
        .put("maps", JSONArray(maps.map {
            JSONObject().put("m", it.map).put("p", it.pickedBy).put("r", JSONArray(listOf(it.rounds1, it.rounds2)))
                .put("p1", JSONArray(it.players1)).put("p2", JSONArray(it.players2))
                .also { o ->
                    if (it.agents1.isNotEmpty()) o.put("a1", JSONArray(it.agents1)).put("a2", JSONArray(it.agents2))
                    if (it.rounds.isNotEmpty()) o.put("rs", it.rounds)
                }
        }))
        .put("odds", JSONArray(odds.map { JSONArray(listOf(it.first ?: JSONObject.NULL, it.second ?: JSONObject.NULL)) }))

    companion object {
        fun fromJson(o: JSONObject): MatchRecord {
            fun strs(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            val t1 = o.getJSONArray("t1")
            val t2 = o.getJSONArray("t2")
            val s = o.getJSONArray("s")
            val veto = o.getJSONArray("veto")
            val maps = o.getJSONArray("maps")
            val odds = o.getJSONArray("odds")
            return MatchRecord(
                id = o.getString("id"),
                time = Instant.ofEpochSecond(o.getLong("t")),
                eventId = o.getString("ev"),
                eventName = o.getString("evn"),
                stage = Stage.valueOf(o.getString("st")),
                region = Region.valueOf(o.getString("rg")),
                series = o.getString("se"),
                team1Id = t1.getString(0), team1 = t1.getString(1),
                team2Id = t2.getString(0), team2 = t2.getString(1),
                score1 = s.getInt(0), score2 = s.getInt(1),
                bestOf = o.getInt("bo"),
                veto = (0 until veto.length()).map { i ->
                    val v = veto.getJSONArray(i); VetoRecord(v.getInt(0), v.getString(1), v.getString(2))
                },
                maps = (0 until maps.length()).map { i ->
                    val m = maps.getJSONObject(i)
                    val r = m.getJSONArray("r")
                    MapRecord(
                        m.getString("m"), m.getInt("p"), r.getInt(0), r.getInt(1),
                        strs(m.getJSONArray("p1")), strs(m.getJSONArray("p2")),
                        agents1 = m.optJSONArray("a1")?.let(::strs).orEmpty(),
                        agents2 = m.optJSONArray("a2")?.let(::strs).orEmpty(),
                        rounds = m.optString("rs", ""),
                    )
                },
                odds = (0 until odds.length()).map { i ->
                    val a = odds.getJSONArray(i)
                    (if (a.isNull(0)) null else a.getDouble(0)) to (if (a.isNull(1)) null else a.getDouble(1))
                },
            )
        }
    }
}
