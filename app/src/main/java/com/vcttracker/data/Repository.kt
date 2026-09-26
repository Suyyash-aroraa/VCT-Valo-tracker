package com.vcttracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TodayData(
    val season: Season?,
    val featured: EventDetail?,
    val upcoming: List<MatchDay>,
    val results: List<MatchDay>,
)

/** A value plus where it came from, so the UI can say when it is showing saved data. */
data class Loaded<T>(val value: T, val fetchedAt: Instant, val offline: Boolean)

class Repository(context: Context) {

    private val diskDir = File(context.cacheDir, "pages").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private data class Entry(val body: String, val at: Instant)

    private val memory = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    // Liquipedia asks API users for a descriptive agent and at most one parse request per 30s.
    private val liquipediaGate = Mutex()
    private var lastLiquipediaCall = 0L

    private suspend fun page(url: String, maxAgeSec: Long, force: Boolean): Loaded<String> =
        locks.getOrPut(url) { Mutex() }.withLock {
            val now = Instant.now()
            val cached = memory[url]
            if (!force && cached != null && cached.at.plusSeconds(maxAgeSec).isAfter(now)) {
                return@withLock Loaded(cached.body, cached.at, offline = false)
            }
            if (!force && cached == null) {
                val file = diskFile(url)
                val fresh = withContext(Dispatchers.IO) {
                    file.takeIf { it.exists() && it.lastModified() + maxAgeSec * 1000 > now.toEpochMilli() }?.readText()
                }
                if (fresh != null) {
                    val at = Instant.ofEpochMilli(file.lastModified())
                    memory[url] = Entry(fresh, at)
                    return@withLock Loaded(fresh, at, offline = false)
                }
            }
            try {
                val body = download(url)
                memory[url] = Entry(body, now)
                withContext(Dispatchers.IO) { diskFile(url).writeText(body) }
                Loaded(body, now, offline = false)
            } catch (e: IOException) {
                val file = diskFile(url)
                val saved = cached?.body ?: withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.readText() }
                    ?: throw e
                Loaded(saved, cached?.at ?: Instant.ofEpochMilli(file.lastModified()), offline = true)
            }
        }

    private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val isLiquipedia = url.contains("liquipedia.net")
        if (isLiquipedia) {
            liquipediaGate.withLock {
                val wait = lastLiquipediaCall + 30_000 - System.currentTimeMillis()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                lastLiquipediaCall = System.currentTimeMillis()
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (isLiquipedia) LIQUIPEDIA_AGENT else VLR_AGENT)
            .build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("${res.code} from ${request.url.host}")
            res.body?.string() ?: throw IOException("Empty response")
        }
    }

    private fun diskFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(diskDir, "$hash.html")
    }

    /** [parse] also receives when the page was fetched, which relative countdowns are measured from. */
    private suspend fun <T> load(url: String, maxAgeSec: Long, force: Boolean, parse: (String, Instant) -> T): Loaded<T> {
        val page = page(url, maxAgeSec, force)
        val value = withContext(Dispatchers.Default) { parse(page.value, page.fetchedAt) }
        return Loaded(value, page.fetchedAt, page.offline)
    }

    // ------------------------------------------------------------------ API

    suspend fun season(year: Int, force: Boolean = false) =
        load("$VLR/vct-$year", 30 * 60, force) { body, _ -> VlrParser.parseSeason(body, year) }

    /**
     * Everything the Today screen needs: the season (for the rail), the event that is
     * on right now, and VCT-only live/upcoming matches and results with team logos.
     */
    suspend fun today(force: Boolean = false): Loaded<TodayData> = coroutineScope {
        val year = LocalDate.now().year
        // The season list changes rarely; polling only needs fresh match lists.
        val seasonJob = async { runCatching { season(year) }.getOrNull() }
        val upcoming = async { load("$VLR/matches", 45, force) { body, at -> VlrParser.parseMatchList(body, at) } }
        val results = async { load("$VLR/matches/results", 120, force) { body, at -> VlrParser.parseMatchList(body, at) } }
        val up = upcoming.await()
        val res = results.await()
        var seasonLoaded = seasonJob.await()
        if (seasonLoaded != null && seasonLoaded.value.events.isEmpty()) {
            seasonLoaded = runCatching { season(year - 1) }.getOrNull() ?: seasonLoaded
        }
        val upVct = up.value.onlyVct()
        val resVct = res.value.onlyVct()

        // Pull logos (and the featured event) from the events that matter today.
        val names = (upVct + resVct.take(2)).flatMap { d -> d.matches.map { it.eventName } }.toSet()
        val relevant = seasonLoaded?.value?.events.orEmpty()
            .filter { it.status == MatchStatus.LIVE || it.name in names }
            .take(5)
        val details = relevant.map { e -> async { runCatching { event(e.id).value }.getOrNull() } }.map { it.await() }
        val logos = details.filterNotNull().fold(HashMap<String, String>()) { acc, d -> acc.apply { putAll(d.logoMap()) } }
        val featured = relevant.zip(details)
            .filter { (e, d) -> e.status == MatchStatus.LIVE && d != null }
            .minByOrNull { (e, _) -> if (e.region == Region.INTERNATIONAL) 0 else 1 }
            ?.second

        Loaded(
            value = TodayData(
                season = seasonLoaded?.value,
                featured = featured,
                upcoming = upVct.withLogos(logos),
                results = resVct.withLogos(logos),
            ),
            fetchedAt = minOf(up.fetchedAt, res.fetchedAt),
            offline = up.offline || res.offline,
        )
    }

    private fun List<MatchDay>.onlyVct() = mapNotNull { day ->
        day.matches.filter { VlrParser.isVctEvent(it.eventName) }
            .takeIf { it.isNotEmpty() }
            ?.let { day.copy(matches = it) }
    }

    suspend fun event(id: String, subPath: String? = null, force: Boolean = false) =
        load(VLR + (subPath ?: "/event/$id"), 90, force) { body, _ -> VlrParser.parseEvent(body, id) }

    suspend fun eventMatches(id: String, force: Boolean = false): Loaded<List<MatchDay>> {
        val list = load("$VLR/event/matches/$id/?series_id=all", 60, force) { body, at -> VlrParser.parseMatchList(body, at) }
        val logos = runCatching { event(id).value.logoMap() }.getOrDefault(emptyMap())
        return list.copy(value = list.value.withLogos(logos))
    }

    suspend fun match(id: String, force: Boolean = false) =
        load("$VLR/$id", 30, force) { body, _ -> VlrParser.parseMatch(body, id) }

    suspend fun standings(year: Int, force: Boolean = false) =
        load("$VLR/vct-$year/standings", 30 * 60, force) { body, _ -> VlrParser.parseStandings(body) }

    suspend fun team(id: String, force: Boolean = false) =
        load("$VLR/team/$id", 10 * 60, force) { body, _ -> VlrParser.parseTeam(body, id) }

    suspend fun player(id: String, force: Boolean = false) =
        load("$VLR/player/$id", 30 * 60, force) { body, _ -> VlrParser.parsePlayer(body, id) }

    suspend fun history(force: Boolean = false) =
        load(LiquipediaParser.API_URL, 12 * 60 * 60, force) { body, _ -> LiquipediaParser.parseHistory(body) }

    companion object {
        private const val VLR_AGENT = "Mozilla/5.0 (Linux; Android 14) VCTTracker/1.0"
        private const val LIQUIPEDIA_AGENT =
            "VCTTracker/1.0 (https://github.com/suyyash-aroraa/vct-valo-tracker; Android app)"
    }
}
