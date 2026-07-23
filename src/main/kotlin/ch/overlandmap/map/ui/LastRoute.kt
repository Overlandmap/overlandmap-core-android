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
    val prefs = (LocalContext.current.applicationContext as OverlandApp).userPreferences
    LaunchedEffect(navController) {
        // Suspends until the NavHost has set the graph and pushed its start
        // destination — then it is safe to navigate.
        val start = navController.currentBackStackEntryFlow.first()
        val saved = prefs.lastRouteNow()
        Log.d(TAG, "start=${start.fullRoute()} saved=$saved")
        if (!saved.isNullOrBlank() && saved != start.fullRoute()) {
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
        navController.currentBackStackEntryFlow.collect { entry ->
            entry.fullRoute()?.let {
                Log.d(TAG, "save $it")
                prefs.setLastRoute(it)
            }
        }
    }
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
