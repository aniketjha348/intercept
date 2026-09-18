package com.intercept.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.intercept.di.AppContainer
import com.intercept.presentation.analyze.AnalyzeScreen
import com.intercept.presentation.home.HomeScreen
import com.intercept.presentation.incoming.IncomingCallScreen
import com.intercept.presentation.live.LiveCallScreen
import com.intercept.presentation.reports.ReportsScreen
import com.intercept.presentation.settings.SettingsScreen
import com.intercept.presentation.setup.SetupScreen

object Routes {
    const val HOME = "home"
    const val INCOMING = "incoming"
    const val ANALYZE = "analyze"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val LIVE = "live/{sid}"
    fun live(sid: String) = "live/$sid"
}

@Composable
fun NavGraph(container: AppContainer, startAtIncoming: Boolean, startAtAnalyze: Boolean = false) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (startAtIncoming) Routes.INCOMING
        else if (startAtAnalyze) Routes.ANALYZE else Routes.HOME
    ) {
        composable(Routes.HOME) { HomeScreen(nav, container) }
        composable(Routes.INCOMING) { IncomingCallScreen(nav, container) }
        composable(
            Routes.LIVE,
            arguments = listOf(navArgument("sid") { type = NavType.StringType })
        ) { backStack ->
            LiveCallScreen(nav, container, backStack.arguments?.getString("sid").orEmpty())
        }
        composable(Routes.SETUP) { SetupScreen(nav, container) }
        composable(Routes.ANALYZE) { AnalyzeScreen(nav, container) }
        composable(Routes.REPORTS) { ReportsScreen(nav, container) }
        composable(Routes.SETTINGS) { SettingsScreen(nav, container) }
    }
}
