package com.vcttracker.data

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Reads the tournament tables on Liquipedia's "VALORANT Champions Tour" page. */
object LiquipediaParser {

    const val SOURCE_PAGE = "https://liquipedia.net/valorant/VALORANT_Champions_Tour"
    const val API_URL =
        "https://liquipedia.net/valorant/api.php?action=parse&page=VALORANT_Champions_Tour&format=json&prop=text&formatversion=2"

    /** Accepts the API's JSON (formatversion 1 or 2) or plain page HTML. */
    fun parseHistory(body: String): List<HistoryYear> = parseHistoryHtml(extractHtml(body))

    internal fun extractHtml(body: String): String {
        if (!body.trimStart().startsWith("{")) return body
        val text = JSONObject(body).getJSONObject("parse").get("text")
        return if (text is JSONObject) text.getString("*") else text.toString()
    }

    fun parseHistoryHtml(html: String): List<HistoryYear> {
        val doc = Jsoup.parse(html)
        val byYear = LinkedHashMap<String, LinkedHashMap<String, HistoricEvent>>()
        for (table in doc.select("div.tournaments-listing")) {
            for (row in table.select("tr")) {
                if (!row.className().contains("row--body")) continue
                val event = parseRow(row) ?: continue
                val year = Regex("20\\d\\d").findAll(event.dates).lastOrNull()?.value
                    ?: Regex("20\\d\\d").find(event.name)?.value
                    ?: continue
                byYear.getOrPut(year) { LinkedHashMap() }.putIfAbsent(event.name, event)
            }
        }
        return byYear.entries
            .sortedByDescending { it.key }
            .map { (year, events) -> HistoryYear(year, events.values.toList()) }
    }

    private fun parseRow(row: Element): HistoricEvent? {
        val name = row.selectFirst("td.column__tournament").txt().ifEmpty { return null }
        val cells = row.select("> td")
        val placements = row.select("td.column__placement")
        fun team(i: Int) = placements.getOrNull(i)?.selectFirst(".name").txt().ifEmpty { "TBD" }
        return HistoricEvent(
            name = name,
            dates = cells.getOrNull(2).txt(),
            prize = cells.getOrNull(3).txt(),
            location = cells.getOrNull(4)?.select("div > span")?.joinToString(" / ") { it.txt() }
                ?.ifBlank { null } ?: cells.getOrNull(4).txt(),
            participants = cells.getOrNull(5).txt(),
            winner = team(0),
            runnerUp = team(1),
            isInternational = isInternational(name),
        )
    }

    fun isInternational(name: String): Boolean {
        val n = name.lowercase()
        return "masters" in n || "lock//in" in n || "lock-in" in n ||
            ("champions" in n && "champions tour" !in n && "qualifier" !in n)
    }
}
