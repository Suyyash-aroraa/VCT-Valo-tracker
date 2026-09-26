package com.vcttracker.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vcttracker.ui.components.LocalNavigator
import com.vcttracker.ui.components.Navigator
import com.vcttracker.ui.screens.EventScreen
import com.vcttracker.ui.screens.EventsScreen
import com.vcttracker.ui.screens.HistoryScreen
import com.vcttracker.ui.screens.MatchScreen
import com.vcttracker.ui.screens.ModelScreen
import com.vcttracker.ui.screens.PlayerScreen
import com.vcttracker.ui.screens.StandingsScreen
import com.vcttracker.ui.screens.TeamScreen
import com.vcttracker.ui.screens.TodayScreen
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct
import com.vcttracker.ui.theme.VctIcons
import java.time.LocalDate
import java.util.Locale

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("today", "Today", VctIcons.Today),
    Tab("events", "Events", VctIcons.Events),
    Tab("standings", "Points", VctIcons.Standings),
    Tab("history", "History", VctIcons.History),
)

/** The franchised VCT era on vlr.gg starts in 2023. */
private val SEASONS: List<Int> = (LocalDate.now().year downTo 2023).toList()

@Composable
fun VctAppUi() {
    val nav = rememberNavController()
    val navigator = remember(nav) { AppNavigator(nav) }
    var season by rememberSaveable { mutableIntStateOf(SEASONS.first()) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val c = Vct.colors

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Column(Modifier.fillMaxSize().background(c.background)) {
            Box(Modifier.weight(1f)) {
                NavHost(
                    navController = nav,
                    startDestination = "today",
                    enterTransition = { if (targetState.destination.route in TABS.map { it.route }) fadeIn(tween(160)) else slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(120)) },
                    popEnterTransition = { fadeIn(tween(160)) },
                    popExitTransition = { slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(200)) },
                ) {
                    composable("today") { TodayScreen() }
                    composable("events") { EventsScreen(season, SEASONS) { season = it } }
                    composable("standings") { StandingsScreen(season, SEASONS) { season = it } }
                    composable("history") { HistoryScreen() }
                    composable("event/{id}") { EventScreen(it.arguments?.getString("id").orEmpty()) }
                    composable("match/{id}") { MatchScreen(it.arguments?.getString("id").orEmpty()) }
                    composable("team/{id}") { TeamScreen(it.arguments?.getString("id").orEmpty()) }
                    composable("player/{id}") { PlayerScreen(it.arguments?.getString("id").orEmpty()) }
                    composable("model") { ModelScreen() }
                }
            }
            BottomBar(route) { tab ->
                nav.navigate(tab.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
}

@Composable
private fun BottomBar(route: String?, onSelect: (Tab) -> Unit) {
    val c = Vct.colors
    // Detail screens keep the tab of wherever they were opened from highlighted.
    var lastTab by rememberSaveable { mutableIntStateOf(0) }
    val idx = TABS.indexOfFirst { it.route == route }
    if (idx >= 0) lastTab = idx
    Column(Modifier.fillMaxWidth().background(c.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            TABS.forEachIndexed { i, tab ->
                val on = i == lastTab
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .selectable(selected = on, role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(if (on) 22.dp else 0.dp).height(3.dp).background(c.spike, ChamferSmall))
                    Spacer(Modifier.height(10.dp))
                    Icon(tab.icon, contentDescription = null, tint = if (on) c.ink else c.faint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label.uppercase(Locale.getDefault()), style = Vct.type.label, color = if (on) c.ink else c.faint)
                }
            }
        }
    }
}

private class AppNavigator(private val nav: NavHostController) : Navigator {
    override fun event(id: String) = nav.navigate("event/$id")
    override fun match(id: String) = nav.navigate("match/$id")
    override fun team(id: String?) { if (id != null) nav.navigate("team/$id") }
    override fun player(id: String?) { if (id != null) nav.navigate("player/$id") }
    override fun model() = nav.navigate("model")
    override fun back() { nav.popBackStack() }
}
