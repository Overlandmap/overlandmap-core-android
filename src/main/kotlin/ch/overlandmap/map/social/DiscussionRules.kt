package ch.overlandmap.map.social

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.geo.Geohash
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.CrossingVehicle
import ch.overlandmap.map.model.Discussion
import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rules of the Overlanding discussions (iOS `DiscussionWriter` and the discussion views):
 * how threads are assembled, what a payload carries, and the checks a new border-post suggestion
 * goes through. Pure, so it is tested without Firestore.
 */
object DiscussionRules {

    /** Minimum distance between a suggested border post and any existing one. */
    const val MIN_POST_DISTANCE_KM = 1.0

    // ── Threads ─────────────────────────────────────────────────────────────

    private fun createdOf(d: Discussion) = d.createdAt ?: Long.MIN_VALUE

    /** The discussions that start a thread, newest first (the Discussion tab). */
    fun roots(all: List<Discussion>): List<Discussion> =
        all.filter { !it.isFollowup && !it.isDeleted }.sortedByDescending(::createdOf)

    /** The replies to [root], oldest first. */
    fun followups(root: Discussion, all: List<Discussion>): List<Discussion> =
        all.filter { it.isFollowup && !it.isDeleted && it.threadId == root.documentId }.sortedBy(::createdOf)

    /** Whether a correction or comment about [field] exists (the pencil turns red). */
    fun hasDiscussion(all: List<Discussion>, field: String): Boolean =
        all.any { !it.isDeleted && it.matches(field) }

    /** The discussions about [field], newest first (the field sheet's list). */
    fun forField(all: List<Discussion>, field: String): List<Discussion> =
        all.filter { !it.isDeleted && it.matches(field) }.sortedByDescending(::createdOf)

    /** Border-crossing testimonials, ordered by the crossing date when there is one, newest first. */
    fun crossingReports(all: List<Discussion>): List<Discussion> =
        all.filter { !it.isDeleted && (it.isTestimonial || it.isCrossingReport) }
            .sortedByDescending { it.crossingDate ?: it.createdAt ?: Long.MIN_VALUE }

    /** Suggested border posts of a border (suggestions that aren't replies), newest first. */
    fun suggestions(all: List<Discussion>): List<Discussion> =
        all.filter { !it.isDeleted && !it.isFollowup && it.reason == ch.overlandmap.map.model.DiscussionReason.SUGGESTION }
            .sortedByDescending(::createdOf)

    /** A discussion with replies can't be deleted (the thread would be orphaned). */
    fun canDelete(root: Discussion, all: List<Discussion>): Boolean = followups(root, all).isEmpty()

    fun isOwner(d: Discussion, uid: String?): Boolean = !uid.isNullOrEmpty() && d.userId == uid

    // ── Payloads ────────────────────────────────────────────────────────────

    /** A discussion about to be written. [nowMs] is passed in so the result is deterministic. */
    data class NewDiscussion(
        val id: String,
        val objectId: String,
        val collection: String,
        val userId: String,
        val userName: String,
        val topic: String,
        val reason: String,
        val content: String,
        val lang: String?,
        val threadId: String?,
        val location: Coordinate?,
        val isOpen: Int?,
        val nowMs: Long,
    )

    /** The document sent to Firestore (timestamps and a GeoPoint, as iOS writes them). */
    fun firestorePayload(d: NewDiscussion): Map<String, Any> = buildMap {
        put("objectId", d.objectId)
        put("collection", d.collection)
        put("userId", d.userId)
        put("userName", d.userName)
        put("createdAt", Timestamp(Date(d.nowMs)))
        put("updatedAt", Timestamp(Date(d.nowMs)))
        put("reason", d.reason)
        put("topic", d.topic)
        put("content", d.content)
        if (!d.lang.isNullOrEmpty()) put("lang", d.lang)
        if (!d.threadId.isNullOrEmpty()) put("threadId", d.threadId)
        d.location?.let {
            put("location", GeoPoint(it.latitude, it.longitude))
            put("geohash", Geohash.encode(it.latitude, it.longitude))
        }
        d.isOpen?.let { put("is_open", it) }
    }

    /** The cached copy of the document: the same fields in the plain shape the local table stores. */
    fun localCopy(d: NewDiscussion): Discussion {
        val data = buildMap<String, Any?> {
            put("objectId", d.objectId)
            put("collection", d.collection)
            put("userId", d.userId)
            put("userName", d.userName)
            put("createdAt", d.nowMs)
            put("updatedAt", d.nowMs)
            put("reason", d.reason)
            put("topic", d.topic)
            put("content", d.content)
            if (!d.lang.isNullOrEmpty()) put("lang", d.lang)
            if (!d.threadId.isNullOrEmpty()) put("threadId", d.threadId)
            d.location?.let {
                put("geohash", Geohash.encode(it.latitude, it.longitude))
                put("location", mapOf("latitude" to it.latitude, "longitude" to it.longitude))
            }
            d.isOpen?.let { put("is_open", it) }
        }
        return Discussion.fromFirestore(d.id, data)
    }

    /** A crossing report to write; becomes a `testimonial` about topic `borderCrossing`. */
    data class NewCrossingReport(
        val id: String,
        val borderPostId: String,
        val userId: String,
        val userName: String,
        val direction: String,
        val passportCountryId: String,
        val vehicle: CrossingVehicle,
        val timeSpent: String?,
        val amountPaid: String?,
        val crossingDateMs: Long?,
        val content: String,
        val lang: String?,
        val nowMs: Long,
    )

    fun firestorePayload(r: NewCrossingReport): Map<String, Any> = buildMap {
        put("objectId", r.borderPostId)
        put("collection", "border_post")
        put("userId", r.userId)
        put("userName", r.userName)
        put("createdAt", Timestamp(Date(r.nowMs)))
        put("updatedAt", Timestamp(Date(r.nowMs)))
        put("reason", "testimonial")
        put("topic", "borderCrossing")
        put("border_direction", r.direction)
        put("border_passport", r.passportCountryId)
        put("content", r.content)
        r.vehicle.dbName?.let { put("border_vehicle", it) }
        if (!r.timeSpent.isNullOrEmpty()) put("border_time_spent", r.timeSpent)
        if (!r.amountPaid.isNullOrEmpty()) put("border_amount_paid", r.amountPaid)
        r.crossingDateMs?.let { put("border_crossing_date", Timestamp(Date(it))) }
        if (!r.lang.isNullOrEmpty()) put("lang", r.lang)
    }

    fun localCopy(r: NewCrossingReport): Discussion {
        val data = buildMap<String, Any?> {
            put("objectId", r.borderPostId)
            put("userId", r.userId)
            put("userName", r.userName)
            put("createdAt", r.nowMs)
            put("updatedAt", r.nowMs)
            put("reason", "testimonial")
            put("topic", "borderCrossing")
            put("border_direction", r.direction)
            put("border_passport", r.passportCountryId)
            put("content", r.content)
            r.vehicle.dbName?.let { put("border_vehicle", it) }
            if (!r.timeSpent.isNullOrEmpty()) put("border_time_spent", r.timeSpent)
            if (!r.amountPaid.isNullOrEmpty()) put("border_amount_paid", r.amountPaid)
            r.crossingDateMs?.let { put("border_crossing_date", it) }
            if (!r.lang.isNullOrEmpty()) put("lang", r.lang)
        }
        return Discussion.fromFirestore(r.id, data)
    }

    // ── Crossing report form ────────────────────────────────────────────────

    class DirectionOption(val label: String, val code: String)

    /**
     * The two directions across a post, from its `countries` ("FRA-ESP") or, failing that, the
     * border's two countries; empty when neither is known. [nameOf] maps a code to a localized name.
     */
    fun directionOptions(
        postCountries: String?,
        borderCountry1: String?,
        borderCountry2: String?,
        nameOf: (String) -> String?,
    ): List<DirectionOption> {
        fun both(a: String, b: String) = listOf(a to b, b to a).map { (x, y) ->
            DirectionOption("${nameOf(x)?.takeIf { it.isNotEmpty() } ?: x} -> ${nameOf(y)?.takeIf { it.isNotEmpty() } ?: y}", "$x->$y")
        }
        if (postCountries != null && postCountries.contains('-')) {
            val parts = postCountries.split('-').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) return both(parts[0], parts[1])
        }
        if (!borderCountry1.isNullOrEmpty() && !borderCountry2.isNullOrEmpty()) {
            return both(borderCountry1, borderCountry2)
        }
        return emptyList()
    }

    private fun dateFormat() = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }

    /** Parses the form's `dd/MM/yyyy`; null when blank or not a real date. */
    fun parseCrossingDate(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return runCatching { dateFormat().parse(t)?.time }.getOrNull()
    }

    fun formatCrossingDate(ms: Long): String = dateFormat().format(Date(ms))

    // ── Suggesting a border post ────────────────────────────────────────────

    /** The existing post closer than [MIN_POST_DISTANCE_KM] to [candidate], if any. */
    fun tooClosePost(candidate: Coordinate, posts: List<BorderPost>): BorderPost? =
        posts.firstOrNull { p ->
            val la = p.lat ?: return@firstOrNull false
            val lo = p.lon ?: return@firstOrNull false
            candidate.distanceKm(Coordinate(la, lo)) < MIN_POST_DISTANCE_KM
        }

    /** Whether [candidate] lies inside any of the border's boxes; no known box means no objection. */
    fun isWithinBounds(candidate: Coordinate, bounds: List<GeoBounds>): Boolean =
        bounds.isEmpty() || bounds.any { it.contains(candidate) }
}
