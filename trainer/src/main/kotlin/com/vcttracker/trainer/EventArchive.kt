package com.vcttracker.trainer

import com.vcttracker.data.EventDetail
import com.vcttracker.data.MatchDay
import com.vcttracker.data.MatchStatus
import com.vcttracker.data.Season
import com.vcttracker.data.VLR
import com.vcttracker.data.VlrParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Raw vlr.gg event pages (season lists, every stage page, the full match list), gzipped,
 * so tournament formats can be learned and backtested with whatever the parser does today.
 */
class EventArchive(private val dir: File) {

    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val agent = "VCTTracker-trainer/1.0 (https://github.com/Suyyash-aroraa/VCT-Valo-tracker)"

    private fun fetch(url: String): String {
        repeat(4) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", agent).build()).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code} for $url")
                    return res.body!!.string()
                }
            } catch (e: IOException) {
                if (attempt == 3) throw e
                Thread.sleep(2000L shl attempt)
            }
        }
        error("unreachable")
    }

    private fun file(name: String) = File(dir, "$name.html.gz")

    private fun save(name: String, html: String) {
        file(name).parentFile.mkdirs()
        GZIPOutputStream(file(name).outputStream()).bufferedWriter().use { it.write(html) }
    }

    fun read(name: String): String? = file(name).takeIf { it.exists() }?.let { f ->
        GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() }
    }

    fun season(year: Int): Season? = read("season-$year")?.let { VlrParser.parseSeason(it, year) }

    /** Every stage page of an event (playoffs, group stage, …), keyed by the stage's path slug. */
    fun stages(eventId: String): List<Pair<String, EventDetail>> {
        val base = read("$eventId/main")?.let { VlrParser.parseEvent(it, eventId) } ?: return emptyList()
        if (base.subPages.isEmpty()) return listOf("main" to base)
        return base.subPages.mapNotNull { sp ->
            val slug = sp.path.trimEnd('/').substringAfterLast('/')
            read("$eventId/$slug")?.let { slug to VlrParser.parseEvent(it, eventId) }
        }
    }

    fun matches(eventId: String): List<MatchDay> = read("$eventId/matches")?.let { VlrParser.parseMatchList(it) }.orEmpty()

    /**
     * Downloads what's missing. Finished events are fetched once; events that aren't over
     * (and the current season list) are refreshed every run.
     */
    fun update(fromYear: Int = 2023, delayMs: Long = 700) {
        for (year in fromYear..LocalDate.now().year) {
            val seasonHtml = fetch("$VLR/vct-$year")
            save("season-$year", seasonHtml)
            val season = VlrParser.parseSeason(seasonHtml, year)
            for (e in season.events) {
                val done = e.status == MatchStatus.COMPLETED
                if (done && file("${e.id}/matches").exists()) continue
                Thread.sleep(delayMs)
                val main = fetch("$VLR/event/${e.id}")
                save("${e.id}/main", main)
                val detail = VlrParser.parseEvent(main, e.id)
                for (sp in detail.subPages) {
                    val slug = sp.path.trimEnd('/').substringAfterLast('/')
                    Thread.sleep(delayMs)
                    save("${e.id}/$slug", fetch(VLR + sp.path))
                }
                Thread.sleep(delayMs)
                save("${e.id}/matches", fetch("$VLR/event/matches/${e.id}/?series_id=all"))
                println("$year ${e.name}: ${detail.subPages.size} stage pages")
            }
        }
    }
}
