package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.LibraryDao
import ch.overlandmap.map.model.TrackPack
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Checks whether a locally downloaded pack has a newer version online, and
 * flags it (persisting `needsUpdate`). The check is throttled per pack by
 * [staleAfterMs]: it runs at most once per that window, driven automatically on
 * app start and whenever a pack is read from the local DB (see
 * [LibraryRepository]). The manual "Check for update" calls [check] directly.
 */
class PackUpdateChecker(
    private val dao: LibraryDao,
    private val shop: ShopRepository,
    private val scope: CoroutineScope,
    private val staleAfterMs: Long,
) {
    // Packs with a check already in flight, so a burst of DB reads triggers one.
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    /** Kicks off a check for [pack] if its last check is older than the window. */
    fun maybeCheck(pack: TrackPack) {
        if (System.currentTimeMillis() - (pack.lastUpdateCheck ?: 0L) < staleAfterMs) return
        if (!inFlight.add(pack.documentId)) return
        scope.launch {
            try {
                check(pack)
            } finally {
                inFlight.remove(pack.documentId)
            }
        }
    }

    /** Checks every stale local pack. Called on app start. */
    suspend fun checkStale() {
        dao.allTrackPacks().forEach(::maybeCheck)
    }

    /**
     * Runs the online version check for [pack] now, persisting `needsUpdate` and
     * the check time. Returns whether an update exists, or null when the check
     * failed (e.g. offline) — in which case the check time is left unchanged so
     * it retries. Also used by the manual "Check for update".
     */
    suspend fun check(pack: TrackPack): Boolean? {
        return try {
            val online = shop.trackPack(pack.documentId)
            val onlineVersion =
                online?.trackPackZip?.let { shop.asset(it) }?.version ?: online?.version
            // Re-read so we mutate only these two fields on the latest row, and
            // skip entirely if the pack was deleted (or re-downloaded) while the
            // network check ran — don't resurrect or clobber it.
            val current = dao.trackPack(pack.documentId) ?: return null
            val needsUpdate = onlineVersion != null && onlineVersion > (current.version ?: 0)
            dao.insertTrackPack(
                current.copy(needsUpdate = needsUpdate, lastUpdateCheck = System.currentTimeMillis()),
            )
            needsUpdate
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Re-check delay in a debuggable build. */
        val DEBUG_DELAY_MS: Long = TimeUnit.MINUTES.toMillis(10)

        /** Re-check delay in a release build. */
        val RELEASE_DELAY_MS: Long = TimeUnit.DAYS.toMillis(7)
    }
}
