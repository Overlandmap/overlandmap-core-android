package ch.overlandmap.map.data.routes

import android.util.Log
import ch.overlandmap.map.data.AuthRepository
import ch.overlandmap.map.planning.RouteGeometryCodec
import ch.overlandmap.map.planning.SegmentGeometry
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Segment polylines of the routing graph, read from Firestore on demand (iOS `RouteGeometryStore`).
 *
 * The geometry of segment `id` lives in the documents of `segment/{id}/geometry`; see
 * [RouteGeometryCodec]. Each polyline is fetched once and kept on disk, so a trip that was planned
 * or drawn once can be shown and edited again without a connection.
 */
class RouteGeometryStore(
    private val auth: AuthRepository,
    private val directory: File,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val memory = ConcurrentHashMap<String, SegmentGeometry>()

    /** Downloads in flight, so concurrent callers (a drag preview, a refresh) share one. */
    private val downloads = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun file(id: String) = File(directory, id.replace("/", "_") + ".json")

    /** The polyline if it is in memory or on disk; never touches the network. */
    fun peek(id: String): SegmentGeometry? {
        memory[id]?.let { return it }
        val file = file(id)
        if (!file.isFile) return null
        val geometry = runCatching {
            RouteGeometryCodec.points(file.readText())?.let { SegmentGeometry.create(it) }
        }.getOrNull() ?: return null
        memory[id] = geometry
        return geometry
    }

    /**
     * Fetches the polylines that are not stored yet. Returns the IDs that could not be loaded
     * (offline, missing document, undecodable content).
     */
    suspend fun ensure(ids: List<String>): List<String> {
        val missing = ids.distinct().filter { peek(it) == null }
        if (missing.isEmpty()) return emptyList()
        directory.mkdirs()
        val failed = ArrayList<String>()
        // A few requests at a time: a long trip has hundreds of segments.
        for (batch in missing.chunked(8)) {
            coroutineScope {
                batch.map { id -> id to sharedDownload(id) }.map { (id, task) ->
                    async { if (!task.await()) synchronized(failed) { failed += id } }
                }.awaitAll()
            }
        }
        return failed
    }

    private fun sharedDownload(id: String): Deferred<Boolean> {
        downloads[id]?.let { return it }
        val created = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { download(id) }
        val existing = downloads.putIfAbsent(id, created)
        if (existing != null) {
            created.cancel()
            return existing
        }
        created.invokeOnCompletion { downloads.remove(id) }
        created.start()
        return created
    }

    private suspend fun download(id: String): Boolean {
        return try {
            auth.awaitUser()
            val documents = db.collection("segment/$id/geometry").get().await().documents
            // Chunk documents are named 000000, 000001, …: ID order is chunk order.
            val chunks = documents.sortedBy { it.id }.mapNotNull { it.getString("content") }
            val json = if (chunks.isEmpty()) null else RouteGeometryCodec.jsonFromChunks(chunks)
            val geometry = json?.let { RouteGeometryCodec.points(it) }?.let { SegmentGeometry.create(it) }
            if (json == null || geometry == null) {
                Log.e(TAG, "Unusable geometry for segment $id")
                return false
            }
            val target = file(id)
            val staging = File(target.path + ".part")
            staging.writeText(json)
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            memory[id] = geometry
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Geometry of $id: ${e.localizedMessage}")
            false
        }
    }

    private companion object {
        const val TAG = "RouteGeometry"
    }
}
