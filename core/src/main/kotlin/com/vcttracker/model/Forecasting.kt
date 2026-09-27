package com.vcttracker.model

import com.vcttracker.data.MatchDetail
import com.vcttracker.data.MatchSummary
import com.vcttracker.data.Region
import java.time.Instant

private fun today(): Long = Instant.now().epochSecond / 86_400

private fun isInternational(eventName: String) = Region.fromName(eventName) == Region.INTERNATIONAL

/**
 * Match lists don't say the format. VCT plays Bo3 almost everywhere; grand finals, and
 * lower finals at internationals, are Bo5.
 */
fun guessBestOf(series: String, eventName: String): Int {
    val s = series.lowercase()
    return when {
        "grand final" in s || s.endsWith("gf") -> 5
        "lower final" in s && isInternational(eventName) -> 5
        else -> 3
    }
}

/** Forecast from a match list row, which only carries team names. */
fun TrainedModel.forecast(m: MatchSummary, vetoRuns: Int = 150): Prediction? {
    val a = teamIdsByName[m.team1.team.name.lowercase()] ?: return null
    val b = teamIdsByName[m.team2.team.name.lowercase()] ?: return null
    return predictor.predict(a, b, guessBestOf(m.series, m.eventName), today(), vetoRuns)
}

/** Forecast for a match page, which knows team ids and the exact format. */
fun TrainedModel.forecast(d: MatchDetail, vetoRuns: Int = 400): Prediction? {
    val a = d.team1.id?.takeIf { it in predictor.names } ?: teamIdsByName[d.team1.name.lowercase()] ?: return null
    val b = d.team2.id?.takeIf { it in predictor.names } ?: teamIdsByName[d.team2.name.lowercase()] ?: return null
    val bestOf = d.format?.drop(2)?.toIntOrNull() ?: guessBestOf(d.series, d.eventName)
    return predictor.predict(a, b, bestOf, today(), vetoRuns)
}

/**
 * Live forecast for a match page once anything beyond the pre-match picture is known:
 * the veto (maps), finished maps, the live map's score and sides, or locked agents.
 */
fun TrainedModel.liveForecast(d: MatchDetail): LiveForecast? {
    if (d.status == com.vcttracker.data.MatchStatus.COMPLETED || !gates.live) return null
    val a = d.team1.id?.takeIf { it in predictor.names } ?: teamIdsByName[d.team1.name.lowercase()] ?: return null
    val b = d.team2.id?.takeIf { it in predictor.names } ?: teamIdsByName[d.team2.name.lowercase()] ?: return null
    val bestOf = d.format?.drop(2)?.toIntOrNull() ?: guessBestOf(d.series, d.eventName)
    val state = LiveState.from(d, bestOf) ?: return null
    // Before any map starts the live forecast only adds the known maps; skip it if that didn't pass.
    if (!gates.maps && state.maps.none { it.started }) return null
    val preMatch = predictor.predict(a, b, bestOf, today(), vetoRuns = 200).team1Wins
    return predictor.predictLive(a, b, state, today(), preMatch)
}
