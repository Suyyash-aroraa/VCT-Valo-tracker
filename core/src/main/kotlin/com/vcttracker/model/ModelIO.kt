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
)

data class Baseline(val name: String, val matches: Int, val accuracy: Double, val logLoss: Double, val modelAccuracy: Double, val modelLogLoss: Double)

/** A trained predictor plus its report, as shipped to the app. */
class TrainedModel(val predictor: Predictor, val generatedAt: Instant, val report: ModelReport?) {

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
            .put("hyper", JSONObject()
                .put("roundWeight", h.roundWeight).put("playerSd", h.playerSd).put("newcomerMean", h.newcomerMean)
                .put("driftPerDay", h.driftPerDay).put("seasonSd", h.seasonSd).put("mapSd", h.mapSd)
                .put("mapDriftPerDay", h.mapDriftPerDay).put("regionSd", h.regionSd)
                .put("regionDriftPerDay", h.regionDriftPerDay).put("mapTemperature", h.mapTemperature))
            .put("players", beliefs(p.engine.players))
            .put("teamMaps", beliefs(p.engine.teamMaps))
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
                .put("calibration", JSONArray(r.calibration.map { JSONArray(listOf(it.first, it.second, it.third)) })))
        }
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
            )
        }
        return TrainedModel(p, Instant.ofEpochSecond(o.getLong("generatedAt")), report)
    }

    private fun round(x: Double) = Math.round(x * 1e5) / 1e5
}
