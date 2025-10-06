package com.devlight.offbookplus.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.devlight.offbookplus.ui.screen.LibraryScreen
import com.devlight.offbookplus.ui.screen.PlayerScreen
import com.devlight.offbookplus.ui.theme.AudiobookAppTheme

@Composable
fun WearApp() {
    AudiobookAppTheme {
        val navController = rememberSwipeDismissableNavController()

        // We are using the standard Wear Compose NavHost directly,
        // as the Horologist AppScaffold handles the overall framework.
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = NavRoutes.LIBRARY_ROUTE,
            modifier = Modifier.background(MaterialTheme.colorScheme.background)
        ) {

            // 1. Library Screen
            composable(route = NavRoutes.LIBRARY_ROUTE) {
                LibraryScreen(
                    onBookClick = { bookId ->
                        // Navigation logic to be filled in Stage 3
                        navController.navigate("${NavRoutes.PLAYER_ROUTE}/$bookId")
                    }
                )
            }

            composable(
                route = "${NavRoutes.PLAYER_ROUTE}/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId")
                // Placeholder until the screen is created
                PlayerScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

object NavRoutes {
    const val LIBRARY_ROUTE = "library"
    const val PLAYER_ROUTE = "player"
    const val CHAPTERS_ROUTE = "chapters"
}