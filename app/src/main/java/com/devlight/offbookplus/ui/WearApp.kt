package com.devlight.offbookplus.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
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
import kotlinx.coroutines.launch

@Composable
fun WearApp(startAtPlayer: Boolean = false) {
    AudiobookAppTheme {
        val navController = rememberSwipeDismissableNavController()
        val libraryViewModel: LibraryViewModel = viewModel()
        val playbackViewModel: PlaybackViewModel = viewModel()
        val updatesViewModel: UpdatesViewModel = viewModel()

        // Pulling down past the top of any list drags the screen with the finger;
        // releasing past the threshold slides open the player. The indicator pill at
        // the top brightens while pulling to hint the gesture.
        val scope = rememberCoroutineScope()
        val maxPullPx = with(LocalDensity.current) { 48.dp.toPx() }
        val triggerPx = maxPullPx * 0.75f
        var pullPx by remember { mutableFloatStateOf(0f) }
        var fired by remember { mutableStateOf(false) }

        var currentRoute by remember { mutableStateOf<String?>(null) }
        DisposableEffect(navController) {
            val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                currentRoute = destination.route
            }
            navController.addOnDestinationChangedListener(listener)
            onDispose { navController.removeOnDestinationChangedListener(listener) }
        }
        val indicatorVisible = when (currentRoute) {
            NavRoutes.PLAYER_ROUTE, NavRoutes.SPEED_CONTROL_ROUTE, NavRoutes.UPDATES_ROUTE -> false
            else -> true
        }

        val pullDownConnection = remember(navController, maxPullPx, scope) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    if (source == NestedScrollSource.UserInput && !fired) {
                        if (available.y > 0f || (available.y < 0f && pullPx > 0f)) {
                            pullPx = (pullPx + available.y * 0.6f).coerceIn(0f, maxPullPx)
                            if (pullPx >= triggerPx) {
                                fired = true
                            }
                        }
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    val release = fired
                    fired = false
                    if (release) {
                        navController.navigate(NavRoutes.PLAYER_ROUTE)
                    }
                    if (pullPx > 0f) {
                        val start = pullPx
                        scope.launch {
                            animate(
                                initialValue = start,
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f)
                            ) { v, _ -> pullPx = v }
                        }
                    }
                    return Velocity.Zero
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = NavRoutes.HOME_ROUTE,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = pullPx }
                        .background(MaterialTheme.colorScheme.background)
                        .nestedScroll(pullDownConnection)
                ) {
            composable(route = NavRoutes.HOME_ROUTE) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    playbackViewModel = playbackViewModel
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
                    onItemClick = { mediaId, mediaTypeForPlay ->
                        // Playback is triggered here (single source of truth); the
                        // player screen only displays/controls the active session.
                        playbackViewModel.playMediaItem(mediaId, mediaTypeForPlay)
                        navController.navigate(NavRoutes.PLAYER_ROUTE)
                    },
                    onNavigateToPlayer = { navController.navigate(NavRoutes.PLAYER_ROUTE) },
                    libraryViewModel = libraryViewModel,
                    playbackViewModel = playbackViewModel
                )
            }

            composable(route = NavRoutes.PLAYER_ROUTE) {
                PlayerScreen(
                    onNavigateToSpeedControl = { navController.navigate(NavRoutes.SPEED_CONTROL_ROUTE) },
                    viewModel = playbackViewModel
                )
            }
            composable(route = NavRoutes.SPEED_CONTROL_ROUTE) {
                SpeedControlScreen(viewModel = playbackViewModel)
            }
            composable(route = NavRoutes.SETTINGS_ROUTE) {
                SettingsScreen(
                    viewModel = libraryViewModel,
                    playbackViewModel = playbackViewModel,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(route = NavRoutes.HISTORY_ROUTE) {
                HistoryScreen(
                    onPlayMedia = { mediaId, mediaType ->
                        playbackViewModel.playMediaItem(mediaId, mediaType)
                        navController.navigate(NavRoutes.PLAYER_ROUTE)
                    }
                )
            }
            composable(route = NavRoutes.UPDATES_ROUTE) {
                UpdatesScreen(viewModel = updatesViewModel)
            }
                }
            }

            if (indicatorVisible) {
                val pullFraction = if (maxPullPx > 0f) pullPx / maxPullPx else 0f
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 30.dp)
                        .width(width = 34.dp)
                        .height(height = 3.dp)
                        .graphicsLayer {
                            scaleX = 1f + 0.9f * pullFraction
                            alpha = 0.85f
                        }
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
            }

        // Tapping the system media notification re-opens the app straight at the player.
        LaunchedEffect(startAtPlayer) {
            if (startAtPlayer) {
                navController.navigate(NavRoutes.PLAYER_ROUTE)
            }
        }
        }
    }
}


object NavRoutes {
    const val HOME_ROUTE = "home"
    const val LIBRARY_ROUTE_TEMPLATE = "library/{mediaType}"
    const val PLAYER_ROUTE = "player"
    const val SPEED_CONTROL_ROUTE = "speed_control"
    const val CHAPTERS_ROUTE = "chapters"
    const val SETTINGS_ROUTE = "settings"
    const val HISTORY_ROUTE = "history"
    const val UPDATES_ROUTE = "updates"
}
