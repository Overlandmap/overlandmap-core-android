package ch.overlandmap.map

import android.app.Application
import ch.overlandmap.map.billing.BillingManager
import ch.overlandmap.map.data.AuthRepository
import ch.overlandmap.map.data.LibraryRepository
import ch.overlandmap.map.data.MapboxTokenManager
import ch.overlandmap.map.data.PackDownloadManager
import ch.overlandmap.map.data.PlanetMapManager
import ch.overlandmap.map.data.ShopRepository
import ch.overlandmap.map.data.StyleAssetsManager
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.data.WorldRepository
import ch.overlandmap.map.data.local.AppDatabase
import ch.overlandmap.map.map.LocalTileServer
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point and the app's (deliberately simple) dependency
 * container: one instance of each repository, created lazily.
 */
class OverlandApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.get(this) }
    val authRepository by lazy { AuthRepository() }
    val shopRepository by lazy { ShopRepository(authRepository) }
    val libraryRepository by lazy { LibraryRepository(database.libraryDao(), authRepository) }
    val worldRepository by lazy { WorldRepository(database.worldDao(), authRepository) }
    val userPreferences by lazy { UserPreferences(this) }
    val mapboxTokenManager by lazy { MapboxTokenManager(userPreferences) }
    val billingManager by lazy { BillingManager(this, appScope) }
    val packDownloadManager by lazy { PackDownloadManager(this, appScope, libraryRepository) }
    val planetMapManager by lazy { PlanetMapManager(this, appScope, shopRepository) }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // Firestore rules require an authenticated user for every request:
        // reuse the cached sign-in from a previous run, or sign in anonymously.
        appScope.launch { authRepository.ensureSignedIn() }
        LocalTileServer.start(this)
        // The world base map and the offline style's fonts/sprites download
        // in the background (WorkManager) and survive the app being suspended.
        appScope.launch { StyleAssetsManager(this@OverlandApp).ensure() }
        planetMapManager.ensurePlanet()
        // Warm the Mapbox token so mapbox:// styles can load (cached, refreshed
        // when older than three months).
        appScope.launch { runCatching { mapboxTokenManager.validToken() } }
    }
}
