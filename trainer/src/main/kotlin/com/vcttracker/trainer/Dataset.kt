package com.vcttracker.trainer

import com.vcttracker.data.MatchDetail
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.Region
import com.vcttracker.data.Stage
import com.vcttracker.data.VLR
import com.vcttracker.data.VlrParser
import com.vcttracker.model.MapRecord
import com.vcttracker.model.MatchRecord
import com.vcttracker.model.VetoRecord
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object Dataset {

    fun load(file: File): List<MatchRecord> =
        if (!file.exists()) emptyList()
        else file.readLines().filter { it.isNotBlank() }.map { MatchRecord.fromJson(JSONObject(it)) }.sortedBy { it.time }

    /** Converts a parsed match page into a record, or null if it can't be used (TBD teams, no result). */
    fun record(detail: MatchDetail, eventName: String, stage: Stage, region: Region): MatchRecord? {
        val s1 = detail.score1?.toIntOrNull() ?: return null
        val s2 = detail.score2?.toIntOrNull() ?: return null
        if (s1 == s2 || detail.status != MatchStatus.COMPLETED) return null
        val id1 = detail.team1.id ?: return null
        val id2 = detail.team2.id ?: return null
        val time = detail.startsAt ?: return null
        val maps = detail.games.filter { it.played }.mapNotNull { g ->
            val r1 = g.score1.toIntOrNull() ?: return@mapNotNull null
            val r2 = g.score2.toIntOrNull() ?: return@mapNotNull null
            MapRecord(
                map = g.map, pickedBy = g.pickedBy, rounds1 = r1, rounds2 = r2,
                players1 = g.players1.mapNotNull { it.id }, players2 = g.players2.mapNotNull { it.id },
            )
        }
        val bestOf = detail.format?.drop(2)?.toIntOrNull() ?: (maxOf(s1, s2) * 2 - 1)
        return MatchRecord(
            id = detail.id, time = time, eventId = detail.eventId.orEmpty(), eventName = eventName,
            stage = stage, region = region, series = detail.series,
            team1Id = id1, team1 = detail.team1.name, team2Id = id2, team2 = detail.team2.name,
            score1 = s1, score2 = s2, bestOf = bestOf,
            veto = resolveVeto(detail, maps),
            maps = maps,
            odds = detail.odds.map { it.team1 to it.team2 },
        )
    }

    /**
     * Veto notes name teams by tag ("EDG ban Ascent"). Picks tell us which tag is which
     * team (the map page records who picked it); otherwise fall back to name initials.
     */
    fun resolveVeto(detail: MatchDetail, maps: List<MapRecord>): List<VetoRecord> {
        val tagToTeam = HashMap<String, Int>()
        for (v in detail.veto) {
            if (v.action != "pick") continue
            val picked = maps.firstOrNull { it.map.equals(v.map, true) }?.pickedBy ?: continue
            if (picked != 0) tagToTeam[v.team] = picked
        }
        val tags = detail.veto.map { it.team }.filter { it.isNotBlank() }.distinct()
        if (tagToTeam.size == 1 && tags.size == 2) {
            val known = tagToTeam.entries.first()
            tagToTeam[tags.first { it != known.key }] = 3 - known.value
        }
        for (t in tags) {
            if (t in tagToTeam) continue
            val guess = when {
                looksLike(t, detail.team1.name) && !looksLike(t, detail.team2.name) -> 1
                looksLike(t, detail.team2.name) && !looksLike(t, detail.team1.name) -> 2
                else -> null
            }
            if (guess != null && guess !in tagToTeam.values) tagToTeam[t] = guess
        }
        return detail.veto.map { v ->
            VetoRecord(if (v.action == "remains") 0 else tagToTeam[v.team] ?: -1, v.action, v.map)
        }
    }

    private fun looksLike(tag: String, name: String): Boolean {
        val t = tag.lowercase()
        val n = name.lowercase()
        val initials = n.split(' ', '-').filter { it.isNotBlank() }.joinToString("") { it.take(1) }
        return n.startsWith(t) || initials == t || n.replace(" ", "").contains(t)
    }

    // ------------------------------------------------------------------ scraping

    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private const val AGENT = "VCTTracker-trainer/1.0 (https://github.com/Suyyash-aroraa/VCT-Valo-tracker)"

    private fun get(url: String, stopAt: String? = null): String {
        repeat(4) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", AGENT).build()).execute().use { res ->
                    if (res.code == 429 || res.code >= 500) throw IOException("HTTP ${res.code}")
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code} for $url")
                    val body = res.body ?: throw IOException("empty")
                    if (stopAt == null) return body.string()
                    // Match pages are mostly comments; stop once the stats are behind us.
                    val sb = StringBuilder()
                    val reader = body.charStream().buffered()
                    val buf = CharArray(16 * 1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                        if (sb.indexOf(stopAt, maxOf(0, sb.length - n - stopAt.length)) >= 0) break
                    }
                    return sb.toString()
                }
            } catch (e: IOException) {
                if (attempt == 3) throw e
                Thread.sleep(2000L shl attempt)
            }
        }
        error("unreachable")
    }

    /**
     * Adds every completed VCT match since [fromYear] that isn't in [file] yet.
     * Appends as it goes, so an interrupted run resumes where it stopped.
     */
    fun update(file: File, fromYear: Int = 2023, delayMs: Long = 700) {
        val known = load(file).map { it.id }.toHashSet()
        val skipped = File(file.parentFile, "skipped.txt")
        val skip = if (skipped.exists()) skipped.readLines().toHashSet() else hashSetOf()
        file.parentFile.mkdirs()
        var added = 0
        for (year in fromYear..LocalDate.now().year) {
            val season = VlrParser.parseSeason(get("$VLR/vct-$year"), year)
            for (event in season.events) {
                if (event.status == MatchStatus.UPCOMING) continue
                Thread.sleep(delayMs)
                val days = VlrParser.parseMatchList(get("$VLR/event/matches/${event.id}/?series_id=all"))
                val todo = days.flatMap { it.matches }
                    .filter { it.status == MatchStatus.COMPLETED && it.id !in known && it.id !in skip }
                if (todo.isNotEmpty()) println("${event.name}: ${todo.size} new")
                for (m in todo) {
                    Thread.sleep(delayMs)
                    val html = try {
                        get("$VLR/${m.id}", stopAt = "match-h2h")
                    } catch (e: IOException) {
                        println("  ! ${m.id}: ${e.message}")
                        continue
                    }
                    val rec = record(VlrParser.parseMatch(html, m.id), event.name, event.stage, event.region)
                    if (rec == null) {
                        skipped.appendText(m.id + "\n")
                        continue
                    }
                    file.appendText(rec.toJson().toString() + "\n")
                    known += m.id
                    added++
                }
            }
        }
        println("Added $added matches; dataset now has ${known.size}.")
    }
}
