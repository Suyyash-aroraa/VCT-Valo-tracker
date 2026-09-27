# Match predictor backtest

_Generated 2026-09-27 by `./gradlew :trainer:run --args="train"`._

**Out-of-sample test:** 1098 VCT series from 2025-01-11 to 2026-09-26. The model forecast every one of them before learning its result. Its settings were tuned only on the 514 series before 2025-01-01, starting July 2023.

| | Accuracy | Log-loss | Brier |
|---|---|---|---|
| **Model** | **61.8%** | **0.651** | **0.230** |

Accuracy is how often the favourite won. Log-loss and Brier score the probabilities themselves; lower is better. A coin flip scores a log-loss of 0.693.

## Against baselines

Each row compares the model with a baseline on exactly the same matches.

| Baseline | Matches | Baseline accuracy | Model accuracy | Baseline log-loss | Model log-loss |
|---|---|---|---|---|---|
| Coin flip | 1098 | 50.0% | 61.8% | 0.693 | 0.651 |
| Team Elo | 1098 | 59.7% | 61.8% | 0.669 | 0.651 |
| Bookmaker odds | 123 | 66.7% | 66.7% | 0.583 | 0.626 |

Bookmaker odds come from the pre-match prices vlr.gg keeps on match pages. After a match only the winner's price survives, so the implied probability is estimated with the typical 6.8% margin removed. That estimate is slightly generous to the bookmaker.

## Calibration

When the model makes a team the favourite at a given confidence, this is how often that team actually won.

| Model said | Favourite won | Series |
|---|---|---|
| 54.7% | 56.1% | 647 |
| 64.2% | 67.0% | 339 |
| 73.9% | 79.0% | 100 |
| 81.6% | 83.3% | 12 |

## Confident picks (70% or more)

Each forecaster judged only on the series where it was at least 70% sure of one side.

| Forecaster | Pool | Confident picks | Share of pool | Won |
|---|---|---|---|---|
| Model | 1098 | 112 | 10.2% | 79.5% |
| Team Elo | 1098 | 281 | 25.6% | 69.4% |
| Bookmaker odds | 123 | 30 | 24.4% | 90.0% |
| Model, same series as bookmakers | 123 | 10 | 8.1% | 90.0% |

## Live: how the forecast sharpens as the match unfolds

The same unseen matches, forecast again at each point a live viewer gets new information.

| Moment | Forecasts | Accuracy | Log-loss |
|---|---|---|---|
| Series, before the veto | 1098 | 61.8% | 0.651 |
| Series, maps known | 1098 | 62.2% | 0.651 |
| Series, after map 1 | 1092 | 73.7% | 0.548 |
| Map at 0–0, without agents | 2793 | 57.3% | 0.676 |
| Map at 0–0, with agents | 2793 | 57.3% | 0.676 |
| Map after 6 rounds | 2793 | 71.2% | 0.566 |
| Map after 12 rounds | 2793 | 77.4% | 0.476 |
| Map after 18 rounds | 2140 | 81.3% | 0.396 |

### Safety gate

A live signal ships only if it beats the pre-match forecast on these same unseen matches; otherwise the app falls back to the pre-match number. This check reruns on every retrain.

- Agents: map log-loss at agent select 0.6775 with vs 0.6765 without; pre-match 0.6515 vs 0.6506 -> SWITCHED OFF
- Maps known: series log-loss 0.6512 vs pre-match 0.6506 on 1098 series -> SWITCHED OFF
- After map 1: series log-loss 0.5485 vs pre-match 0.6505 on 1092 series -> KEPT
- Live round score: map log-loss 0.5660 after 6 rounds vs 0.6765 at 0–0 on 2793 maps -> KEPT
- Events: winner log-loss 1.870 vs 2.500 uniform, advancing 0.622 vs 0.655, qualifying 0.455 vs 0.495 on 13 events -> KEPT

## Breakdown

| Slice | Series | Accuracy | Log-loss |
|---|---|---|---|
| International (Masters, Champions) | 130 | 60.8% | 0.654 |
| Regional leagues | 968 | 62.0% | 0.650 |
| Americas | 239 | 66.7% | 0.631 |
| EMEA | 239 | 58.8% | 0.654 |
| Pacific | 239 | 58.4% | 0.661 |
| China | 251 | 63.9% | 0.655 |
| Bo1/Bo3 | 1036 | 61.8% | 0.651 |
| Bo5 | 62 | 62.9% | 0.649 |
| 2025 | 504 | 63.3% | 0.643 |
| 2026 | 594 | 60.6% | 0.657 |
| Favourite at 70% or more | 112 | 79.5% | 0.508 |

**Map level** (with the map known): 2794 maps, 57.2% accuracy, log-loss 0.677.

## Events: who advances, qualifies and wins

Every event that started from 2025-01-01 on was simulated 2,000 times before its first match, using only earlier results. Formats are learned stage by stage from events that had already finished, so an event is forecast only once each of its bracket shapes has been seen played out. The baseline for each question knows the format but nothing about the teams: every team has the same chance.

| Question | Forecasts | Model log-loss | Knowing nothing |
|---|---|---|---|
| Who wins the event | 13 events | 1.870 | 2.500 |
| Who gets out of each stage | 144 team-stages | 0.622 | 0.655 |
| Who qualifies for Masters / Champions | 100 team-slots | 0.455 | 0.495 |

On average the model gave the eventual winner **17.8%** before the event, against 8.3% for a random pick. The favourite won 4 of 13: even a clear favourite rarely has better than a one-in-three chance to win a whole event.

| Event | Teams | Winner | Chance given to the winner | Model favourite |
|---|---|---|---|---|
| Valorant Masters Bangkok 2025 | 8 | T1 | 7.8% | G2 Esports |
| Valorant Masters Toronto 2025 | 12 | Paper Rex | 10.4% | G2 Esports |
| VCT 2025: China Stage 2 | 13 | Bilibili Gaming | 25.8% | Bilibili Gaming |
| VCT 2025: Pacific Stage 2 | 12 | Paper Rex | 25.6% | Paper Rex |
| VCT 2025: EMEA Stage 2 | 12 | Team Liquid | 15.3% | FNATIC |
| VCT 2025: Americas Stage 2 | 13 | G2 Esports | 37.1% | G2 Esports |
| Valorant Champions 2025 | 16 | NRG | 9.6% | G2 Esports |
| Valorant Masters Santiago 2026 | 12 | Nongshim RedForce | 19.3% | G2 Esports |
| VCT 2026: China Stage 1 | 14 | EDward Gaming | 13.3% | Xi Lai Gaming |
| VCT 2026: EMEA Stage 1 | 12 | Team Heretics | 7.4% | Gentle Mates |
| VCT 2026: Pacific Stage 1 | 12 | Paper Rex | 17.3% | Nongshim RedForce |
| VCT 2026: Americas Stage 1 | 12 | G2 Esports | 34.6% | G2 Esports |
| Valorant Masters London 2026 | 12 | LEVIATÁN | 8.5% | Paper Rex |

## How it works

1. **Player ratings, updated on round margins.** Every player has a skill estimate with an uncertainty attached. After each map, the round score (for example 13–8) updates everyone who played. A team's strength is the average of its five current players, so roster moves, loans and rebrands carry the right history with them.
2. **Map offsets and region offsets.** Each team has its own adjustment per map. Each region has a strength offset that only cross-region matches can move, i.e. Masters and Champions.
3. **Round → map → series.** The round-win edge becomes a map-win probability through the exact race-to-13 formula with win-by-two overtime. Maps are then combined into Bo1/Bo3/Bo5 odds with a state-by-state calculation. Uncertainty about the rosters is averaged over with Gauss–Hermite quadrature, so a barely known lineup gives more cautious odds.
4. **Veto simulation.** Each team's recent pick and ban habits drive a Monte-Carlo simulation of the actual VCT veto format. The series odds are averaged over the maps likely to be played.
5. **Events.** vlr.gg doesn't publish how brackets are wired, so the wiring is read from finished events: a team's previous match shows which slot feeds which. Group stages, Swiss stages and brackets are then played out thousands of times with the match model, keeping every result already in.

### Tried and rejected

Each idea was judged on pre-2025 data only, and kept only if it helped there.

- **A team-level rating on top of the players** (coaching, teamwork): 200 tuning trials never beat the players-only model's pre-2025 log-loss (0.6541). The player ratings already carry it.
- **Stacking a separate team Elo into the calibration layer:** no measurable change on the 2024 validation year, so it was dropped.
- **A calibration layer** (a regularised logistic regression adding form, head-to-head, rest days and the stage on top of the ratings) made no difference on the 2024 validation year. On the test years it was slightly worse: 61.2% accuracy against 61.8%, and 74.4% against 79.5% on 70%+ picks. The ratings are well calibrated on their own, so the layer was removed.

### Limits

- Pro VCT is very even by design: over half of all series are forecast between 50% and 60%, and those are close to coin flips for any model.
- Bookmakers do better on the matches where their prices survive. They see things this model can't, such as stand-ins, illness and scrim results.
- Lineups are taken from each team's latest match, so a surprise substitution isn't known until the next match is played.
- Event forecasts need every bracket shape to have been played out before. A format that's new this year (every 2026 Kickoff, the 2026 Stage 2 play-ins) gets no forecast until one has finished, and the app says so.
- "Qualifies" means qualifying by placing at that event. Champions spots earned through season circuit points aren't simulated.

### Tuned settings (fitted before 2025-01-01 only)

```
roundWeight=0.2899046284640457
playerSd=0.6012877324658921
newcomerMean=-0.030153783963583727
driftPerDay=0.006213851566607335
seasonSd=0.06005594494353522
mapSd=0.1611306830267037
mapDriftPerDay=0.009715596385680059
regionSd=0.31716571242461056
regionDriftPerDay=0.003278030513660355
mapTemperature=0.4733081790516611
agentSd=0.0
comfortSd=0.0
agentDriftPerDay=0.00730568597744227
```
