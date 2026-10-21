package ch.overlandmap.map.geo

/**
 * Geohash encode/decode. Port of iOS `Geohash`. Geohashes are an indexed column on
 * several tables (steps, waypoints, border posts, contributed waypoints) and let a
 * waypoint without a stored geohash get one computed on decode.
 */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** Precision 9 gives roughly ±2.4 m, matching the values in the track pack exports. */
    fun encode(latitude: Double, longitude: Double, precision: Int = 9): String {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        val hash = StringBuilder()
        var bit = 0
        var index = 0
        var isEven = true
        while (hash.length < precision) {
            if (isEven) {
                val mid = (lonMin + lonMax) / 2
                if (longitude >= mid) {
                    index = index * 2 + 1
                    lonMin = mid
                } else {
                    index *= 2
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (latitude >= mid) {
                    index = index * 2 + 1
                    latMin = mid
                } else {
                    index *= 2
                    latMax = mid
                }
            }
            isEven = !isEven
            bit++
            if (bit == 5) {
                hash.append(BASE32[index])
                bit = 0
                index = 0
            }
        }
        return hash.toString()
    }

    /** The centre of the cell [hash] denotes, or null for an empty/invalid hash. */
    fun decode(hash: String?): Coordinate? = boundingBox(hash)?.centre

    /** The cell [hash] denotes (case-insensitive); null for an empty or invalid hash. */
    fun boundingBox(hash: String?): GeoBounds? {
        if (hash.isNullOrEmpty()) return null
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var isEven = true
        for (ch in hash.lowercase()) {
            val value = BASE32.indexOf(ch)
            if (value < 0) return null
            for (shift in 4 downTo 0) {
                val bit = (value shr shift) and 1
                if (isEven) {
                    val mid = (lonMin + lonMax) / 2
                    if (bit == 1) lonMin = mid else lonMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (bit == 1) latMin = mid else latMax = mid
                }
                isEven = !isEven
            }
        }
        return GeoBounds(latMin, latMax, lonMin, lonMax)
    }

    /** Distance between two geohashed points, in kilometres; null if either is invalid. */
    fun distanceKm(a: String?, b: String?): Double? {
        val ca = decode(a) ?: return null
        val cb = decode(b) ?: return null
        return ca.distanceKm(cb)
    }

    /**
     * Shortest prefix whose cell is no larger than [km] across, clamped to 1..9. Turns a
     * "within N km" search into an indexed `LIKE 'prefix%'` scan; callers still filter
     * precisely.
     */
    fun prefixLength(forKm: Double): Int = when {
        forKm < 0.02 -> 9
        forKm < 0.2 -> 7
        forKm < 1 -> 6
        forKm < 5 -> 5
        forKm < 40 -> 4
        forKm < 200 -> 3
        forKm < 1000 -> 2
        else -> 1
    }
}
