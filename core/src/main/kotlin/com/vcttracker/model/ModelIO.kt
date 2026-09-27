package com.vcttracker.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** The backtest headline numbers that ship with a model, so the app can show them. */
data class ModelReport(
    val trainedOn: Int,
    val testFrom: String,
    val testMatches: Int,
    val accuracy: Double,
    val logLoss: Double,
    val brier: Double,
    val baselines: List<Baseline>,
    /** (predicted bucket midpoint, observed win rate, count) */
    val calibration: List<Triple<Double, Double, Int>>,
    /** Accuracy at each live checkpoint: before the veto, maps known, after map 1, mid-map. */
    val live: List<LiveRow> = emptyList(),
    val events: EventReport? = null,
)

/** Event forecasts made before each unseen event started, against knowing nothing about the teams. */
data class EventReport(
    val events: Int,
    /** Average chance the forecast gave the eventual winner, and what picking at random would give. */
    val winnerChance: Double,
    val uniformChance: Double,
    val winnerLogLoss: Double,
    val uniformLogLoss: Double,
    val favouriteWon: Int,
    val withWinner: Int,
    val advanceN: Int,
    val advanceLogLoss: Double,
    val advanceBase: Double,
    val qualifyN: Int,
    val qualifyLogLoss: Double,
    val qualifyBase: Double,
) {
    /** Better than knowing nothing on every measure: the rule for shipping event forecasts. */
    val passes: Boolean get() = events > 0 && winnerLogLoss < uniformLogLoss &&
        (advanceN == 0 || advanceLogLoss < advanceBase) && (qualifyN == 0 || qualifyLogLoss < qualifyBase)
}

data class LiveRow(val moment: String, val forecasts: Int, val accuracy: Double, val logLoss: Double)

data class Baseline(val name: String, val matches: Int, val accuracy: Double, val logLoss: Double, val modelAccuracy: Double, val modelLogLoss: Double)

/** A trained predictor plus its report, as shipped to the app. */
/**
 * Which live signals passed the backtest gate. A signal that made forecasts worse than the
 * pre-match number on unseen matches is switched off, so live can never be worse than pre-match.
 */
data class LiveGates(
    /** Use the real maps once the veto is out (before any map starts). */
    val maps: Boolean = true,
    /** Update during the match (finished maps, live score). */
    val live: Boolean = true,
    /** Event forecasts (who advances, qualifies, wins): only if they beat knowing nothing. */
    val events: Boolean = false,
)

class TrainedModel(
    val predictor: Predictor,
    val generatedAt: Instant,
    val report: ModelReport?,
    val gates: LiveGates = LiveGates(),
    /** Stage formats learned from finished events, for event forecasts. */
    val book: FormatBook? = null,
) {

    /** Team ids by lowercase name, for screens that only know names. */
    val teamIdsByName: Map<String, String> by lazy {
        predictor.names.entries.associate { (id, name) -> name.lowercase() to id }
    }
}

object ModelIO {

    fun write(model: TrainedModel): String {
        val p = model.predictor
        val h = p.hyper
        fun belief(b: Belief) = JSONArray(listOf(round(b.mean), round(b.variance), b.lastDay))
        fun beliefs(m: Map<String, Belief>) = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, belief(v)) } }
        val root = JSONObject()
            .put("version", 2)
            .put("generatedAt", model.generatedAt.epochSecond)
            .put("gates", JSONObject().put("maps", model.gates.maps).put("live", model.gates.live).put("events", model.gates.events))
            .put("hyper", JSONObject()
                .put("roundWeight", h.roundWeight).put("playerSd", h.playerSd).put("newcomerMean", h.newcomerMean)
                .put("driftPerDay", h.driftPerDay).put("seasonSd", h.seasonSd).put("mapSd", h.mapSd)
                .put("mapDriftPerDay", h.mapDriftPerDay).put("regionSd", h.regionSd)
                .put("regionDriftPerDay", h.regionDriftPerDay).put("mapTemperature", h.mapTemperature)
                .put("agentSd", h.agentSd).put("comfortSd", h.comfortSd).put("agentDriftPerDay", h.agentDriftPerDay))
            .put("players", beliefs(p.engine.players))
            .put("teamMaps", beliefs(p.engine.teamMaps))
            .put("agentMaps", beliefs(p.engine.agentMaps))
            .put("playerAgents", beliefs(p.engine.playerAgents))
            .put("sideStats", JSONObject().also { o -> p.sideStats.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.first, v.second))) } })
            .put("regions", beliefs(p.engine.regions))
            .put("teamRegion", JSONObject(p.engine.teamRegion as Map<*, *>))
            .put("rosters", JSONObject().also { o -> p.engine.rosters.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
            .put("names", JSONObject(p.names as Map<*, *>))
            .put("season", p.engine.currentSeason)
            .put("mapsSeen", JSONObject(p.veto.lastSeen as Map<*, *>))
            .put("habits", JSONObject().also { o ->
                p.veto.teams.forEach { (team, hb) ->
                    o.put(team, JSONObject()
                        .put("d", hb.day)
                        .put("p", JSONObject(hb.picks.mapValues { round(it.value) } as Map<*, *>))
                        .put("b", JSONObject(hb.bans.mapValues { round(it.value) } as Map<*, *>)))
                }
            })
        model.report?.let { r ->
            root.put("report", JSONObject()
                .put("trainedOn", r.trainedOn).put("testFrom", r.testFrom).put("testMatches", r.testMatches)
                .put("accuracy", r.accuracy).put("logLoss", r.logLoss).put("brier", r.brier)
                .put("baselines", JSONArray(r.baselines.map {
                    JSONObject().put("name", it.name).put("matches", it.matches).put("accuracy", it.accuracy)
                        .put("logLoss", it.logLoss).put("modelAccuracy", it.modelAccuracy).put("modelLogLoss", it.modelLogLoss)
                }))
                .put("calibration", JSONArray(r.calibration.map { JSONArray(listOf(it.first, it.second, it.third)) }))
                .put("live", JSONArray(r.live.map {
                    JSONObject().put("moment", it.moment).put("n", it.forecasts).put("accuracy", it.accuracy).put("logLoss", it.logLoss)
                }))
                .also { o -> r.events?.let { o.put("events", eventReport(it)) } })
        }
        model.book?.let { root.put("book", book(it)) }
        return root.toString()
    }

    fun read(json: String): TrainedModel {
        val o = JSONObject(json)
        val hj = o.getJSONObject("hyper")
        val hyper = Hyper(
            roundWeight = hj.getDouble("roundWeight"), playerSd = hj.getDouble("playerSd"),
            newcomerMean = hj.getDouble("newcomerMean"), driftPerDay = hj.getDouble("driftPerDay"),
            seasonSd = hj.getDouble("seasonSd"), mapSd = hj.getDouble("mapSd"),
            mapDriftPerDay = hj.getDouble("mapDriftPerDay"), regionSd = hj.getDouble("regionSd"),
            regionDriftPerDay = hj.getDouble("regionDriftPerDay"), mapTemperature = hj.getDouble("mapTemperature"),
            agentSd = hj.optDouble("agentSd", 0.0), comfortSd = hj.optDouble("comfortSd", 0.0),
            agentDriftPerDay = hj.optDouble("agentDriftPerDay", 0.003),
        )
        val p = Predictor(hyper)
        fun beliefs(key: String, into: MutableMap<String, Belief>) {
            val m = o.getJSONObject(key)
            m.keys().forEach { k -> val a = m.getJSONArray(k); into[k] = Belief(a.getDouble(0), a.getDouble(1), a.getLong(2)) }
        }
        fun strings(key: String, into: MutableMap<String, String>) {
            val m = o.getJSONObject(key); m.keys().forEach { into[it] = m.getString(it) }
        }
        beliefs("players", p.engine.players)
        beliefs("teamMaps", p.engine.teamMaps)
        if (o.has("agentMaps")) beliefs("agentMaps", p.engine.agentMaps)
        if (o.has("playerAgents")) beliefs("playerAgents", p.engine.playerAgents)
        o.optJSONObject("sideStats")?.let { m ->
            m.keys().forEach { k -> val a = m.getJSONArray(k); p.sideStats[k] = a.getDouble(0) to a.getDouble(1) }
        }
        beliefs("regions", p.engine.regions)
        strings("teamRegion", p.engine.teamRegion)
        strings("names", p.names)
        o.getJSONObject("rosters").let { r ->
            r.keys().forEach { k -> val a = r.getJSONArray(k); p.engine.rosters[k] = List(a.length()) { a.getString(it) } }
        }
        o.getJSONObject("mapsSeen").let { m -> m.keys().forEach { p.veto.lastSeen[it] = m.getLong(it) } }
        o.getJSONObject("habits").let { m ->
            m.keys().forEach { team ->
                val hb = m.getJSONObject(team)
                val habits = VetoModel.Habits().also { it.day = hb.getLong("d") }
                hb.getJSONObject("p").let { x -> x.keys().forEach { habits.picks[it] = x.getDouble(it) } }
                hb.getJSONObject("b").let { x -> x.keys().forEach { habits.bans[it] = x.getDouble(it) } }
                p.veto.teams[team] = habits
            }
        }
        p.engine.resumeSeason(o.optInt("season", -1))
        val report = o.optJSONObject("report")?.let { r ->
            val bl = r.getJSONArray("baselines")
            val cal = r.getJSONArray("calibration")
            ModelReport(
                trainedOn = r.getInt("trainedOn"), testFrom = r.getString("testFrom"), testMatches = r.getInt("testMatches"),
                accuracy = r.getDouble("accuracy"), logLoss = r.getDouble("logLoss"), brier = r.getDouble("brier"),
                baselines = List(bl.length()) { i ->
                    val b = bl.getJSONObject(i)
                    Baseline(b.getString("name"), b.getInt("matches"), b.getDouble("accuracy"), b.getDouble("logLoss"),
                        b.getDouble("modelAccuracy"), b.getDouble("modelLogLoss"))
                },
                calibration = List(cal.length()) { i -> val c = cal.getJSONArray(i); Triple(c.getDouble(0), c.getDouble(1), c.getInt(2)) },
                live = r.optJSONArray("live")?.let { a ->
                    List(a.length()) { i ->
                        val x = a.getJSONObject(i)
                        LiveRow(x.getString("moment"), x.getInt("n"), x.getDouble("accuracy"), x.getDouble("logLoss"))
                    }
                }.orEmpty(),
                events = r.optJSONObject("events")?.let(::eventReport),
            )
        }
        val gates = o.optJSONObject("gates")?.let { LiveGates(it.optBoolean("maps", true), it.optBoolean("live", true), it.optBoolean("events", false)) } ?: LiveGates()
        return TrainedModel(p, Instant.ofEpochSecond(o.getLong("generatedAt")), report, gates, o.optJSONObject("book")?.let(::book))
    }

    private fun eventReport(e: EventReport) = JSONObject()
        .put("events", e.events).put("winnerChance", e.winnerChance).put("uniformChance", e.uniformChance)
        .put("winnerLogLoss", e.winnerLogLoss).put("uniformLogLoss", e.uniformLogLoss)
        .put("favouriteWon", e.favouriteWon).put("withWinner", e.withWinner)
        .put("advanceN", e.advanceN).put("advanceLogLoss", e.advanceLogLoss).put("advanceBase", e.advanceBase)
        .put("qualifyN", e.qualifyN).put("qualifyLogLoss", e.qualifyLogLoss).put("qualifyBase", e.qualifyBase)

    private fun eventReport(o: JSONObject) = EventReport(
        o.getInt("events"), o.getDouble("winnerChance"), o.getDouble("uniformChance"),
        o.getDouble("winnerLogLoss"), o.getDouble("uniformLogLoss"), o.getInt("favouriteWon"), o.getInt("withWinner"),
        o.getInt("advanceN"), o.getDouble("advanceLogLoss"), o.getDouble("advanceBase"),
        o.getInt("qualifyN"), o.getDouble("qualifyLogLoss"), o.getDouble("qualifyBase"),
    )

    private fun feed(f: Feed) = when (f) {
        Feed.Seed -> "S"
        is Feed.Winner -> "W:" + f.match
        is Feed.Loser -> "L:" + f.match
    }

    private fun feed(s: String): Feed = when {
        s.startsWith("W:") -> Feed.Winner(s.drop(2))
        s.startsWith("L:") -> Feed.Loser(s.drop(2))
        else -> Feed.Seed
    }

    private fun strings(m: Map<String, String>) = JSONObject(m as Map<*, *>)

    private fun strings(o: JSONObject): Map<String, String> = o.keys().asSequence().associateWith { o.getString(it) }

    private fun book(b: FormatBook) = JSONObject()
        .put("brackets", JSONObject().also { o ->
            b.brackets.forEach { (shape, t) ->
                o.put(shape, JSONObject()
                    .put("feeds", JSONObject().also { f -> t.feeds.forEach { (k, v) -> f.put(k, JSONArray(v.map(::feed))) } })
                    .put("order", JSONArray(t.order))
                    .put("eliminations", strings(t.eliminations))
                    .put("final", t.finalMatch ?: "")
                    .put("support", t.support))
            }
        })
        .put("swiss", JSONObject().also { o -> b.swiss.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.first, v.second))) } })
        .put("advancing", JSONObject(b.advancing as Map<*, *>))
        .put("seeding", JSONObject().also { o -> b.seeding.forEach { (k, v) -> o.put(k, strings(v)) } })

    private fun book(o: JSONObject): FormatBook {
        val br = o.getJSONObject("brackets")
        val brackets = br.keys().asSequence().associateWith { shape ->
            val t = br.getJSONObject(shape)
            val feeds = t.getJSONObject("feeds").let { f ->
                f.keys().asSequence().associateWith { k -> f.getJSONArray(k).let { a -> List(a.length()) { feed(a.getString(it)) } } }
            }
            val order = t.getJSONArray("order").let { a -> List(a.length()) { a.getString(it) } }
            BracketTemplate(shape, feeds, order, strings(t.getJSONObject("eliminations")), t.getString("final").ifBlank { null }, t.getInt("support"))
        }
        val sw = o.getJSONObject("swiss")
        val adv = o.getJSONObject("advancing")
        val seeds = o.getJSONObject("seeding")
        return FormatBook(
            brackets = brackets,
            swiss = sw.keys().asSequence().associateWith { k -> sw.getJSONArray(k).let { it.getInt(0) to it.getInt(1) } },
            advancing = adv.keys().asSequence().associateWith { adv.getInt(it) },
            seeding = seeds.keys().asSequence().associateWith { strings(seeds.getJSONObject(it)) },
        )
    }

    private fun round(x: Double) = Math.round(x * 1e5) / 1e5
}
