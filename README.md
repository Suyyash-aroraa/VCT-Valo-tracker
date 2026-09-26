# VCT Tracker

An Android app for following the whole VALORANT Champions Tour: all four regional leagues (Americas, EMEA, Pacific, China), both Masters events, and Champions.

The app has no backend. It downloads pages from [vlr.gg](https://www.vlr.gg/vct) and [Liquipedia](https://liquipedia.net/valorant/VALORANT_Champions_Tour), parses them on the phone, and shows the results in its own interface.

![Today, round-by-round stats, and dark mode](docs/screenshots/showcase.png)

## Install

Every push to `main` builds a new APK and publishes it on the [Releases page](../../releases/latest). Download `VCT-Tracker-1.0.N.apk` on your phone, open it, and allow installs from your browser when Android asks. You need Android 8.0 or newer.

## What's in it

**Today**
- The event that's on right now, with its stage, prize pool and how many days are left.
- The **Season Rail**, which shows the year's circuit in order: Kickoff → Masters → Stage 1 → Masters → Stage 2 → Champions. The current stop is marked. Tap a regional stop to see its four leagues.
- Live matches, which refresh every 30 seconds.
- Upcoming matches, grouped by day and shown in your local time with a countdown.
- Recent results. Only VCT matches are listed; Challengers and other events are filtered out.

**Events**
- Every VCT event for 2023–2026, filterable by region and grouped by stage.
- Brackets drawn with connector lines, for both upper and lower brackets.
- Group tables with W–L record, maps and round difference.
- Each event's full match list, prize distribution with circuit points, and team rosters.

**Match**
- A scoreboard header, the map veto (bans shown struck through, picks filled in), and a list of maps with who picked each one.
- A **round strip** for each map. The winning cell of every round is coloured by side (amber = attack, teal = defense) and marked with how the round ended: elimination, spike detonation, defuse or time.
- A player stats table (R, ACS, K/D/A, KAST, ADR, HS%, FK/FD) that you can switch between both sides, attack only and defense only. The top performer is highlighted.
- Stream and VOD links that open in Twitch or YouTube.

**Points**: circuit-point standings for each region, with the teams that qualified for Champions marked.

**History**: a hall of champions built from Liquipedia. Every Masters and Champions winner since 2021, plus the regional winners for each year.

**Team and player pages**: roster with photos, staff, last-five form, upcoming matches, results, agent stats and team history.

The app also:
- Follows the system light or dark theme.
- Works offline: every page is cached, and the app tells you when it's showing saved data.
- Supports pull-to-refresh.

<p>
<img src="docs/screenshots/today-matches.png" width="32%">
<img src="docs/screenshots/bracket.png" width="32%">
<img src="docs/screenshots/match.png" width="32%">
</p>
<p>
<img src="docs/screenshots/groups.png" width="32%">
<img src="docs/screenshots/standings.png" width="32%">
<img src="docs/screenshots/history.png" width="32%">
</p>

## Design

The colours come from VALORANT itself:

| Colour | Hex | Used for |
|---|---|---|
| Bone | `#ECE8E1` | Light background ("paper") |
| Ink | `#0F1923` | Text; dark background |
| Spike red | `#FF4655` | Only "live" and "won" |
| Amber / teal | – | Only attack / defense |

Fonts:
- **Big Shoulders Display**: scores and titles (condensed, like a broadcast scorebug).
- **Barlow**: body text.
- **IBM Plex Mono**: labels and timestamps.

Panels have a clipped corner that echoes the in-game HUD. All icons are drawn for the app; it uses no emoji and no generic icon pack.

## Build it yourself

```bash
./gradlew :app:assembleRelease       # APK in app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest     # parser tests + screen renders
./gradlew :app:recordPaparazziDebug  # render every screen to app/src/test/snapshots/
```

You need JDK 17 and the Android SDK (platform 35).

### Signing

By default, CI signs the APK with a cached debug key, so each new build installs over the previous one. To sign with your own key, add these repository secrets:

- `VCT_KEYSTORE_BASE64`: your `.jks` file, base64-encoded
- `VCT_KEYSTORE_PASSWORD`
- `VCT_KEY_ALIAS`
- `VCT_KEY_PASSWORD`

## How it works

```
app/src/main/java/com/vcttracker/
├── data/
│   ├── VlrParser.kt          vlr.gg HTML → models (season, match lists, events, brackets, matches, standings, teams, players)
│   ├── LiquipediaParser.kt   Liquipedia parse API → tournament history
│   ├── Repository.kt         OkHttp + memory/disk cache, offline fallback, Liquipedia rate limiting
│   └── Logos.kt              adds team logos to match lists (vlr's lists don't include them)
└── ui/
    ├── theme/                colours, type, chamfer shape, icon set
    ├── components/           Season Rail, bracket renderer, round strip, match row, loaders
    └── screens/              Today, Events, Event, Match, Points, History, Team, Player
```

- Every parser is a pure function and is tested against real pages saved in `app/src/test/resources`. If vlr.gg changes its markup, the failing test points at the section that broke.
- **Time zones.** vlr.gg writes match-list times in US Central time. The app converts them to your time zone. It also checks those times against vlr's own countdowns, so if the site ever renders another zone, the app notices and corrects for it.
- **Liquipedia rules.** The app follows Liquipedia's API terms: it sends a descriptive User-Agent, uses gzip, makes at most one parse request every 30 seconds, and caches results for 12 hours.

## Credits

Match data comes from [vlr.gg](https://www.vlr.gg). Tournament history comes from [Liquipedia](https://liquipedia.net/valorant), licensed under [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/). VALORANT and the VALORANT Champions Tour are trademarks of Riot Games. This is an unofficial fan project and is not affiliated with Riot Games, vlr.gg or Liquipedia.
