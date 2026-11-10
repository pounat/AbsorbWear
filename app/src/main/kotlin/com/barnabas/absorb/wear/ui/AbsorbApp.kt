package com.barnabas.absorb.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.barnabas.absorb.wear.AbsorbWearApp
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.ui.screens.AwaitingCredentialsScreen
import com.barnabas.absorb.wear.ui.screens.BookDetailsScreen
import com.barnabas.absorb.wear.ui.screens.BookmarksScreen
import com.barnabas.absorb.wear.ui.screens.ChaptersScreen
import com.barnabas.absorb.wear.ui.screens.BookDetailsStore
import com.barnabas.absorb.wear.ui.screens.CardsHomeScreen
import com.barnabas.absorb.wear.ui.screens.EpisodesScreen
import com.barnabas.absorb.wear.ui.screens.PodcastShowStore
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.ui.screens.ListsScreen
import com.barnabas.absorb.wear.ui.screens.SOURCE_WATCH
import com.barnabas.absorb.wear.ui.screens.ManualLoginScreen
import com.barnabas.absorb.wear.ui.screens.SearchScreen
import com.barnabas.absorb.wear.ui.screens.SettingsScreen
import com.barnabas.absorb.wear.ui.screens.SleepTimerScreen
import com.barnabas.absorb.wear.ui.screens.ShelfScreen

/**
 * Root composable. Three top-level states:
 *  1. Signed in: nav host (the cards home, the watch player, the
 *     phone-remote player, a shelf, search, settings)
 *  2. Signed out, manual login chosen: ManualLoginScreen
 *  3. Signed out: AwaitingCredentialsScreen
 *
 * Screens read the playback state flows themselves rather than taking it
 * as a parameter, so a change recomposes the screen directly instead of
 * going through the navigation graph.
 */
@Composable
fun AbsorbApp() {
    val creds by CredentialStore.credentials.collectAsState()
    val ready by CredentialStore.ready.collectAsState()
    var manualLoginOpen by remember { mutableStateOf(false) }

    LaunchedEffect(creds) { if (creds != null) manualLoginOpen = false }

    AbsorbWearTheme {
        val c = creds
        when {
            // The splash screen covers this until the stored session is read.
            !ready -> Unit
            c != null -> SignedInNavHost(creds = c)
            manualLoginOpen -> ManualLoginScreen(onCancel = { manualLoginOpen = false })
            else -> AwaitingCredentialsScreen(onManualLogin = { manualLoginOpen = true })
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_SHELF = "shelf/{id}"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_BOOK = "book"
private const val ROUTE_EPISODES = "episodes"
private const val ROUTE_LISTS = "lists"
private const val ROUTE_CHAPTERS = "chapters/{source}"
private const val ROUTE_BOOKMARKS = "bookmarks/{source}"
private const val ROUTE_SLEEP = "sleep/{source}"

/**
 * The cards home is the root: the card of a book playing on the watch or
 * the phone is that player, so starting a book just brings its card
 * forward. Everything else is a swipe-dismissable destination above it.
 * Wear OS opening the app for the phone's media session lands back on
 * the cards, which show the phone's book.
 */
@Composable
private fun SignedInNavHost(creds: Credentials) {
    val navController = rememberSwipeDismissableNavController()
    val phoneRequest by AbsorbWearApp.phonePlayerRequests.collectAsState()

    LaunchedEffect(phoneRequest) {
        if (phoneRequest == 0L) return@LaunchedEffect
        if (navController.currentBackStackEntry?.destination?.route != ROUTE_HOME) {
            navController.popBackStack(ROUTE_HOME, inclusive = false)
        }
    }

    val backHome: () -> Unit = { navController.popBackStack(ROUTE_HOME, inclusive = false) }
    val openBook: (com.barnabas.absorb.wear.api.InProgressItem) -> Unit = { item ->
        if (item.isPodcastShow) {
            PodcastShowStore.item.value = item
            navController.navigate(ROUTE_EPISODES) { launchSingleTop = true }
        } else {
            BookDetailsStore.item.value = item
            navController.navigate(ROUTE_BOOK) { launchSingleTop = true }
        }
    }
    // The player's own book, as the details sheet wants it.
    val openWatchDetails = {
        val s = PlaybackManager.uiState.value
        val sel = PlaybackManager.selection.value
        val id = s.itemId ?: sel?.itemId
        if (id != null) {
            openBook(
                com.barnabas.absorb.wear.api.InProgressItem(
                    id = id,
                    media = com.barnabas.absorb.wear.api.InProgressItem.Media(
                        metadata = com.barnabas.absorb.wear.api.InProgressItem.Metadata(
                            title = s.title ?: sel?.title,
                            authorName = s.author ?: sel?.author,
                        ),
                        duration = s.durationMs / 1000.0,
                    ),
                ),
            )
        }
    }
    val openPhoneDetails = {
        val p = com.barnabas.absorb.wear.player.PlayerStateStore.state.value
        val id = p?.itemId
        if (p != null && id != null) {
            openBook(
                com.barnabas.absorb.wear.api.InProgressItem(
                    id = id,
                    media = com.barnabas.absorb.wear.api.InProgressItem.Media(
                        metadata = com.barnabas.absorb.wear.api.InProgressItem.Metadata(title = p.title, authorName = p.author),
                        duration = p.durationMs / 1000.0,
                    ),
                ),
            )
        }
    }

    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            CardsHomeScreen(
                creds = creds,
                onOpenShelf = { id -> navController.navigate("shelf/$id") { launchSingleTop = true } },
                onOpenLists = { navController.navigate(ROUTE_LISTS) { launchSingleTop = true } },
                onOpenSearch = { navController.navigate(ROUTE_SEARCH) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                onOpenChapters = { source -> navController.navigate("chapters/$source") { launchSingleTop = true } },
                onOpenBookmarks = { source -> navController.navigate("bookmarks/$source") { launchSingleTop = true } },
                onOpenWatchDetails = openWatchDetails,
                onOpenPhoneDetails = openPhoneDetails,
                onOpenSleep = { source -> navController.navigate("sleep/$source") { launchSingleTop = true } },
            )
        }
        composable(ROUTE_CHAPTERS) { entry ->
            ChaptersScreen(
                creds = creds,
                source = entry.arguments?.getString("source") ?: SOURCE_WATCH,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_BOOKMARKS) { entry ->
            BookmarksScreen(
                creds = creds,
                source = entry.arguments?.getString("source") ?: SOURCE_WATCH,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_SLEEP) { entry ->
            SleepTimerScreen(
                creds = creds,
                source = entry.arguments?.getString("source") ?: SOURCE_WATCH,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_SHELF) { entry ->
            ShelfScreen(creds = creds, shelfId = entry.arguments?.getString("id").orEmpty(), onOpenBook = openBook)
        }
        composable(ROUTE_SEARCH) {
            SearchScreen(creds = creds, onOpenBook = openBook)
        }
        composable(ROUTE_LISTS) {
            ListsScreen(creds = creds, onOpenShelf = { id -> navController.navigate("shelf/$id") { launchSingleTop = true } })
        }
        composable(ROUTE_EPISODES) {
            EpisodesScreen(creds = creds, onOpenEpisode = openBook)
        }
        composable(ROUTE_BOOK) {
            BookDetailsScreen(
                creds = creds,
                onOpenPlayer = backHome,
                onOpenPhonePlayer = backHome,
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                creds = creds,
                onOpenDownloads = { navController.navigate("shelf/kept") { launchSingleTop = true } },
            )
        }
    }
}
