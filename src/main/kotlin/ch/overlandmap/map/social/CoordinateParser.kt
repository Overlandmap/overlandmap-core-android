package ch.overlandmap.map.social

import ch.overlandmap.map.geo.Coordinate

/**
 * Reads a position typed or pasted by the user (corrections and shared waypoints), accepting
 * what iOS `parseGPSLocation` accepts and a little more:
 *  - signed decimals: `46.5, 7.25`, `(46.5 7.25)`, `[46.5; 7.25]`
 *  - decimals with cardinals before or after, attached or not: `46.5N 7.25E`, `N 46.5 E 7.25`
 *  - degrees/minutes[/seconds]: `46°30'18"N 7°15'E`, `N 46 30 18 E 7 15 0`
 * Returns null when it is not a position or is out of range.
 */
object CoordinateParser {

    fun parse(raw: String): Coordinate? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return signedPair(text) ?: cardinal(text)
    }

    private fun inRange(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0

    private val separators = Regex("[,;\\s]+")

    private fun signedPair(text: String): Coordinate? {
        if (text.any { it.uppercaseChar() in "NSEW" }) return null
        val cleaned = text.replace(Regex("[()\\[\\]]"), " ")
        val parts = cleaned.split(separators).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return if (inRange(lat, lon)) Coordinate(lat, lon) else null
    }

    /** Numbers and N/S/E/W letters, in either order, with ° ' " as separators. */
    private fun cardinal(text: String): Coordinate? {
        val spaced = text.uppercase()
            .replace('°', ' ').replace('\'', ' ').replace('"', ' ').replace('′', ' ').replace('″', ' ')
            .replace(Regex("([NSEW])"), " $1 ")
        val tokens = spaced.split(separators).filter { it.isNotEmpty() }

        // Group the numbers around the direction letters: a letter closes the number group before it
        // (suffix style "46 30 N") or opens the one after it (prefix style "N 46 30").
        data class Part(val numbers: MutableList<Double> = mutableListOf(), var dir: Char? = null)
        val parts = mutableListOf(Part())
        var prefixStyle: Boolean? = null
        for (token in tokens) {
            val c = token.singleOrNull()
            if (c != null && c in "NSEW") {
                val current = parts.last()
                if (prefixStyle == null) prefixStyle = current.numbers.isEmpty()
                if (prefixStyle == true) {
                    if (current.dir == null && current.numbers.isEmpty()) current.dir = c
                    else parts += Part(dir = c)
                } else {
                    current.dir = c
                    parts += Part()
                }
            } else {
                val n = token.toDoubleOrNull() ?: return null
                parts.last().numbers += n
            }
        }
        val groups = parts.filter { it.numbers.isNotEmpty() }
        if (groups.size != 2) return null

        fun value(p: Part): Double {
            val d = p.numbers
            val magnitude = Math.abs(d[0]) + (d.getOrElse(1) { 0.0 }) / 60.0 + (d.getOrElse(2) { 0.0 }) / 3600.0
            val sign = when (p.dir) {
                'S', 'W' -> -1.0
                'N', 'E' -> 1.0
                else -> if (d[0] < 0) -1.0 else 1.0
            }
            return sign * magnitude
        }

        var (a, b) = groups
        // Letters decide which is the latitude; without letters the first number is.
        if (a.dir in listOf('E', 'W') || b.dir in listOf('N', 'S')) {
            val t = a; a = b; b = t
        }
        if (a.dir in listOf('E', 'W') || b.dir in listOf('N', 'S')) return null
        val lat = value(a)
        val lon = value(b)
        return if (inRange(lat, lon)) Coordinate(lat, lon) else null
    }
}
