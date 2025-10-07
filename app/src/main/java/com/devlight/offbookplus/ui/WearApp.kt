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
import com.devlight.offbookplus.ui.screen.LibraryScreen
import com.devlight.offbookplus.ui.screen.PlayerScreen
import com.devlight.offbookplus.ui.screen.SettingsScreen
import com.devlight.offbookplus.ui.screen.HomeScreen
import com.devlight.offbookplus.ui.theme.AudiobookAppTheme
import com.devlight.offbookplus.ui.viewmodel.LibraryViewModel
import java.net.URLEncoder

@Composable
fun WearApp() {
    AudiobookAppTheme {
        val navController = rememberSwipeDismissableNavController()
        val libraryViewModel: LibraryViewModel = viewModel()

        SwipeDismissableNavHost(
            navController = navController,
            startDestination = NavRoutes.HOME_ROUTE,
            modifier = Modifier.background(MaterialTheme.colorScheme.background)
        ) {
            composable(route = NavRoutes.HOME_ROUTE) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) }
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
                        val encodedmediaId = URLEncoder.encode(mediaId, "UTF-8")
                        val route = NavRoutes.PLAYER_ROUTE_TEMPLATE
                            .replace("{mediaId}", encodedmediaId)
                            .replace("{mediaType}", mediaTypeForNav.name)
                        navController.navigate(route)
                    },
                    libraryViewModel = libraryViewModel
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
                    onBack = { navController.popBackStack() }
                )
            }
            composable(route = NavRoutes.SETTINGS_ROUTE) {
                SettingsScreen(
                    // Pass the same shared ViewModel instance here as well.
                    viewModel = libraryViewModel
                )
            }
        }
    }
}


object NavRoutes {
    const val HOME_ROUTE = "home"
    const val LIBRARY_ROUTE_TEMPLATE = "library/{mediaType}"
    const val PLAYER_ROUTE_TEMPLATE = "player/{mediaId}/{mediaType}"
    const val CHAPTERS_ROUTE = "chapters"
    const val SETTINGS_ROUTE = "settings"
}