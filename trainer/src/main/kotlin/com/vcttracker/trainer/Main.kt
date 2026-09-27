package com.vcttracker.trainer

import com.vcttracker.model.Hyper
import com.vcttracker.model.LiveGates
import com.vcttracker.model.ModelIO
import com.vcttracker.model.Predictor
import com.vcttracker.model.TrainedModel
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val DATA = File(System.getenv("VCT_DATA") ?: "model/data/matches.jsonl")
private val HYPER = File("model/hyper.json")
private val MODEL_OUT = listOf(File("model/model.json"), File("app/src/main/assets/model.json"))
private val REPORT = File("docs/BACKTEST.md")
private val EVENTS = File("model/data/events")

/** Ratings need a few months of history before their forecasts mean anything. */
private val WARMUP_END: Instant = Instant.parse("2023-07-01T00:00:00Z")

/** Settings are tuned only on matches before this; everything after is the out-of-sample test. */
private val TEST_FROM: Instant = Instant.parse(System.getenv("VCT_TEST_FROM") ?: "2025-01-01T00:00:00Z")

/**
 * ./gradlew :trainer:run --args="update"       fetch new completed matches
 * ./gradlew :trainer:run --args="tune 80"      random-search the filter settings on 2023–24
 * ./gradlew :trainer:run --args="train"        walk-forward backtest, write the report and model.json
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "update" -> Dataset.update(DATA)
        // Re-fetch everything into a fresh file (used when the record format gains fields).
        "rebuild" -> Dataset.update(File(args.getOrElse(1) { "model/data/rebuild.jsonl" }))
        "tune" -> tune(args.getOrNull(1)?.toInt() ?: 80)
        "tune-agents" -> tuneAgents(args.getOrNull(1)?.toInt() ?: 40)
        "events" -> EventArchive(EVENTS).update()
        "events-backtest" -> {
            val data = Dataset.load(DATA)
            val hyper = (if (HYPER.exists()) hyperFrom(JSONObject(HYPER.readText())) else Hyper()).copy(agentSd = 0.0, comfortSd = 0.0)
            val events = EventBacktest.load(EventArchive(EVENTS))
            val from = Instant.parse(args.getOrElse(1) { "2023-07-01T00:00:00Z" })
            val results = EventBacktest.run(data, hyper, events, from, runs = 2000, log = ::println)
            EventBacktest.summary(results.filter { it.event.start >= TEST_FROM }).forEach { println("TEST " + it) }
            EventBacktest.summary(results.filter { it.event.start < TEST_FROM }).forEach { println("EARLIER " + it) }
        }
        "shapes" -> {
            val archive = EventArchive(EVENTS)
            for (year in 2023..LocalDate.now().year) {
                val season = archive.season(year) ?: continue
                for (e in season.events) {
                    println("== $year ${e.name} [${e.stage} ${e.region}] ${e.status}")
                    for ((slug, d) in archive.stages(e.id)) {
                        val brackets = d.sections.joinToString(" | ") { s ->
                            s.title + ": " + s.brackets.joinToString(" ; ") { b ->
                                (if (b.isLower) "L[" else "U[") + b.columns.joinToString(",") { c -> "${c.label}=${c.matches.size}" } + "]"
                            }
                        }
                        val groups = d.groups.joinToString(" ") { "${it.title}(${it.rows.size}, adv=${it.rows.count { r -> r.advanced }})" }
                        println("   $slug: $brackets ${if (groups.isNotEmpty()) "GROUPS $groups" else ""}")
                    }
                    val notes = archive.stages(e.id).firstOrNull()?.second?.prizes.orEmpty().filter { it.note != null }.map { "${it.place}:${it.note}" }.distinct()
                    if (notes.isNotEmpty()) println("   notes: $notes")
                }
            }
        }
        "train" -> train()
        else -> println("usage: update | tune [trials] | train")
    }
}

private fun tune(trials: Int) {
    val data = Dataset.load(DATA)
    println("Tuning on ${data.count { it.time in WARMUP_END..TEST_FROM }} matches between $WARMUP_END and $TEST_FROM")
    val start = if (HYPER.exists()) hyperFrom(JSONObject(HYPER.readText())) else Hyper()
    val best = Backtest.tune(data, WARMUP_END, TEST_FROM, trials, ::println, start)
    HYPER.writeText(hyperJson(best).toString(2))
    println("Saved $HYPER")
}

private fun tuneAgents(trials: Int) {
    val data = Dataset.load(DATA)
    val base = if (HYPER.exists()) hyperFrom(JSONObject(HYPER.readText())) else Hyper()
    val best = Backtest.tuneAgents(data, base, WARMUP_END, TEST_FROM, trials, ::println)
    HYPER.writeText(hyperJson(best).toString(2))
    println("Saved $HYPER")
}

private fun train() {
    val data = Dataset.load(DATA)
    val hyper = if (HYPER.exists()) hyperFrom(JSONObject(HYPER.readText())) else Hyper()
    println("Walk-forward over ${data.size} matches with $hyper")

    // For model-selection experiments, VCT_TEST_UNTIL keeps the evaluation inside a validation window.
    val testUntil = System.getenv("VCT_TEST_UNTIL")?.let(Instant::parse) ?: Instant.MAX

    // Every match after warm-up is forecast from the state before it, then learned from.
    fun walk(h: Hyper): Pair<List<Forecast>, Predictor> {
        var finalState: Predictor? = null
        val f = Backtest.run(data, h, WARMUP_END, vetoRuns = 300, onPredictor = { finalState = it }, withLive = true)
        return f to finalState!!
    }
    fun testOf(f: List<Forecast>) = f.filter { it.match.time >= TEST_FROM && it.match.time < testUntil }

    // Gate 1, agents: they ship only if they sharpen map forecasts at agent select AND leave the
    // pre-match forecast no worse. Otherwise the model is retrained without them.
    var chosen = walk(hyper)
    var shipped = hyper
    val gateLog = ArrayList<String>()
    if (hyper.agentSd > 0 || hyper.comfortSd > 0) {
        val off = hyper.copy(agentSd = 0.0, comfortSd = 0.0)
        val without = walk(off)
        val withAgents = testOf(chosen.first)
        val noAgents = testOf(without.first)
        val mapsWith = withAgents.flatMap { it.live?.mapStart.orEmpty() }
        val mapsWithout = noAgents.flatMap { it.live?.mapStart.orEmpty() }
        val mapLlWith = Backtest.score(mapsWith.map { it.second }, mapsWith.map { it.third }).logLoss
        val mapLlWithout = Backtest.score(mapsWithout.map { it.first }, mapsWithout.map { it.third }).logLoss
        val preWith = Backtest.score(withAgents.map { it.final }, withAgents.map { it.team1Won }).logLoss
        val preWithout = Backtest.score(noAgents.map { it.final }, noAgents.map { it.team1Won }).logLoss
        val pass = mapLlWith < mapLlWithout && preWith <= preWithout
        gateLog += "Agents: map log-loss at agent select %.4f with vs %.4f without; pre-match %.4f vs %.4f -> %s".format(
            mapLlWith, mapLlWithout, preWith, preWithout, if (pass) "KEPT" else "SWITCHED OFF")
        if (!pass) {
            chosen = without
            shipped = off
        }
    }
    val forecasts = chosen.first
    val test = testOf(forecasts)
    val train = forecasts.filter { it.match.time < TEST_FROM }

    // Gates 2 and 3: live updates must beat the pre-match forecast on the same unseen series.
    fun ll(xs: List<Forecast>, p: (Forecast) -> Double) = Backtest.score(xs.map(p), xs.map { it.team1Won }).logLoss
    val vetoSet = test.filter { it.live?.postVeto != null }
    val mapsPass = ll(vetoSet) { it.live!!.postVeto!! } <= ll(vetoSet) { it.final }
    gateLog += "Maps known: series log-loss %.4f vs pre-match %.4f on %d series -> %s".format(
        ll(vetoSet) { it.live!!.postVeto!! }, ll(vetoSet) { it.final }, vetoSet.size, if (mapsPass) "KEPT" else "SWITCHED OFF")
    val map1Set = test.filter { it.live?.afterMap1 != null }
    val livePass = ll(map1Set) { it.live!!.afterMap1!! } <= ll(map1Set) { it.final }
    gateLog += "After map 1: series log-loss %.4f vs pre-match %.4f on %d series -> %s".format(
        ll(map1Set) { it.live!!.afterMap1!! }, ll(map1Set) { it.final }, map1Set.size, if (livePass) "KEPT" else "SWITCHED OFF")
    // The live map's score must sharpen the map forecast compared with its 0–0 number.
    val starts = test.flatMap { it.live?.mapStart.orEmpty() }
    val startLl = Backtest.score(starts.map { it.second }, starts.map { it.third }).logLoss
    val inMap6 = test.flatMap { it.live?.inMap.orEmpty() }.filter { it.first == 6 }
    val scoreLl = Backtest.score(inMap6.map { it.second }, inMap6.map { it.third }).logLoss
    val scorePass = scoreLl <= startLl
    gateLog += "Live round score: map log-loss %.4f after 6 rounds vs %.4f at 0–0 on %d maps -> %s".format(
        scoreLl, startLl, inMap6.size, if (scorePass) "KEPT" else "SWITCHED OFF")
    // Gate 4, events: forecasts of who advances, qualifies and wins ship only if every measure
    // beats knowing nothing about the teams, on events that started after TEST_FROM.
    val archive = EventArchive(EVENTS)
    val events = EventBacktest.load(archive)
    val eventResults = if (events.isEmpty()) emptyList() else
        EventBacktest.run(data, shipped, events, TEST_FROM, runs = 2000, log = ::println).filter { it.event.start < testUntil }
    val eventReport = eventResults.takeIf { it.isNotEmpty() }?.let(EventBacktest::report)
    val eventsPass = eventReport?.passes == true
    if (eventReport != null) gateLog += "Events: winner log-loss %.3f vs %.3f uniform, advancing %.3f vs %.3f, qualifying %.3f vs %.3f on %d events -> %s".format(
        eventReport.winnerLogLoss, eventReport.uniformLogLoss, eventReport.advanceLogLoss, eventReport.advanceBase,
        eventReport.qualifyLogLoss, eventReport.qualifyBase, eventReport.events, if (eventsPass) "KEPT" else "SWITCHED OFF")
    val book = EventBacktest.book(events, Instant.now())
    val gates = LiveGates(maps = mapsPass, live = livePass && scorePass, events = eventsPass)
    gateLog.forEach(::println)

    val report = Backtest.report(test, trainedOn = train.size, testFrom = TEST_FROM.toString().take(10)).copy(events = eventReport)
    REPORT.parentFile.mkdirs()
    REPORT.writeText(Report.markdown(report, test, train, shipped, gateLog, eventResults))
    println(REPORT.readText())

    // The shipped model has seen every match up to today.
    val predictor = chosen.second
    val json = ModelIO.write(TrainedModel(predictor, Instant.now(), report, gates, book))
    MODEL_OUT.forEach { it.parentFile.mkdirs(); it.writeText(json) }
    println("Wrote model (${json.length / 1024} KB)")
    println("Latest match in data: ${data.lastOrNull()?.time} (today ${LocalDate.now()})")
}

private fun hyperJson(h: Hyper) = JSONObject()
    .put("roundWeight", h.roundWeight).put("playerSd", h.playerSd).put("newcomerMean", h.newcomerMean)
    .put("driftPerDay", h.driftPerDay).put("seasonSd", h.seasonSd).put("mapSd", h.mapSd)
    .put("mapDriftPerDay", h.mapDriftPerDay).put("regionSd", h.regionSd)
    .put("regionDriftPerDay", h.regionDriftPerDay).put("mapTemperature", h.mapTemperature)
    .put("agentSd", h.agentSd).put("comfortSd", h.comfortSd).put("agentDriftPerDay", h.agentDriftPerDay)

private fun hyperFrom(o: JSONObject) = Hyper(
    roundWeight = o.getDouble("roundWeight"), playerSd = o.getDouble("playerSd"), newcomerMean = o.getDouble("newcomerMean"),
    driftPerDay = o.getDouble("driftPerDay"), seasonSd = o.getDouble("seasonSd"), mapSd = o.getDouble("mapSd"),
    mapDriftPerDay = o.getDouble("mapDriftPerDay"), regionSd = o.getDouble("regionSd"),
    regionDriftPerDay = o.getDouble("regionDriftPerDay"), mapTemperature = o.getDouble("mapTemperature"),
    agentSd = o.optDouble("agentSd", 0.0), comfortSd = o.optDouble("comfortSd", 0.0),
    agentDriftPerDay = o.optDouble("agentDriftPerDay", 0.003),
)
