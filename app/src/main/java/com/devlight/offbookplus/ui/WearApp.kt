package com.devlight.offbookplus.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.devlight.offbookplus.model.MediaType
import com.devlight.offbookplus.ui.screen.HomeScreen
import com.devlight.offbookplus.ui.screen.HistoryScreen
import com.devlight.offbookplus.ui.screen.LibraryScreen
import com.devlight.offbookplus.ui.screen.PlayerScreen
import com.devlight.offbookplus.ui.screen.SettingsScreen
import com.devlight.offbookplus.ui.screen.SpeedControlScreen
import com.devlight.offbookplus.ui.screen.UpdatesScreen
import com.devlight.offbookplus.ui.theme.AudiobookAppTheme
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel
import com.devlight.offbookplus.ui.viewmodel.PlaybackViewModel
import com.devlight.offbookplus.ui.viewmodel.UpdatesViewModel
import java.net.URLEncoder

@Composable
fun WearApp() {
    AudiobookAppTheme {
        val navController = rememberSwipeDismissableNavController()
        val libraryViewModel: LibraryViewModel = viewModel()
        val playbackViewModel: PlaybackViewModel = viewModel()
        val updatesViewModel: UpdatesViewModel = viewModel()

        SwipeDismissableNavHost(
            navController = navController,
            startDestination = NavRoutes.HOME_ROUTE,
            modifier = Modifier.background(MaterialTheme.colorScheme.background)
        ) {
            composable(route = NavRoutes.HOME_ROUTE) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    playbackViewModel = playbackViewModel,
                    onOpenNowPlaying = {
                        navController.navigate(NavRoutes.playerRouteForCurrent(playbackViewModel))
                    }
                )
            }

            composable(
                route = NavRoutes.LIBRARY_ROUTE_TEMPLATE,
                arguments = listOf(navArgument("mediaType") { type = NavType.StringType })
            ) { backStackEntry ->
                val mediaTypeString = backStackEntry.arguments?.getString("mediaType")
                val mediaType = try { MediaType.valueOf(mediaTypeString ?: "AUDIOBOOKS") } catch (e: IllegalArgumentException) { MediaType.AUDIOBOOKS }

                LibraryScreen(
                    mediaType = mediaType,
                    onItemClick = { mediaId, mediaTypeForNav ->
                        navController.navigate(NavRoutes.playerRoute(mediaId, mediaTypeForNav))
                    },
                    libraryViewModel = libraryViewModel,
                    playbackViewModel = playbackViewModel
                )
            }

            composable(
                route = NavRoutes.PLAYER_ROUTE_TEMPLATE,
                arguments = listOf(
                    navArgument("mediaId") { type = NavType.StringType },
                    navArgument("mediaType") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val mediaId = backStackEntry.arguments?.getString("mediaId")
                val mediaTypeString = backStackEntry.arguments?.getString("mediaType")
                val mediaType = try { MediaType.valueOf(mediaTypeString ?: "AUDIOBOOKS") } catch (e: IllegalArgumentException) { MediaType.AUDIOBOOKS }

                PlayerScreen(
                    mediaId = mediaId,
                    mediaType = mediaType,
                    onNavigateToSpeedControl = { navController.navigate(NavRoutes.SPEED_CONTROL_ROUTE) },
                    onBack = { navController.popBackStack() },
                    viewModel = playbackViewModel
                )
            }
            composable(route = NavRoutes.SPEED_CONTROL_ROUTE) {
                SpeedControlScreen()
            }
            composable(route = NavRoutes.SETTINGS_ROUTE) {
                SettingsScreen(
                    // Pass the same shared ViewModel instance here as well.
                    viewModel = libraryViewModel,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(route = NavRoutes.HISTORY_ROUTE) {
                HistoryScreen(
                    onPlayMedia = { mediaId, mediaType ->
                        navController.navigate(NavRoutes.playerRoute(mediaId, mediaType))
                    }
                )
            }
            composable(route = NavRoutes.UPDATES_ROUTE) {
                UpdatesScreen(viewModel = updatesViewModel)
            }
        }
    }
}


object NavRoutes {
    const val HOME_ROUTE = "home"
    const val LIBRARY_ROUTE_TEMPLATE = "library/{mediaType}"
    const val PLAYER_ROUTE_TEMPLATE = "player/{mediaId}/{mediaType}"
    const val SPEED_CONTROL_ROUTE = "speed_control"
    const val CHAPTERS_ROUTE = "chapters"
    const val SETTINGS_ROUTE = "settings"
    const val HISTORY_ROUTE = "history"
    const val UPDATES_ROUTE = "updates"

    fun playerRoute(mediaId: String, mediaType: MediaType): String {
        val encodedId = URLEncoder.encode(mediaId, "UTF-8")
        return PLAYER_ROUTE_TEMPLATE
            .replace("{mediaId}", encodedId)
            .replace("{mediaType}", mediaType.name)
    }

    fun playerRouteForCurrent(playbackViewModel: PlaybackViewModel): String {
        val state = playbackViewModel.playbackState.value
        return playerRoute(state.mediaId, state.mediaType)
    }
}