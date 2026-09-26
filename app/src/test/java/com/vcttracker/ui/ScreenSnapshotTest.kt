package com.vcttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.vcttracker.data.LiquipediaParser
import com.vcttracker.data.Loaded
import com.vcttracker.data.MatchDay
import com.vcttracker.data.TodayData
import com.vcttracker.data.VlrParser
import com.vcttracker.data.logoMap
import com.vcttracker.model.forecast
import com.vcttracker.data.withLogos
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.Navigator
import com.vcttracker.ui.screens.EventBody
import com.vcttracker.ui.screens.EventsList
import com.vcttracker.ui.screens.HistoryBody
import com.vcttracker.ui.screens.MatchBody
import com.vcttracker.ui.screens.PlayerBody
import com.vcttracker.ui.screens.StandingsBody
import com.vcttracker.ui.screens.TeamBody
import com.vcttracker.ui.screens.TodayBody
import com.vcttracker.ui.theme.Vct
import com.vcttracker.ui.theme.VctTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Renders each screen from saved vlr.gg / Liquipedia pages so layout regressions
 * show up without a device. Run `./gradlew recordPaparazziDebug` to refresh images.
 */
class ScreenSnapshotTest {
    private val FETCHED = Instant.parse("2026-09-26T20:12:00Z")

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 4200, softButtons = false),
        maxPercentDifference = 0.5,
    )

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun <T> loaded(v: T) = Loaded(v, Instant.parse("2026-09-26T20:00:00Z"), offline = false)

    private val noNav = object : Navigator {
        override fun event(id: String) = Unit
        override fun match(id: String) = Unit
        override fun team(id: String?) = Unit
        override fun player(id: String?) = Unit
        override fun model() = Unit
        override fun back() = Unit
    }

    private fun shot(dark: Boolean = false, content: @Composable () -> Unit) {
        if (dark) paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 4200, softButtons = false, nightMode = NightMode.NIGHT))
        paparazzi.snapshot {
            VctTheme(dark = dark) {
                CompositionLocalProvider(LocalNavigator provides noNav) {
                    Box(Modifier.fillMaxSize().background(Vct.colors.background)) { content() }
                }
            }
        }
    }

    private fun todayData(): TodayData {
        val champs = VlrParser.parseEvent(fixture("event_champions.html"), "2766")
        fun List<MatchDay>.vct() = mapNotNull { d ->
            d.matches.filter { VlrParser.isVctEvent(it.eventName) }.takeIf { it.isNotEmpty() }?.let { d.copy(matches = it) }
        }
        return TodayData(
            season = VlrParser.parseSeason(fixture("vct.html"), 2026),
            featured = champs,
            upcoming = VlrParser.parseMatchList(fixture("matches.html"), FETCHED).vct().withLogos(champs.logoMap()),
            results = VlrParser.parseMatchList(fixture("results.html"), FETCHED).vct().withLogos(champs.logoMap()),
        )
    }

    @Test fun today() = shot { TodayBody(loaded(todayData()), Instant.parse("2026-09-26T20:00:00Z")) }

    @Test fun todayDark() = shot(dark = true) { TodayBody(loaded(todayData()), Instant.parse("2026-09-26T20:00:00Z")) }

    @Test fun events() = shot { EventsList(loaded(VlrParser.parseSeason(fixture("vct.html"), 2026)), null) }

    @Test fun eventBracket() = shot {
        EventBody("2766", loaded(VlrParser.parseEvent(fixture("event_champions.html"), "2766")), 0, {}, {})
    }

    @Test fun eventGroups() = shot {
        EventBody("2977", loaded(VlrParser.parseEvent(fixture("event_groups.html"), "2977")), 0, {}, {})
    }

    @Test fun eventPrizes() = shot {
        EventBody("2977", loaded(VlrParser.parseEvent(fixture("event_playoffs.html"), "2977")), 2, {}, {})
    }

    @Test fun eventTeams() = shot {
        EventBody("2766", loaded(VlrParser.parseEvent(fixture("event_champions.html"), "2766")), 3, {}, {})
    }

    @Test fun match() = shot { MatchBody(loaded(VlrParser.parseMatch(fixture("match.html"), "753450")), 1) {} }

    @Test fun matchDark() = shot(dark = true) { MatchBody(loaded(VlrParser.parseMatch(fixture("match.html"), "753450")), 1) {} }

    @Test fun standings() = shot { StandingsBody(loaded(VlrParser.parseStandings(fixture("standings.html"))), 0) {} }

    @Test fun team() = shot { TeamBody(loaded(VlrParser.parseTeam(fixture("team.html"), "1034"))) }

    @Test fun player() = shot { PlayerBody(loaded(VlrParser.parsePlayer(fixture("player.html"), "41224"))) }

    private val model by lazy { com.vcttracker.model.ModelIO.read(java.io.File("src/main/assets/model.json").readText()) }

    @Test fun matchForecast() = shot {
        val detail = VlrParser.parseMatch(fixture("match_upcoming.html"), "753444")
        MatchBody(loaded(detail), null, model.forecast(detail), model.report) {}
    }

    @Test fun modelScreen() = shot { com.vcttracker.ui.screens.ModelBody(model) }

    @Test fun history() = shot { HistoryBody(loaded(LiquipediaParser.parseHistory(fixture("liquipedia_vct.json")))) }
}
