package ch.overlandmap.map.model

import java.text.Normalizer

/** The alphabetical prefix of the itinerary slug, e.g. "Om" in "Om5", or "" if none. */
val Itinerary.idPrefix: String get() = itineraryId.takeWhile { !it.isDigit() }

/** The numeric postfix of the itinerary slug, e.g. 5 in "Om5", or null if none. */
val Itinerary.idPostfix: Int? get() =
    itineraryId.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()

/**
 * Finder-style string comparison (iOS `localizedStandardCompare`): case- and
 * diacritic-insensitive, and digit runs compare by numeric value ("K2" < "K10").
 */
object NaturalOrder : Comparator<String> {
    private fun fold(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()

    override fun compare(a: String, b: String): Int {
        val x = fold(a)
        val y = fold(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var ie = i
                while (ie < x.length && x[ie].isDigit()) ie++
                var je = j
                while (je < y.length && y[je].isDigit()) je++
                val nx = x.substring(i, ie).trimStart('0')
                val ny = y.substring(j, je).trimStart('0')
                if (nx.length != ny.length) return nx.length.compareTo(ny.length)
                val c = nx.compareTo(ny)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                if (x[i] != y[j]) return x[i].compareTo(y[j])
                i++
                j++
            }
        }
        return (x.length - i).compareTo(y.length - j)
    }
}

/**
 * The order itineraries are listed in (iOS `LocalStore.sortedForDisplay`): optionally the
 * free one first (free-sample packs), then by the alphabetical prefix of the slug, then by
 * its numeric postfix (none sorts last), then by the whole slug.
 */
fun List<Itinerary>.sortedForDisplay(freeFirst: Boolean = false): List<Itinerary> = sortedWith { a, b ->
    if (freeFirst && a.isFree != b.isFree) return@sortedWith if (a.isFree) -1 else 1
    val prefix = NaturalOrder.compare(a.idPrefix, b.idPrefix)
    if (prefix != 0) return@sortedWith prefix
    val na = a.idPostfix ?: Int.MAX_VALUE
    val nb = b.idPostfix ?: Int.MAX_VALUE
    if (na != nb) return@sortedWith na.compareTo(nb)
    NaturalOrder.compare(a.itineraryId, b.itineraryId)
}
