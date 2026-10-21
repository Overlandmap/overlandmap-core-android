package ch.overlandmap.map.units

/** How coordinates are written out. */
enum class GpsFormat {
    /** `34.05972, 77.51383` */
    DD,

    /** `34° 03.583' N 77° 30.830' E` */
    DDMM,

    /** `34° 03' 35'' N, 77° 30' 49'' E` */
    DDMMSS,
}

/**
 * The user's unit preferences. Port of iOS `UnitFormat`.
 *
 * The axes are **independent** (someone can want kilometres for distance but feet for
 * altitude), matching the Flutter and iOS apps. Collapsing them into one metric/imperial
 * switch would be a regression.
 */
data class UnitFormat(
    val heightMetric: Boolean = true,
    val distanceMetric: Boolean = true,
    val temperatureCelsius: Boolean = true,
    val gpsFormat: GpsFormat = GpsFormat.DD,
) {
    companion object {
        val METRIC = UnitFormat()
        val IMPERIAL = UnitFormat(
            heightMetric = false,
            distanceMetric = false,
            temperatureCelsius = false,
        )

        /** Regions whose measurement system is "US" (CLDR); everywhere else is metric. */
        private val US_SYSTEM_REGIONS = setOf("US", "LR", "MM")

        /**
         * The format implied by the device region, used until the user chooses. Mirrors iOS
         * `deviceDefault`, which is metric unless the measurement system is US.
         */
        fun deviceDefault(regionCode: String?): UnitFormat =
            if (regionCode?.uppercase() in US_SYSTEM_REGIONS) IMPERIAL else METRIC
    }
}
