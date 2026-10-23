package ch.overlandmap.map

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import ch.overlandmap.map.billing.BillingManager
import ch.overlandmap.map.data.AccountRepository
import ch.overlandmap.map.data.AuthRepository
import ch.overlandmap.map.data.DiscussionRepository
import ch.overlandmap.map.data.GlobalSettingsRepository
import ch.overlandmap.map.data.LibraryRepository
import ch.overlandmap.map.data.OverlandDataManager
import ch.overlandmap.map.data.MapboxTokenManager
import ch.overlandmap.map.data.PackDownloadManager
import ch.overlandmap.map.data.PackUpdateChecker
import ch.overlandmap.map.data.PlanetDemManager
import ch.overlandmap.map.data.PlanetMapManager
import ch.overlandmap.map.data.SatelliteTileManager
import ch.overlandmap.map.data.SearchRepository
import ch.overlandmap.map.data.ShopRepository
import ch.overlandmap.map.data.SocialRepository
import ch.overlandmap.map.data.StyleAssetsManager
import ch.overlandmap.map.data.SyncPolicy
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.data.WorldRepository
import ch.overlandmap.map.data.local.AppDatabase
import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.map.LocalTileServer
import ch.overlandmap.map.model.Entitlements
import ch.overlandmap.map.units.UnitFormatter
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Application entry point and the app's (deliberately simple) dependency
 * container: one instance of each repository, created lazily.
 */
class OverlandApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.get(this) }
    val ftsIndex by lazy { FtsIndex(database) }
    val authRepository by lazy { AuthRepository() }
    val accountRepository by lazy { AccountRepository(this, authRepository) }
    val shopRepository by lazy { ShopRepository(authRepository) }
    val packUpdateChecker by lazy {
        PackUpdateChecker(
            database.libraryDao(),
            shopRepository,
            appScope,
            if (isDebugBuild(this)) PackUpdateChecker.DEBUG_DELAY_MS
            else PackUpdateChecker.RELEASE_DELAY_MS,
        )
    }
    val libraryRepository by lazy {
        LibraryRepository(database.libraryDao(), authRepository, ftsIndex, packUpdateChecker)
    }
    val socialRepository by lazy {
        SocialRepository(
            database.socialDao(), database.libraryDao(), authRepository, ftsIndex,
            policy = SyncPolicy.forBuild(isDebugBuild(this)),
            userName = { accountRepository.postedName() },
        )
    }
    val discussionRepository by lazy {
        DiscussionRepository(
            database.socialDao(), authRepository, userPreferences,
            userName = { accountRepository.postedName() },
        )
    }
    val weatherRepository by lazy { ch.overlandmap.map.weather.WeatherRepository(cacheDir) }
    // ── Recording and navigation (beta) ─────────────────────────────────────

    val trackRecorder: ch.overlandmap.map.tracking.TrackRecorder by lazy {
        ch.overlandmap.map.tracking.TrackRecorder(
            store = ch.overlandmap.map.tracking.TrackStore(java.io.File(filesDir, "recordings")),
            location = ch.overlandmap.map.tracking.AndroidLocationSource(this),
            onRecordingChanged = { active -> if (active) ch.overlandmap.map.tracking.TrackingService.start(this) },
        )
    }
    val savedNavigationStore by lazy { ch.overlandmap.map.navigation.SavedNavigationStore(userPreferences, appScope) }
    val bluetoothGPS by lazy { ch.overlandmap.map.bluetooth.BluetoothGPS(this) }
    private val navigationLocation by lazy { ch.overlandmap.map.bluetooth.SelectedLocationSource(this) { navigationCoordinator.locationUnavailable() } }
    private val headingSensor by lazy { ch.overlandmap.map.tracking.HeadingSensor(this) { navigationLocation.lastFix } }
    val navigationCoordinator: ch.overlandmap.map.navigation.NavigationCoordinator by lazy {
        val coordinator = ch.overlandmap.map.navigation.NavigationCoordinator(
            location = navigationLocation,
            persistence = savedNavigationStore,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            onTargetReached = { /* no chime when reaching a step */ },
            log = { line -> android.util.Log.i("Navigation", line) },
            debug = { line -> android.util.Log.d("NavDebug", line) },
            onNavigatingChanged = { active ->
                if (active) {
                    headingSensor.start()
                    ch.overlandmap.map.tracking.TrackingService.start(this)
                } else {
                    headingSensor.stop()
                }
            },
        )
        appScope.launch(Dispatchers.Main.immediate) { headingSensor.heading.collect { coordinator.updateHeading(it) } }
        coordinator
    }

    /** What the tracking notification shows; inactive when nothing is being recorded or navigated. */
    val trackingStatus: StateFlow<ch.overlandmap.map.tracking.TrackingStatus> by lazy {
        fun status(
            recordings: List<ch.overlandmap.map.tracking.RecordedTrack>,
            currentId: String?,
            nav: ch.overlandmap.map.navigation.NavigationState,
        ) = ch.overlandmap.map.tracking.TrackingStatus(
            recording = currentId?.let { id -> recordings.firstOrNull { it.id == id } }?.let { t ->
                getString(R.string.recording_status, t.formattedDistance(), t.points.size, t.waypoints.size)
            },
            navigation = nav.takeIf { it.isNavigating }?.let {
                getString(
                    R.string.navigation_status,
                    it.currentTargetStep?.fullName(it.language) ?: getString(R.string.nav),
                    UnitFormatter(userPreferences.unitFormatNow(), java.util.Locale.getDefault()).navigationDistance(it.distanceToTargetMeters).ifEmpty { "--" },
                ) + if (it.isOffTrack) " · " + getString(R.string.off_track) else ""
            },
        )
        combine(trackRecorder.recordings, trackRecorder.currentTrackId, navigationCoordinator.state, ::status)
            .stateIn(
                appScope, SharingStarted.Eagerly,
                status(trackRecorder.recordings.value, trackRecorder.currentTrackId.value, navigationCoordinator.state.value),
            )
    }

    val worldRepository by lazy { WorldRepository(database.worldDao()) }
    val globalSettingsRepository by lazy { GlobalSettingsRepository(database.worldDao(), authRepository) }
    /**
     * A pending request to show an Overlanding object (from a markup link, a search result or a
     * universal link); the Overlanding tab consumes it and frames the object on its map.
     */
    val overlandingLink = MutableStateFlow<ch.overlandmap.map.ui.world.OverlandingSelection?>(null)
    /** A universal link received from outside (a tapped overlandmap.ch URL), consumed by the navigation. */
    val pendingLink = MutableStateFlow<ch.overlandmap.map.model.UniversalLink?>(null)
    val universalLinkResolver by lazy {
        ch.overlandmap.map.data.UniversalLinkResolver(
            ch.overlandmap.map.data.RepositoryLinkStore(libraryRepository, worldRepository)
        )
    }
    /** Bumped when the Overlanding tab is tapped again: back to its home screen. */
    val overlandingReset = MutableStateFlow(0)
    val overlandDataManager by lazy {
        OverlandDataManager(this, appScope, shopRepository, database.worldDao(), ftsIndex)
    }
    val searchRepository by lazy { SearchRepository(ftsIndex, libraryRepository) }
    val satelliteTileManager by lazy { SatelliteTileManager(this, appScope) }
    val userPreferences by lazy { UserPreferences(this) }
    val mapboxTokenManager by lazy { MapboxTokenManager(userPreferences) }
    val billingManager by lazy { BillingManager(this, appScope, uuidProvider = { accountRepository.purchaseUuid() }) }
    val packDownloadManager by lazy { PackDownloadManager(this, appScope, libraryRepository) }
    val planetMapManager by lazy { PlanetMapManager(this, appScope, shopRepository) }
    val planetDemManager by lazy { PlanetDemManager(this, appScope, shopRepository) }
    val styleAssetsManager by lazy { StyleAssetsManager(this) }

    /**
     * Cached map of track pack document IDs to their display names, fetched
     * from Firestore at startup. Used by debug tools to resolve pack names
     * without repeated network calls.
     */
    @Volatile
    var trackPackNames: Map<String, String> = emptyMap()
        private set

    /**
     * The signed-in user's entitlements, live from `users/{uid}/purchases`; null until the first
     * snapshot of the current user has arrived (so "no beta" isn't mistaken for "not loaded yet").
     * Anonymous sessions have none: an empty [Entitlements].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val entitlements: StateFlow<Entitlements?> by lazy {
        authRepository.userFlow
            .flatMapLatest<FirebaseUser?, Entitlements?> { user ->
                if (user == null) flowOf(null)
                else flow {
                    emit(null)
                    emitAll(shopRepository.purchasesFlow().map { Entitlements(it) })
                }
            }
            .stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /** Suspends until the current user's purchases have been loaded. */
    suspend fun awaitEntitlements(): Entitlements = entitlements.filterNotNull().first()

    /** True when the signed-in user has the (case-insensitive) 'admin' entitlement. Live, not cached. */
    val isAdmin: Boolean get() = entitlements.value?.hasAdmin == true

    /** True when the signed-in user has the 'beta' entitlement. */
    val isBeta: Boolean get() = entitlements.value?.hasBeta == true

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // On first launch, default the interface and map languages to the
        // device language (if supported), otherwise English.
        initDefaultLanguage()
        // Firestore rules require an authenticated user for every request:
        // reuse the cached sign-in from a previous run, or sign in anonymously.
        appScope.launch { authRepository.ensureSignedIn() }
        LocalTileServer.start(this)
        // Warm the Mapbox token so mapbox:// styles can load (cached, refreshed
        // when older than three months). Independent of local storage.
        appScope.launch { runCatching { mapboxTokenManager.validToken() } }

        // A database left by an older, incompatible build cannot be opened
        // (Room has no migration path). Touching it — as the asset-download and
        // sync work below would — crashes the process. Defer all
        // storage-dependent startup until the UI's DatabaseUpgradeGate has the
        // user confirm the wipe and calls [runStorageDependentStartup]. On a
        // fresh install or a compatible database, run it right away.
        if (AppDatabase.needsDestructiveReset(this)) {
            android.util.Log.w(TAG, "Old database schema detected; deferring startup until reset is confirmed")
        } else {
            runStorageDependentStartup()
        }
    }

    /**
     * Startup work that reads or writes the local database and downloaded
     * assets: the general map assets (world map, relief, fonts, sprites) plus
     * the per-pack social sync and update checks. Held back when an
     * incompatible database is found until the user confirms the reset (see
     * [ch.overlandmap.map.ui.DatabaseUpgradeGate]).
     */
    fun runStorageDependentStartup() {
        // The world base map and the offline style's fonts/sprites download
        // in the background (WorkManager) and survive the app being suspended.
        appScope.launch { styleAssetsManager.ensure() }
        planetMapManager.ensurePlanet()
        // The world relief (Terrain-RGB DEM) the styles' hillshade and
        // elevation-colour layers read; fetched once, like the world base map.
        planetDemManager.ensurePlanetDem()
        // The Overlanding assets (country borders tiles + the countries dump) and the advisory
        // URL templates, like iOS fetched at launch; an offline start keeps the cached copies.
        overlandDataManager.ensure()
        appScope.launch { globalSettingsRepository.refreshAdvisoryUrlTemplates() }
        // Cache track pack names from Firestore for debug tools.
        appScope.launch {
            runCatching {
                authRepository.awaitUser()
                val docs = FirebaseFirestore.getInstance()
                    .collection("track_pack")
                    .get()
                    .await()
                trackPackNames = docs.documents.associate { doc ->
                    doc.id to ((doc.data?.get("name") as? String) ?: doc.id)
                }
            }
        }
        // Start listening to the entitlements (admin / beta / purchases) right away.
        entitlements
        // Once an account is signed in: make sure it carries the uuid purchases are tied to, and
        // validate/acknowledge any Play purchase left half-done (Play refunds those after 3 days).
        appScope.launch {
            authRepository.userFlow.filterNotNull().filter { !it.isAnonymous }.collect {
                accountRepository.ensureUuid()
                runCatching { billingManager.reconcileUnacknowledged() }
            }
        }
        // Sync social data (check-ins, votes, comments, contributed waypoints) for each
        // locally downloaded pack, each kind under its own freshness policy (SyncPolicy).
        appScope.launch {
            runCatching {
                authRepository.awaitUser()
                val packs = libraryRepository.allTrackPackIds()
                packs.forEach { packId ->
                    runCatching { socialRepository.syncTrackPackSocialData(packId) }
                }
            }
        }
        // Check each local pack for a newer version online, throttled per pack
        // (10 min debug / 1 week release — see PackUpdateChecker).
        appScope.launch {
            runCatching {
                authRepository.awaitUser()
                packUpdateChecker.checkStale()
            }
        }
    }

    /**
     * Erases all app-private local data — the database, downloaded photos and
     * map archives (`.pmtiles`), and caches — then recreates the database and
     * restarts the general-asset downloads. Called by the upgrade gate once the
     * user accepts wiping an incompatible install.
     */
    fun wipeLocalDataAndRestart() {
        // Drop the incompatible database (closes its handle first).
        AppDatabase.deleteDatabase(this)
        // Remove everything else under private storage: photos, pmtiles/mbtiles,
        // style assets, and any cached scratch files.
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // The tile server's archive handles now point at deleted files.
        ch.overlandmap.map.map.LocalTileServer.reloadArchives()
        // The saved route points at an object that no longer exists.
        userPreferences.clearLastRoute()
        // Recreate the database (via the lazy get on next access) and re-fetch
        // the general assets from scratch.
        runStorageDependentStartup()
    }

    /**
     * On first launch (no AppCompat locale set yet), sets the interface and map
     * languages to the device language if it's one we support, otherwise English.
     */
    private fun initDefaultLanguage() {
        val current = AppCompatDelegate.getApplicationLocales()
        if (!current.isEmpty) return // already configured — not a fresh install

        val deviceLang = java.util.Locale.getDefault().language
        val supported = AppConfig.SUPPORTED_LANGUAGES.map { it.first }.toSet()
        val lang = if (deviceLang in supported) deviceLang else "en"

        AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(lang)
        )
        appScope.launch { userPreferences.setMapLanguage(lang) }
    }

    private companion object {
        const val TAG = "OverlandApp"
    }
}
