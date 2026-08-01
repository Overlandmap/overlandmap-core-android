package ch.overlandmap.map.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import ch.overlandmap.map.OverlandApp
import kotlinx.coroutines.flow.first

private const val TAG = "LastRoute"

/**
 * Restores the screen the user last had open across process death and keeps it
 * saved. Call it once, as a sibling of the NavHost. It first waits for the
 * graph's start destination to land on the back stack (so navigating to the
 * restored route can't race the NavHost setting itself up), navigates to the
 * persisted route once, then writes every later destination change to DataStore.
 * Only the top screen is restored, not the whole back stack, and it degrades
 * gracefully if that route no longer exists (e.g. a deleted pack).
 */
@Composable
fun RestoreAndPersistLastRoute(navController: NavController) {
    val app = (LocalContext.current.applicationContext as OverlandApp)
    val prefs = app.userPreferences
    LaunchedEffect(navController) {
        // Suspends until the NavHost has set the graph and pushed its start
        // destination — then it is safe to navigate.
        val start = navController.currentBackStackEntryFlow.first()
        val saved = prefs.lastRouteNow()
        Log.d(TAG, "start=${start.fullRoute()} saved=$saved")
        if (!saved.isNullOrBlank() && saved != start.fullRoute()) {
            // Validate that the referenced object still exists in the local DB.
            // If it doesn't (e.g. after a DB wipe), skip the restore.
            if (!isRouteValid(app, saved)) {
                Log.w(TAG, "saved route '$saved' references a missing object, skipping restore")
                prefs.clearLastRoute()
            } else {
                // Hand the itinerary screen its saved tab/step/camera to reapply once.
                if (saved.startsWith("itinerary/")) {
                    val docId = saved.removePrefix("itinerary/").substringBefore("?")
                    val camera = prefs.lastCameraNow()
                    RestoreState.pendingItinerary = ItineraryRestore(
                        itineraryDocumentId = docId,
                        tab = prefs.lastTabNow(),
                        stepIndex = prefs.lastStepIndexNow(),
                        zoom = camera?.first,
                        lat = camera?.second,
                        lon = camera?.third,
                    )
                }
                runCatching { navController.navigate(saved) }
                    .onFailure { Log.w(TAG, "could not restore '$saved'", it) }
            }
        }
        navController.currentBackStackEntryFlow.collect { entry ->
            // The help/tutorial is a transient overlay; never restore into it.
            entry.fullRoute()?.takeUnless { it.startsWith("help") }?.let {
                Log.d(TAG, "save $it")
                prefs.setLastRoute(it)
            }
        }
    }
}

/**
 * Checks whether the saved route references an object that still exists in the
 * local database. Returns true for routes that don't reference a specific
 * object (tabs, settings, etc.) and for those where the object is found.
 */
private suspend fun isRouteValid(app: OverlandApp, route: String): Boolean = try {
    when {
        route.startsWith("itinerary/") -> {
            val docId = route.removePrefix("itinerary/").substringBefore("?")
            app.libraryRepository.itinerary(docId) != null
        }
        route.startsWith("localPack/") -> {
            val packId = route.removePrefix("localPack/")
            app.libraryRepository.trackPack(packId) != null
        }
        route.startsWith("sidebar/") -> {
            val sidebarId = route.removePrefix("sidebar/")
            app.libraryRepository.sidebarById(sidebarId) != null
        }
        route.startsWith("pack/") -> {
            // Shop pack detail: always valid (it fetches from Firestore).
            true
        }
        else -> true
    }
} catch (e: Exception) {
    Log.w(TAG, "isRouteValid check failed for '$route'", e)
    false
}

/**
 * Rebuilds a navigable route (with its argument values filled in) from a
 * back-stack entry — e.g. `itinerary/{itineraryId}?step={step}` becomes
 * `itinerary/abc?step=5`. Returns null when a path argument can't be resolved.
 */
fun NavBackStackEntry.fullRoute(): String? {
    val pattern = destination.route ?: return null
    if (!pattern.contains("{")) return pattern
    var result = pattern
    val args = arguments
    // Replace every {placeholder} taken from the pattern itself — inferred path
    // args (e.g. itineraryId) aren't always in destination.arguments.
    Regex("\\{([^{}]+)\\}").findAll(pattern).forEach { match ->
        val key = match.groupValues[1]
        args?.get(key)?.toString()?.let { value -> result = result.replace("{$key}", value) }
    }
    // Drop any unresolved optional query args; a leftover path arg is unusable.
    if (result.contains("{")) result = result.substringBefore("?")
    return result.takeUnless { it.contains("{") }
}
