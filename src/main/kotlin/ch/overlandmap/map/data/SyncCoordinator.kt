package ch.overlandmap.map.data

import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.model.Vote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What kind of remote data a sync timestamp refers to. Each kind has its own freshness
 * policy (see [SyncPolicy]) because the cost of stale data differs. Raw values are the
 * strings stored in `sync_state.kind` (same as iOS `SyncKind`).
 */
enum class SyncKind(val raw: String) {
    COMMENTS("comments"),
    CHECK_INS("checkIns"),
    VOTES("votes"),
    CONTRIBUTED_WAYPOINTS("contributedWaypoints"),
    TRACK_PACK_VERSION("trackPackVersion"),
    OVERLAND_DATA("overlandData"),
}

/**
 * How long each kind of cached data stays fresh (port of iOS `SyncPolicy`):
 *  - check-ins refresh after 24 hours;
 *  - comments and votes weekly in release, but comments every 10 minutes in debug so a
 *    comment written elsewhere shows up without waiting out the interval;
 *  - contributed waypoints weekly in release and every 10 minutes in debug;
 *  - track pack version checks weekly in release and on every open in debug (TTL 0).
 */
class SyncPolicy(private val ttlMillis: Map<SyncKind, Long>) {

    fun ttl(kind: SyncKind): Long = ttlMillis[kind] ?: DAY

    /** True when a copy synced at [lastSync] (null = never) is missing or older than its TTL. */
    fun isStale(kind: SyncKind, lastSync: Long?, now: Long): Boolean =
        lastSync == null || now - lastSync > ttl(kind)

    companion object {
        private const val MINUTE = 60_000L
        private const val DAY = 24 * 60 * MINUTE
        private const val WEEK = 7 * DAY

        val RELEASE = SyncPolicy(
            mapOf(
                SyncKind.COMMENTS to WEEK,
                SyncKind.CHECK_INS to DAY,
                SyncKind.VOTES to WEEK,
                SyncKind.CONTRIBUTED_WAYPOINTS to WEEK,
                SyncKind.TRACK_PACK_VERSION to WEEK,
                SyncKind.OVERLAND_DATA to DAY,
            )
        )

        val DEBUG = SyncPolicy(
            mapOf(
                SyncKind.COMMENTS to 10 * MINUTE,
                SyncKind.CHECK_INS to DAY,
                SyncKind.VOTES to WEEK,
                SyncKind.CONTRIBUTED_WAYPOINTS to 10 * MINUTE,
                // 0 = always stale, so a debug build re-checks the version every time.
                SyncKind.TRACK_PACK_VERSION to 0L,
                SyncKind.OVERLAND_DATA to DAY,
            )
        )

        fun forBuild(isDebug: Boolean): SyncPolicy = if (isDebug) DEBUG else RELEASE
    }
}

/** Where synced data comes from. Firestore implements this; tests use a fake. */
interface SocialDataSource {
    suspend fun fetchComments(trackPackId: String): List<Comment>
    suspend fun fetchCheckIns(trackPackId: String): CheckInBatch
    suspend fun fetchVotes(trackPackId: String): List<Vote>

    /** Only rows with `active == true`; moderated-out waypoints are never cached. */
    suspend fun fetchContributedWaypoints(trackPackId: String): List<ContributedWaypoint>
}

/** The queried locations and their results, including locations with no check-ins. */
data class CheckInBatch(val geohashes: Set<String>, val checkIns: List<CheckIn>)

/** The local store the coordinator writes to; each `replace…` is one transaction. */
interface SocialCache {
    suspend fun lastSync(trackPackId: String, kind: SyncKind): Long?
    suspend fun setLastSync(trackPackId: String, kind: SyncKind, time: Long)
    suspend fun clearLastSync(trackPackId: String, kind: SyncKind)
    suspend fun backdateAllCheckIns(time: Long)
    suspend fun replaceComments(trackPackId: String, comments: List<Comment>)
    suspend fun replaceCheckIns(trackPackId: String, batch: CheckInBatch)
    suspend fun replaceVotes(trackPackId: String, votes: List<Vote>)
    suspend fun replaceContributedWaypoints(trackPackId: String, waypoints: List<ContributedWaypoint>)
}

/**
 * Decides what needs refreshing and writes the results into the local store (port of iOS
 * `SyncCoordinator`). The app is offline-first: the database is the single source of truth
 * for the UI and this is the only thing that pushes remote data into it.
 *
 * A failed fetch (offline included) throws before anything is written or stamped, so an
 * offline attempt is never recorded as a successful sync.
 */
class SyncCoordinator(
    private val cache: SocialCache,
    private val source: SocialDataSource,
    private val policy: SyncPolicy,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (SyncKind, String, Throwable) -> Unit = { _, _, _ -> },
) {
    /** Guards against two overlapping syncs of the same pack and kind. */
    private val inFlightLock = Mutex()
    private val checkInSyncLock = Mutex()
    private val inFlight = HashSet<String>()

    suspend fun needsSync(trackPackId: String, kind: SyncKind): Boolean =
        policy.isStale(kind, cache.lastSync(trackPackId, kind), now())

    suspend fun lastSync(trackPackId: String, kind: SyncKind): Long? = cache.lastSync(trackPackId, kind)

    /** Records a successful sync, for callers that fetched through another path. */
    suspend fun markSynced(trackPackId: String, kind: SyncKind) =
        cache.setLastSync(trackPackId, kind, now())

    /** Forces the next check to refetch (after a manual refresh or a sign-in). */
    suspend fun invalidate(trackPackId: String, kind: SyncKind) =
        cache.clearLastSync(trackPackId, kind)

    /** Backdates every pack's check-in sync after any in-flight check-in fetch finishes. */
    suspend fun backdateAllCheckIns(time: Long) = checkInSyncLock.withLock {
        cache.backdateAllCheckIns(time)
    }

    /** Syncs [kind] when its cache is missing or stale. Returns whether a sync ran. */
    suspend fun syncIfNeeded(trackPackId: String, kind: SyncKind): Boolean =
        if (needsSync(trackPackId, kind)) sync(trackPackId, kind) else false

    /** Fetches and caches regardless of freshness. Returns false when a sync of it is already running. */
    suspend fun sync(trackPackId: String, kind: SyncKind): Boolean {
        val token = "$trackPackId/${kind.raw}"
        if (!inFlightLock.withLock { inFlight.add(token) }) return false
        try {
            suspend fun fetchAndStamp() {
                when (kind) {
                    SyncKind.COMMENTS ->
                        cache.replaceComments(trackPackId, source.fetchComments(trackPackId))
                    SyncKind.CHECK_INS ->
                        cache.replaceCheckIns(trackPackId, source.fetchCheckIns(trackPackId))
                    SyncKind.VOTES ->
                        cache.replaceVotes(trackPackId, source.fetchVotes(trackPackId))
                    SyncKind.CONTRIBUTED_WAYPOINTS ->
                        cache.replaceContributedWaypoints(
                            trackPackId,
                            // Moderated-out waypoints are never cached, whatever the source returned.
                            source.fetchContributedWaypoints(trackPackId).filter { it.isActive },
                        )
                    // Handled by their own repositories; the timestamp is still recorded here
                    // so the policy lives in one place.
                    SyncKind.TRACK_PACK_VERSION, SyncKind.OVERLAND_DATA -> Unit
                }
                markSynced(trackPackId, kind)
            }
            if (kind == SyncKind.CHECK_INS) checkInSyncLock.withLock { fetchAndStamp() }
            else fetchAndStamp()
            return true
        } finally {
            inFlightLock.withLock { inFlight.remove(token) }
        }
    }

    /**
     * Refreshes everything social for a pack. One failing kind must not abort the others:
     * a comments outage should not cost the user their check-ins.
     */
    suspend fun syncTrackPackSocialData(trackPackId: String, force: Boolean = false) {
        for (kind in SOCIAL_KINDS) {
            try {
                if (force) sync(trackPackId, kind) else syncIfNeeded(trackPackId, kind)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(kind, trackPackId, e)
            }
        }
    }

    companion object {
        val SOCIAL_KINDS = listOf(
            SyncKind.COMMENTS, SyncKind.CONTRIBUTED_WAYPOINTS, SyncKind.CHECK_INS, SyncKind.VOTES,
        )
    }
}
