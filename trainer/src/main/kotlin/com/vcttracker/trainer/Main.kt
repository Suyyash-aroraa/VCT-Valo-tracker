package com.vcttracker.trainer

import com.vcttracker.model.Hyper
import com.vcttracker.model.ModelIO
import com.vcttracker.model.Predictor
import com.vcttracker.model.TrainedModel
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val DATA = File("model/data/matches.jsonl")
private val HYPER = File("model/hyper.json")
private val MODEL_OUT = listOf(File("model/model.json"), File("app/src/main/assets/model.json"))
private val REPORT = File("docs/BACKTEST.md")

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
        "tune" -> tune(args.getOrNull(1)?.toInt() ?: 80)
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

private fun train() {
    val data = Dataset.load(DATA)
    val hyper = if (HYPER.exists()) hyperFrom(JSONObject(HYPER.readText())) else Hyper()
    println("Walk-forward over ${data.size} matches with $hyper")

    // Every match after warm-up is forecast from the state before it, then learned from.
    var finalState: Predictor? = null
    val forecasts = Backtest.run(data, hyper, WARMUP_END, vetoRuns = 300, onPredictor = { finalState = it })

    // For model-selection experiments, VCT_TEST_UNTIL keeps the evaluation inside a validation window.
    val testUntil = System.getenv("VCT_TEST_UNTIL")?.let(Instant::parse) ?: Instant.MAX
    val test = forecasts.filter { it.match.time >= TEST_FROM && it.match.time < testUntil }
    val train = forecasts.filter { it.match.time < TEST_FROM }
    val report = Backtest.report(test, trainedOn = train.size, testFrom = TEST_FROM.toString().take(10))
    REPORT.parentFile.mkdirs()
    REPORT.writeText(Report.markdown(report, test, train, hyper))
    println(REPORT.readText())

    // The shipped model has seen every match up to today.
    val predictor = finalState!!
    val json = ModelIO.write(TrainedModel(predictor, Instant.now(), report))
    MODEL_OUT.forEach { it.parentFile.mkdirs(); it.writeText(json) }
    println("Wrote model (${json.length / 1024} KB)")
    println("Latest match in data: ${data.lastOrNull()?.time} (today ${LocalDate.now()})")
}

private fun hyperJson(h: Hyper) = JSONObject()
    .put("roundWeight", h.roundWeight).put("playerSd", h.playerSd).put("newcomerMean", h.newcomerMean)
    .put("driftPerDay", h.driftPerDay).put("seasonSd", h.seasonSd).put("mapSd", h.mapSd)
    .put("mapDriftPerDay", h.mapDriftPerDay).put("regionSd", h.regionSd)
    .put("regionDriftPerDay", h.regionDriftPerDay).put("mapTemperature", h.mapTemperature)

private fun hyperFrom(o: JSONObject) = Hyper(
    roundWeight = o.getDouble("roundWeight"), playerSd = o.getDouble("playerSd"), newcomerMean = o.getDouble("newcomerMean"),
    driftPerDay = o.getDouble("driftPerDay"), seasonSd = o.getDouble("seasonSd"), mapSd = o.getDouble("mapSd"),
    mapDriftPerDay = o.getDouble("mapDriftPerDay"), regionSd = o.getDouble("regionSd"),
    regionDriftPerDay = o.getDouble("regionDriftPerDay"), mapTemperature = o.getDouble("mapTemperature"),
)
