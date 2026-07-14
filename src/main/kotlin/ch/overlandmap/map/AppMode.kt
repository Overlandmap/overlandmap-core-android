package ch.overlandmap.map

/**
 * Which flavour of app is running, set once at startup by the app module.
 *
 * The multi-pack app (Overland Map) leaves the defaults; a single-track-pack
 * app (e.g. Ride2Ladakh) sets [singleTrackPack] and [trackPackName]. The pack's
 * document ID is resolved from the name on first use and cached in
 * [trackPackId] so the rest of the app can reach it without another lookup.
 */
object AppMode {
    /** True for a single-track-pack app (no bottom tabs); false for multi-pack. */
    var singleTrackPack: Boolean = false

    /** The configured pack name to resolve in single-track-pack mode. */
    var trackPackName: String? = null

    /** Resolved document ID of [trackPackName], cached after the first lookup. */
    var trackPackId: String? = null
}
