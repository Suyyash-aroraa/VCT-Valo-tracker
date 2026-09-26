# Match predictor backtest

_Generated 2026-09-26 by `./gradlew :trainer:run --args="train"`._

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
| Bookmaker odds | 123 | 66.7% | 66.7% | 0.582 | 0.626 |

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
| Bookmaker odds | 123 | 29 | 23.6% | 93.1% |
| Model, same series as bookmakers | 123 | 10 | 8.1% | 90.0% |

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

## How it works

1. **Player ratings, updated on round margins.** Every player has a skill estimate with an uncertainty attached. After each map, the round score (for example 13–8) updates everyone who played. A team's strength is the average of its five current players, so roster moves, loans and rebrands carry the right history with them.
2. **Map offsets and region offsets.** Each team has its own adjustment per map. Each region has a strength offset that only cross-region matches can move, i.e. Masters and Champions.
3. **Round → map → series.** The round-win edge becomes a map-win probability through the exact race-to-13 formula with win-by-two overtime. Maps are then combined into Bo1/Bo3/Bo5 odds with a state-by-state calculation. Uncertainty about the rosters is averaged over with Gauss–Hermite quadrature, so a barely known lineup gives more cautious odds.
4. **Veto simulation.** Each team's recent pick and ban habits drive a Monte-Carlo simulation of the actual VCT veto format. The series odds are averaged over the maps likely to be played.

### Tried and rejected

Each idea was judged on pre-2025 data only, and kept only if it helped there.

- **A team-level rating on top of the players** (coaching, teamwork): 200 tuning trials never beat the players-only model's pre-2025 log-loss (0.6541). The player ratings already carry it.
- **Stacking a separate team Elo into the calibration layer:** no measurable change on the 2024 validation year, so it was dropped.
- **A calibration layer** (a regularised logistic regression adding form, head-to-head, rest days and the stage on top of the ratings) made no difference on the 2024 validation year. On the test years it was slightly worse: 61.2% accuracy against 61.8%, and 74.4% against 79.5% on 70%+ picks. The ratings are well calibrated on their own, so the layer was removed.

### Limits

- Pro VCT is very even by design: over half of all series are forecast between 50% and 60%, and those are close to coin flips for any model.
- Bookmakers do better on the matches where their prices survive. They see things this model can't, such as stand-ins, illness and scrim results.
- Lineups are taken from each team's latest match, so a surprise substitution isn't known until the next match is played.

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
```
