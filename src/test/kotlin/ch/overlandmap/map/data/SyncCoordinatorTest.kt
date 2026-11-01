package ch.overlandmap.map.data

import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.model.Vote
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    private class FakeCache : SocialCache {
        val localPacks = mutableSetOf<String>()
        val stamps = HashMap<Pair<String, SyncKind>, Long>()
        val comments = HashMap<String, List<Comment>>()
        val checkIns = HashMap<String, List<CheckIn>>()
        val votes = HashMap<String, List<Vote>>()
        val contributed = HashMap<String, List<ContributedWaypoint>>()
        override suspend fun lastSync(trackPackId: String, kind: SyncKind) = stamps[trackPackId to kind]
        override suspend fun setLastSync(trackPackId: String, kind: SyncKind, time: Long) {
            stamps[trackPackId to kind] = time
        }
        override suspend fun clearLastSync(trackPackId: String, kind: SyncKind) {
            stamps.remove(trackPackId to kind)
        }
        override suspend fun backdateAllCheckIns(time: Long) {
            val packs = localPacks + stamps.keys.filter { it.second == SyncKind.CHECK_INS }.map { it.first }
            packs.forEach { stamps[it to SyncKind.CHECK_INS] = time }
        }
        override suspend fun replaceComments(trackPackId: String, comments: List<Comment>) {
            this.comments[trackPackId] = comments
        }
        override suspend fun replaceCheckIns(trackPackId: String, batch: CheckInBatch) {
            this.checkIns[trackPackId] = batch.checkIns
        }
        override suspend fun replaceVotes(trackPackId: String, votes: List<Vote>) {
            this.votes[trackPackId] = votes
        }
        override suspend fun replaceContributedWaypoints(trackPackId: String, waypoints: List<ContributedWaypoint>) {
            contributed[trackPackId] = waypoints
        }
    }

    private class FakeSource : SocialDataSource {
        var offline = false
        var failing: Set<SyncKind> = emptySet()
        val calls = ArrayList<SyncKind>()
        private fun <T> guard(kind: SyncKind, value: T): T {
            calls += kind
            if (offline || kind in failing) throw java.io.IOException("offline")
            return value
        }
        override suspend fun fetchComments(trackPackId: String) =
            guard(SyncKind.COMMENTS, listOf(Comment.fromFirestore("c", mapOf("objectId" to trackPackId))))
        override suspend fun fetchCheckIns(trackPackId: String) =
            guard(SyncKind.CHECK_INS, CheckInBatch(setOf("u0"), listOfNotNull(CheckIn.fromFirestore("k", mapOf("geohash" to "u0")))))
        override suspend fun fetchVotes(trackPackId: String) =
            guard(SyncKind.VOTES, listOf(Vote.fromFirestore("v", emptyMap())))
        override suspend fun fetchContributedWaypoints(trackPackId: String) =
            guard(
                SyncKind.CONTRIBUTED_WAYPOINTS,
                listOf(
                    ContributedWaypoint.fromFirestore("a", mapOf("active" to true), trackPackId),
                    ContributedWaypoint.fromFirestore("b", mapOf("active" to false), trackPackId),
                ),
            )
    }

    private fun coordinator(cache: FakeCache, source: SocialDataSource, policy: SyncPolicy, clock: () -> Long) =
        SyncCoordinator(cache, source, policy, clock)

    @Test
    fun releaseTtlsMatchIos() {
        val p = SyncPolicy.RELEASE
        assertEquals(7 * day, p.ttl(SyncKind.COMMENTS))
        assertEquals(day, p.ttl(SyncKind.CHECK_INS))
        assertEquals(7 * day, p.ttl(SyncKind.VOTES))
        assertEquals(7 * day, p.ttl(SyncKind.CONTRIBUTED_WAYPOINTS))
        assertEquals(7 * day, p.ttl(SyncKind.TRACK_PACK_VERSION))
        assertEquals(day, p.ttl(SyncKind.OVERLAND_DATA))
    }

    @Test
    fun debugTtlsMatchIos() {
        val p = SyncPolicy.DEBUG
        assertEquals(10 * 60_000L, p.ttl(SyncKind.COMMENTS))
        assertEquals(day, p.ttl(SyncKind.CHECK_INS))
        assertEquals(7 * day, p.ttl(SyncKind.VOTES))
        assertEquals(10 * 60_000L, p.ttl(SyncKind.CONTRIBUTED_WAYPOINTS))
        assertEquals(0L, p.ttl(SyncKind.TRACK_PACK_VERSION))
        assertEquals(day, p.ttl(SyncKind.OVERLAND_DATA))
        assertTrue(SyncPolicy.forBuild(true) === SyncPolicy.DEBUG)
        assertTrue(SyncPolicy.forBuild(false) === SyncPolicy.RELEASE)
    }

    @Test
    fun staleOnlyAfterTtl() {
        val p = SyncPolicy.RELEASE
        assertTrue(p.isStale(SyncKind.CHECK_INS, null, 1000))
        assertFalse(p.isStale(SyncKind.CHECK_INS, 0, day))
        assertTrue(p.isStale(SyncKind.CHECK_INS, 0, day + 1))
        assertFalse(p.isStale(SyncKind.COMMENTS, 0, 6 * day))
        // A zero TTL is stale as soon as any time has passed.
        assertTrue(SyncPolicy.DEBUG.isStale(SyncKind.TRACK_PACK_VERSION, 5, 6))
    }

    @Test
    fun syncIfNeededSkipsFreshKinds() = runBlocking {
        val cache = FakeCache()
        val source = FakeSource()
        var now = 1_000_000L
        val c = coordinator(cache, source, SyncPolicy.RELEASE) { now }
        assertTrue(c.syncIfNeeded("p", SyncKind.CHECK_INS))
        now += hour
        assertFalse(c.syncIfNeeded("p", SyncKind.CHECK_INS))
        now += day
        assertTrue(c.syncIfNeeded("p", SyncKind.CHECK_INS))
        assertEquals(2, source.calls.count { it == SyncKind.CHECK_INS })
    }

    @Test
    fun offlineNeverStampsTheSync() = runBlocking {
        val cache = FakeCache()
        val source = FakeSource().apply { offline = true }
        val c = coordinator(cache, source, SyncPolicy.RELEASE) { 5_000_000L }
        c.syncTrackPackSocialData("p")
        assertTrue(cache.stamps.isEmpty())
        assertTrue(cache.checkIns.isEmpty())
        // Back online, everything is still due.
        source.offline = false
        c.syncTrackPackSocialData("p")
        assertEquals(4, cache.stamps.size)
    }

    @Test
    fun oneFailingKindDoesNotAbortTheOthers() = runBlocking {
        val cache = FakeCache()
        val errors = ArrayList<SyncKind>()
        val source = FakeSource().apply { failing = setOf(SyncKind.COMMENTS) }
        val c = SyncCoordinator(cache, source, SyncPolicy.RELEASE, { 10L }, { kind, _, _ -> errors += kind })
        c.syncTrackPackSocialData("p")
        assertEquals(listOf(SyncKind.COMMENTS), errors)
        assertNull(cache.stamps["p" to SyncKind.COMMENTS])
        assertEquals(10L, cache.stamps["p" to SyncKind.CHECK_INS])
        assertEquals(10L, cache.stamps["p" to SyncKind.VOTES])
        assertEquals(10L, cache.stamps["p" to SyncKind.CONTRIBUTED_WAYPOINTS])
    }

    @Test
    fun onlyActiveContributedWaypointsAreCached() = runBlocking {
        val cache = FakeCache()
        val c = coordinator(cache, FakeSource(), SyncPolicy.RELEASE) { 1L }
        c.sync("p", SyncKind.CONTRIBUTED_WAYPOINTS)
        assertEquals(listOf("a"), cache.contributed["p"]!!.map { it.documentId })
    }

    @Test
    fun invalidateForcesRefetch() = runBlocking {
        val cache = FakeCache()
        val c = coordinator(cache, FakeSource(), SyncPolicy.RELEASE) { 1L }
        c.sync("p", SyncKind.VOTES)
        assertFalse(c.needsSync("p", SyncKind.VOTES))
        c.invalidate("p", SyncKind.VOTES)
        assertTrue(c.needsSync("p", SyncKind.VOTES))
    }

    @Test
    fun forceSyncsEveryKindRegardlessOfFreshness() = runBlocking {
        val cache = FakeCache()
        val source = FakeSource()
        val c = coordinator(cache, source, SyncPolicy.RELEASE) { 1L }
        c.syncTrackPackSocialData("p")
        source.calls.clear()
        c.syncTrackPackSocialData("p")
        assertTrue(source.calls.isEmpty())
        c.syncTrackPackSocialData("p", force = true)
        assertEquals(4, source.calls.size)
    }

    @Test
    fun backdatingCheckInsMakesEveryPackStaleWithoutClearingItsCache() = runBlocking {
        val cache = FakeCache().apply { localPacks += listOf("p1", "p2") }
        val source = FakeSource()
        val now = 10 * day
        val c = coordinator(cache, source, SyncPolicy.RELEASE) { now }
        c.sync("p1", SyncKind.CHECK_INS)
        c.sync("p1", SyncKind.COMMENTS)
        val cached = cache.checkIns["p1"]

        c.backdateAllCheckIns(now - 2 * day)

        assertEquals(now - 2 * day, cache.stamps["p1" to SyncKind.CHECK_INS])
        assertEquals(now - 2 * day, cache.stamps["p2" to SyncKind.CHECK_INS])
        assertEquals(now, cache.stamps["p1" to SyncKind.COMMENTS])
        assertEquals(cached, cache.checkIns["p1"])
        assertTrue(c.needsSync("p1", SyncKind.CHECK_INS))
        assertTrue(c.needsSync("p2", SyncKind.CHECK_INS))
        assertTrue(c.syncIfNeeded("p2", SyncKind.CHECK_INS))
    }

    @Test
    fun backdatingWaitsForAnInFlightCheckInSync() = runBlocking {
        val cache = FakeCache().apply { localPacks += "p1" }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val source = object : SocialDataSource by FakeSource() {
            override suspend fun fetchCheckIns(trackPackId: String): CheckInBatch {
                started.complete(Unit)
                finish.await()
                return CheckInBatch(emptySet(), emptyList())
            }
        }
        val now = 10 * day
        val c = coordinator(cache, source, SyncPolicy.RELEASE) { now }
        val sync = async { c.sync("p1", SyncKind.CHECK_INS) }
        started.await()
        val reset = async { c.backdateAllCheckIns(now - 2 * day) }
        yield()
        assertFalse(reset.isCompleted)
        finish.complete(Unit)
        sync.await()
        reset.await()
        assertTrue(c.needsSync("p1", SyncKind.CHECK_INS))
    }
}
