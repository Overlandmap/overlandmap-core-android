package ch.overlandmap.map.navigation

/** Explicit road entry targets versus joining the track in the chosen direction. */
enum class NavigationStartChoice {
    FIRST, LAST, NEAREST, FORWARD, REVERSE;

    val reverse: Boolean get() = this == LAST || this == REVERSE
}
