package com.vcttracker.trainer

import com.vcttracker.data.Region
import com.vcttracker.model.Hyper
import com.vcttracker.model.ModelReport
import java.time.LocalDate
import java.time.ZoneOffset

object Report {

    private fun pct(x: Double) = "%.1f%%".format(x * 100)
    private fun f3(x: Double) = "%.3f".format(x)

    fun markdown(
        r: ModelReport,
        test: List<Forecast>,
        train: List<Forecast>,
        hyper: Hyper,
    ): String = buildString {
        val first = test.minOfOrNull { it.match.time }?.toString()?.take(10)
        val last = test.maxOfOrNull { it.match.time }?.toString()?.take(10)
        appendLine("# Match predictor backtest")
        appendLine()
        appendLine("_Generated ${LocalDate.now()} by `./gradlew :trainer:run --args=\"train\"`._")
        appendLine()
        appendLine("**Out-of-sample test:** ${r.testMatches} VCT series from $first to $last. The model forecast every one of them before learning its result. Its settings were tuned only on the ${train.size} series before ${r.testFrom}, starting July 2023.")
        appendLine()
        appendLine("| | Accuracy | Log-loss | Brier |")
        appendLine("|---|---|---|---|")
        appendLine("| **Model** | **${pct(r.accuracy)}** | **${f3(r.logLoss)}** | **${f3(r.brier)}** |")
        appendLine()
        appendLine("Accuracy is how often the favourite won. Log-loss and Brier score the probabilities themselves; lower is better. A coin flip scores a log-loss of 0.693.")
        appendLine()
        appendLine("## Against baselines")
        appendLine()
        appendLine("Each row compares the model with a baseline on exactly the same matches.")
        appendLine()
        appendLine("| Baseline | Matches | Baseline accuracy | Model accuracy | Baseline log-loss | Model log-loss |")
        appendLine("|---|---|---|---|---|---|")
        for (b in r.baselines) {
            appendLine("| ${b.name} | ${b.matches} | ${pct(b.accuracy)} | ${pct(b.modelAccuracy)} | ${f3(b.logLoss)} | ${f3(b.modelLogLoss)} |")
        }
        appendLine()
        appendLine("Bookmaker odds come from the pre-match prices vlr.gg keeps on match pages. After a match only the winner's price survives, so the implied probability is estimated with the typical 6.8% margin removed. That estimate is slightly generous to the bookmaker.")
        appendLine()
        appendLine("## Calibration")
        appendLine()
        appendLine("When the model makes a team the favourite at a given confidence, this is how often that team actually won.")
        appendLine()
        appendLine("| Model said | Favourite won | Series |")
        appendLine("|---|---|---|")
        for ((p, obs, n) in r.calibration) appendLine("| ${pct(p)} | ${pct(obs)} | $n |")
        appendLine()
        appendLine("## Confident picks (70% or more)")
        appendLine()
        appendLine("Each forecaster judged only on the series where it was at least 70% sure of one side.")
        appendLine()
        appendLine("| Forecaster | Pool | Confident picks | Share of pool | Won |")
        appendLine("|---|---|---|---|---|")
        fun confident(name: String, pool: List<Forecast>, prob: (Forecast) -> Double) {
            val picks = pool.filter { maxOf(prob(it), 1 - prob(it)) >= 0.7 }
            if (picks.isEmpty()) {
                appendLine("| $name | ${pool.size} | 0 | 0% | – |")
                return
            }
            val won = picks.count { (prob(it) >= 0.5) == it.team1Won }
            appendLine("| $name | ${pool.size} | ${picks.size} | ${pct(picks.size.toDouble() / pool.size)} | ${pct(won.toDouble() / picks.size)} |")
        }
        confident("Model", test) { it.final }
        confident("Team Elo", test) { it.elo }
        val oddsPool = test.filter { it.odds != null }
        confident("Bookmaker odds", oddsPool) { it.odds!! }
        confident("Model, same series as bookmakers", oddsPool) { it.final }
        appendLine()
        appendLine("## Breakdown")
        appendLine()
        appendLine("| Slice | Series | Accuracy | Log-loss |")
        appendLine("|---|---|---|---|")
        fun slice(name: String, s: List<Forecast>) {
            if (s.isEmpty()) return
            val sc = Backtest.score(s.map { it.final }, s.map { it.team1Won })
            appendLine("| $name | ${sc.n} | ${pct(sc.accuracy)} | ${f3(sc.logLoss)} |")
        }
        slice("International (Masters, Champions)", test.filter { it.match.region == Region.INTERNATIONAL })
        slice("Regional leagues", test.filter { it.match.region != Region.INTERNATIONAL })
        for (region in listOf(Region.AMERICAS, Region.EMEA, Region.PACIFIC, Region.CHINA)) slice(region.label, test.filter { it.match.region == region })
        slice("Bo1/Bo3", test.filter { it.match.bestOf <= 3 })
        slice("Bo5", test.filter { it.match.bestOf == 5 })
        test.groupBy { it.match.time.atZone(ZoneOffset.UTC).year }.toSortedMap().forEach { (y, s) -> slice("$y", s) }
        val confident = test.filter { maxOf(it.final, 1 - it.final) >= 0.7 }
        slice("Favourite at 70% or more", confident)
        appendLine()
        val maps = test.flatMap { it.maps }
        val ms = Backtest.score(maps.map { it.first }, maps.map { it.second })
        appendLine("**Map level** (with the map known): ${ms.n} maps, ${pct(ms.accuracy)} accuracy, log-loss ${f3(ms.logLoss)}.")
        appendLine()
        appendLine("## How it works")
        appendLine()
        appendLine("1. **Player ratings, updated on round margins.** Every player has a skill estimate with an uncertainty attached. After each map, the round score (for example 13–8) updates everyone who played. A team's strength is the average of its five current players, so roster moves, loans and rebrands carry the right history with them.")
        appendLine("2. **Map offsets and region offsets.** Each team has its own adjustment per map. Each region has a strength offset that only cross-region matches can move, i.e. Masters and Champions.")
        appendLine("3. **Round → map → series.** The round-win edge becomes a map-win probability through the exact race-to-13 formula with win-by-two overtime. Maps are then combined into Bo1/Bo3/Bo5 odds with a state-by-state calculation. Uncertainty about the rosters is averaged over with Gauss–Hermite quadrature, so a barely known lineup gives more cautious odds.")
        appendLine("4. **Veto simulation.** Each team's recent pick and ban habits drive a Monte-Carlo simulation of the actual VCT veto format. The series odds are averaged over the maps likely to be played.")
        appendLine()
        appendLine("### Tried and rejected")
        appendLine()
        appendLine("Each idea was judged on pre-2025 data only, and kept only if it helped there.")
        appendLine()
        appendLine("- **A team-level rating on top of the players** (coaching, teamwork): 200 tuning trials never beat the players-only model's pre-2025 log-loss (0.6541). The player ratings already carry it.")
        appendLine("- **Stacking a separate team Elo into the calibration layer:** no measurable change on the 2024 validation year, so it was dropped.")
        appendLine("- **A calibration layer** (a regularised logistic regression adding form, head-to-head, rest days and the stage on top of the ratings) made no difference on the 2024 validation year. On the test years it was slightly worse: 61.2% accuracy against 61.8%, and 74.4% against 79.5% on 70%+ picks. The ratings are well calibrated on their own, so the layer was removed.")
        appendLine()
        appendLine("### Limits")
        appendLine()
        appendLine("- Pro VCT is very even by design: over half of all series are forecast between 50% and 60%, and those are close to coin flips for any model.")
        appendLine("- Bookmakers do better on the matches where their prices survive. They see things this model can't, such as stand-ins, illness and scrim results.")
        appendLine("- Lineups are taken from each team's latest match, so a surprise substitution isn't known until the next match is played.")
        appendLine()
        appendLine("### Tuned settings (fitted before ${r.testFrom} only)")
        appendLine()
        appendLine("```")
        appendLine(hyper.toString().removePrefix("Hyper(").removeSuffix(")").replace(", ", "\n"))
        appendLine("```")
    }
}
