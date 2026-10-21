package ch.overlandmap.map.social

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.CheckInReason
import ch.overlandmap.map.model.Vote

/** Validation and decision rules of the social features, ported from the iOS sheets. */
object SocialRules {

    /** A contributed waypoint or a "wrong location" must lie within the pack's bounds plus this margin. */
    const val PACK_MARGIN_KM = 50.0

    /** A new shared waypoint closer than this to a known point is rejected as a probable duplicate. */
    const val DUPLICATE_RADIUS_KM = 0.3

    /** Reasons a user can pick when correcting a step or waypoint (in display order). */
    val correctionReasons = listOf(
        CheckInReason.DOES_NOT_EXIST, CheckInReason.CLOSED, CheckInReason.REPAIRED,
        CheckInReason.WRONG_DESCRIPTION, CheckInReason.WRONG_LOCATION,
    )

    /** Whether [coordinate] is within [bounds] grown by [PACK_MARGIN_KM]; a pack without bounds accepts anything. */
    fun isWithinPack(bounds: GeoBounds?, coordinate: Coordinate): Boolean =
        bounds == null || bounds.expanded(PACK_MARGIN_KM).contains(coordinate)

    enum class CorrectionError { REASON_REQUIRED, CONTENT_REQUIRED, LOCATION_REQUIRED, LOCATION_INVALID, LOCATION_OUTSIDE }

    /**
     * The first thing wrong with a correction form, or null. A location is only needed for
     * `wrongLocation`; [locationText] is parsed unless a [picked] position (photo, GPS) is set.
     */
    fun validateCorrection(
        reason: CheckInReason?,
        content: String,
        locationText: String,
        picked: Coordinate?,
        bounds: GeoBounds?,
    ): CorrectionError? {
        if (reason == null) return CorrectionError.REASON_REQUIRED
        if (content.isBlank()) return CorrectionError.CONTENT_REQUIRED
        if (reason != CheckInReason.WRONG_LOCATION) return null
        if (picked == null && locationText.isBlank()) return CorrectionError.LOCATION_REQUIRED
        val coordinate = picked ?: CoordinateParser.parse(locationText) ?: return CorrectionError.LOCATION_INVALID
        return if (isWithinPack(bounds, coordinate)) null else CorrectionError.LOCATION_OUTSIDE
    }

    enum class WaypointError { FIELDS_REQUIRED, LOCATION_INVALID, LOCATION_OUTSIDE, DUPLICATE }

    /** A named point a new shared waypoint must not sit on top of. */
    data class KnownPoint(val name: String, val coordinate: Coordinate)

    data class WaypointCheck(val error: WaypointError? = null, val duplicateOf: String? = null)

    /** Checks a shared-waypoint form; [others] are steps, pack waypoints and other contributed waypoints. */
    fun validateWaypoint(
        name: String,
        content: String,
        locationText: String,
        picked: Coordinate?,
        bounds: GeoBounds?,
        others: List<KnownPoint>,
        ignoreId: String? = null,
    ): WaypointCheck {
        if (name.isBlank() || content.isBlank() || (picked == null && locationText.isBlank())) {
            return WaypointCheck(WaypointError.FIELDS_REQUIRED)
        }
        val coordinate = picked ?: CoordinateParser.parse(locationText)
            ?: return WaypointCheck(WaypointError.LOCATION_INVALID)
        if (!isWithinPack(bounds, coordinate)) return WaypointCheck(WaypointError.LOCATION_OUTSIDE)
        val near = others.firstOrNull { it.coordinate.distanceKm(coordinate) < DUPLICATE_RADIUS_KM }
        return if (near != null) WaypointCheck(WaypointError.DUPLICATE, near.name) else WaypointCheck()
    }

    enum class CommentError { TEXT_REQUIRED, RATING_REQUIRED }

    fun validateComment(text: String, rating: Int): CommentError? = when {
        text.isBlank() -> CommentError.TEXT_REQUIRED
        rating < 1 -> CommentError.RATING_REQUIRED
        else -> null
    }

    /** What tapping the up or down button does, given the user's current vote on the object. */
    sealed interface VoteAction {
        object Create : VoteAction
        data class Update(val vote: Vote) : VoteAction
        data class Delete(val vote: Vote) : VoteAction
    }

    /** Same vote again removes it, the other one switches it, none creates it. */
    fun voteAction(existing: Vote?, tappedUp: Boolean): VoteAction = when {
        existing == null -> VoteAction.Create
        existing.isUpVote == tappedUp -> VoteAction.Delete(existing)
        else -> VoteAction.Update(existing)
    }

    /** One comment per user per object; the Add button disappears once the user has one. */
    fun canAddComment(signedIn: Boolean, hasOwn: Boolean): Boolean = signedIn && !hasOwn

    /** The comment (or check-in) author, or an admin, may delete. */
    fun canDeleteComment(userId: String?, currentUid: String?, isAdmin: Boolean): Boolean =
        isAdmin || (userId != null && userId == currentUid)
}
