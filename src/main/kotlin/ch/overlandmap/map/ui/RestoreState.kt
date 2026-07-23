package ch.overlandmap.map.ui

/** The itinerary screen state to reapply once, when restored at cold start. */
data class ItineraryRestore(
    val itineraryDocumentId: String,
    val tab: Int,
    val stepIndex: Int,
    val zoom: Double?,
    val lat: Double?,
    val lon: Double?,
)

/**
 * A one-shot hand-off from the startup route restore ([RestoreAndPersistLastRoute])
 * to the itinerary screen: set when an itinerary route is restored at cold
 * start, and consumed by the matching itinerary screen on its first composition,
 * so the tab/step/camera are reapplied only then — never on normal navigation.
 */
object RestoreState {
    @Volatile
    var pendingItinerary: ItineraryRestore? = null

    /** Returns the pending state for [itineraryDocumentId] and clears it. */
    fun consume(itineraryDocumentId: String): ItineraryRestore? {
        val pending = pendingItinerary ?: return null
        if (pending.itineraryDocumentId != itineraryDocumentId) return null
        pendingItinerary = null
        return pending
    }
}
