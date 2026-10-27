package ch.overlandmap.map.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model decoding rules, ported from the iOS `OverlandMapKit` tests and models: the same
 * Firestore document must give the same object on both platforms.
 */
class ModelsTest {

    // ── LocalizedLookup ─────────────────────────────────────────────────────

    @Test
    fun lookupPrefersExactMatchThenBaseCode() {
        val map = mapOf("pt" to "pt", "pt-PT" to "pt-PT", "fr" to "fr")
        assertEquals("pt", LocalizedLookup.value(map, "pt"))
        assertEquals("pt-PT", LocalizedLookup.value(mapOf("pt-PT" to "pt-PT"), "pt"))
        assertEquals("pt-PT", LocalizedLookup.value(map, "pt-PT"))
        assertEquals("fr", LocalizedLookup.value(map, "FR"))
        assertNull(LocalizedLookup.value(map, "de"))
        assertNull(LocalizedLookup.value(null, "en"))
        assertNull(LocalizedLookup.value(emptyMap<String, String>(), "en"))
    }

    @Test
    fun lookupIsDeterministicWhenSeveralVariantsExist() {
        val map = linkedMapOf("pt_BR" to "br", "pt-PT" to "pt")
        // Sorted keys: "pt-PT" < "pt_BR" ('-' is 0x2D, '_' is 0x5F).
        assertEquals("pt", LocalizedLookup.value(map, "pt"))
        assertEquals("pt", LocalizedLookup.canonicalCode("PT_br"))
    }

    @Test
    fun localizedFallsBackToOriginal() {
        assertEquals("Hello", localized("Hello", mapOf("fr" to "Bonjour"), "de"))
        assertEquals("Bonjour", localized("Hello", mapOf("fr" to "Bonjour"), "fr"))
        assertNull(localized(null, null, "fr"))
    }

    // ── FS readers ──────────────────────────────────────────────────────────

    @Test
    fun boolAcceptsNumbers() {
        assertTrue(FS.bool(true))
        assertTrue(FS.bool(1L))
        assertTrue(FS.bool(2.0))
        assertFalse(FS.bool(0L))
        assertFalse(FS.bool(null))
        assertFalse(FS.bool("true"))
        assertNull(FS.boolOrNull(null))
        assertEquals(true, FS.boolOrNull(1))
    }

    @Test
    fun openStateReadsIntegersAndLegacyBooleans() {
        assertEquals(3, FS.openState(3L))
        assertEquals(2, FS.openState(true))
        assertEquals(0, FS.openState(false))
        assertEquals(4, FS.openState("4"))
        assertNull(FS.openState(null))
    }

    @Test
    fun millisReadsEveryTimestampShape() {
        assertEquals(1_700_000_000_000L, FS.millis(mapOf("_seconds" to 1_700_000_000L, "_nanoseconds" to 0L)))
        assertEquals(1_700_000_000_500L, FS.millis(mapOf("seconds" to 1_700_000_000L, "nanoseconds" to 500_000_000L)))
        assertEquals(1_700_000_000_000L, FS.millis(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, FS.millis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, FS.millis("2023-11-14T22:13:20Z"))
        assertNull(FS.millis("yesterday"))
        assertNull(FS.millis(null))
    }

    @Test
    fun geoReadsMapShapes() {
        assertEquals(12.5, FS.geoLat(mapOf("_latitude" to 12.5, "_longitude" to 7.0))!!, 0.0)
        assertEquals(7.0, FS.geoLon(mapOf("latitude" to 12.5, "longitude" to 7.0))!!, 0.0)
        assertNull(FS.geoLat("nope"))
    }

    @Test
    fun jsonSafeDropsNullsAndKeepsPlainValues() {
        val out = FS.jsonSafe(mapOf("a" to 1, "b" to null, "c" to mapOf("d" to null, "e" to "x"), "f" to listOf(1, null)))
        assertEquals(mapOf("a" to 1, "c" to mapOf("e" to "x"), "f" to listOf(1)), out)
    }

    // ── Overland enums ──────────────────────────────────────────────────────

    @Test
    fun absentOverlandingIsOpenAndAbsentCarnetIsNotRequired() {
        assertEquals(CountryOverlanding.OPEN, CountryOverlanding.fromRaw(null))
        assertEquals(CountryOverlanding.UNKNOWN, CountryOverlanding.fromRaw(9))
        assertEquals(CountryOverlanding.FORBIDDEN, CountryOverlanding.fromRaw(0))
        assertEquals(CountryCarnet.NOT_REQUIRED, CountryCarnet.fromRaw(null))
        assertEquals(CountryCarnet.ACCESS_FORBIDDEN, CountryCarnet.fromRaw(-1))
        assertEquals(CountryCarnet.UNKNOWN, CountryCarnet.fromRaw(7))
        assertEquals(CountryVisa.UNKNOWN, CountryVisa.fromRaw(null))
        assertEquals(CountryInsurance.UNKNOWN, CountryInsurance.fromRaw(null))
        assertEquals(InsuranceScheme.GREEN_CARD, InsuranceScheme.fromRaw("green_card"))
        assertNull(InsuranceScheme.fromRaw("white_card"))
        assertEquals(ZoneType.PERMIT_NEEDED, ZoneType.fromRaw(2))
        assertEquals(ZoneType.UNKNOWN, ZoneType.fromRaw(null))
        assertEquals(BorderOpenState.TEMPORARILY_CLOSED, BorderOpenState.fromRaw(4))
        assertEquals(BorderOpenState.UNKNOWN, BorderOpenState.fromRaw(null))
    }

    @Test
    fun countryReadsFirestoreKeys() {
        val c = Country.fromFirestore(
            "FRA",
            mapOf(
                "name" to "France",
                "iso_a2" to "FR",
                "translations" to mapOf("fr" to "France", "de" to "Frankreich"),
                "comment" to "Overview",
                "comment_translations" to mapOf("fr" to "Aperçu"),
                "visa_comment" to "visa",
                "carnet_comment" to "carnet",
                "insurance_comment" to "ins",
                "stay_duration" to "90 days",
                "stay_duration_translations" to mapOf("fr" to "90 jours"),
                "money" to "Euro",
                "tip" to "10%",
                "capital" to "Paris",
                "capital_translations" to mapOf("de" to "Paris"),
                "currency_symbol" to "€",
                "visa_url" to "https://v",
                "insurance_url" to "https://i",
                "insurance_scheme" to "green_card",
                "languages" to listOf("fr"),
                "borders" to mapOf("ES" to "Spain"),
                "bounding_box" to listOf(-5.0, 41.0, 9.5, 51.0),
                "visa" to "2",
            ),
        )
        assertEquals("Aperçu", c.comment("fr"))
        assertEquals("Overview", c.comment("de"))
        assertEquals("visa", c.visaComment("en"))
        assertEquals("carnet", c.carnetComment("en"))
        assertEquals("ins", c.insuranceComment("en"))
        assertEquals("90 jours", c.stayDuration("fr"))
        assertEquals("Euro", c.money("en"))
        assertEquals("10%", c.tip("en"))
        assertEquals("Paris", c.capital("de"))
        assertEquals("€", c.currencySymbol)
        assertEquals("https://v", c.visaUrl)
        assertEquals("https://i", c.insuranceUrl)
        assertEquals(InsuranceScheme.GREEN_CARD, c.insuranceScheme)
        assertEquals(listOf("fr"), c.languages)
        assertEquals(mapOf("ES" to "Spain"), c.bordersMap)
        assertEquals("Frankreich", c.name("de"))
        assertEquals(CountryVisa.E_VISA, c.visaStatus)
        val box = c.boundingBox!!
        assertEquals(41.0, box.latMin, 0.0)
        assertEquals(51.0, box.latMax, 0.0)
        assertEquals(-5.0, box.lonMin, 0.0)
        assertEquals(9.5, box.lonMax, 0.0)
    }

    @Test
    fun countryWithoutStatusesIsOpenAndNotRequired() {
        val c = Country.fromFirestore("XXX", mapOf("name" to "Nowhere"))
        assertEquals(CountryOverlanding.OPEN, c.overlandingStatus)
        assertEquals(CountryCarnet.NOT_REQUIRED, c.carnetStatus)
        assertEquals(CountryVisa.UNKNOWN, c.visaStatus)
        assertEquals("XXX", c.adm0A3)
    }

    @Test
    fun borderReadsLegacyBooleanOpenStateAndPostIds() {
        val b = CountryBorder.fromFirestore(
            "b1",
            mapOf(
                "name" to "FR-ES", "country1" to "FR", "country2" to "ES", "is_open" to true,
                "border_posts" to listOf("p1", "p2"), "comment" to "c",
                "comment_translations" to mapOf("fr" to "cf"),
            ),
        )
        assertEquals(BorderOpenState.OPEN, b.openState)
        assertEquals(listOf("p1", "p2"), b.borderPostIds)
        assertEquals("cf", b.comment("fr"))
        assertEquals("ES", b.oppositeCountryCode("FR"))
        assertEquals("FR", b.oppositeCountryCode("ES"))
        assertNull(b.oppositeCountryCode("DE"))

        val old = CountryBorder.fromFirestore("b2", mapOf("is_open" to 3L, "border_posts" to mapOf("z" to true, "a" to true)))
        assertEquals(BorderOpenState.RESTRICTIONS, old.openState)
        assertEquals(listOf("a", "z"), old.borderPostIds)
    }

    @Test
    fun borderPostDerivesLocationAndGeohash() {
        val p = BorderPost.fromFirestore(
            "bp",
            mapOf("name" to "Crossing", "is_open" to 4L, "lat" to 46.0, "lng" to 7.0, "borderId" to "b1"),
        )
        assertEquals(BorderOpenState.TEMPORARILY_CLOSED, p.openState)
        assertEquals(46.0, p.lat!!, 0.0)
        assertEquals(7.0, p.lon!!, 0.0)
        assertNotNull(p.geohash)
        assertEquals("b1", p.borderId)
        val explicit = BorderPost.fromFirestore("bp2", mapOf("latitude" to 1.0, "longitude" to 2.0, "geohash" to "s0"))
        assertEquals("s0", explicit.geohash)
    }

    @Test
    fun zoneAndClimateAndGlobalSetting() {
        val z = Zone.fromFirestore(
            "z", mapOf("name" to "Z", "country" to "IN", "type" to 2L, "comment" to "c", "bounding_box" to listOf(1, 2, 3, 4)),
        )
        assertEquals(ZoneType.PERMIT_NEEDED, z.zoneType)
        assertEquals(2.0, z.boundingBox!!.latMin, 0.0)
        assertEquals("c", z.comment("en"))
        val stored = z.stored()
        assertEquals(2, stored["type"])

        val cl = Climate.fromFirestore("c", mapOf("geohash" to "u0", "month" to 7L, "tempMin" to 3, "tempMax" to "12.5", "precipitation" to 40.0))
        assertEquals(7, cl.month)
        assertEquals(3.0, cl.temperatureMinC!!, 0.0)
        assertEquals(12.5, cl.temperatureMaxC!!, 0.0)
        assertEquals(40.0, cl.precipitationMm!!, 0.0)

        val g = GlobalSetting.fromFirestore("advisory_url_template", mapOf("gbr_en" to "https://x/{translation}", "updatedAt" to 1_700_000_000_000L))
        assertEquals(mapOf("gbr_en" to "https://x/{translation}"), g.advisoryUrlTemplates)
        assertEquals(1_700_000_000_000L, g.updatedAt)
    }

    // ── Social ──────────────────────────────────────────────────────────────

    @Test
    fun checkInNeedsObjectIdAndDefaultsToOther() {
        assertNull(CheckIn.fromFirestore("c1", mapOf("userId" to "u")))
        val c = CheckIn.fromFirestore(
            "c2",
            mapOf(
                "objectId" to "o", "trackPackId" to "p", "itinDocumentId" to "i", "userId" to "u",
                "userName" to "Ann", "collection" to "itinerary/i/steps", "content" to "ok",
                "createdAt" to mapOf("_seconds" to 1_700_000_000L, "_nanoseconds" to 0L),
                "location" to mapOf("latitude" to 1.5, "longitude" to 2.5),
                "upVotes" to 3L, "translatedDesc" to mapOf("fr" to "bon"),
            ),
        )!!
        assertEquals(CheckInReason.OTHER, c.reason)
        assertEquals(1_700_000_000_000L, c.createdAt)
        assertEquals("Ann", c.userName)
        assertEquals("itinerary/i/steps", c.collection)
        assertEquals("ok", c.content)
        assertEquals(1.5, c.lat!!, 0.0)
        assertEquals(2.5, c.lon!!, 0.0)
        assertEquals(3, c.upVotes)
        assertEquals(0, c.downVotes)
        assertEquals("bon", c.translation("fr"))
        assertFalse(c.reason.indicatesProblem)
        assertTrue(CheckInReason.fromRaw("closed").indicatesProblem)
        assertEquals(CheckInReason.CHECK, CheckInReason.fromRaw("check"))
        assertEquals(CheckInReason.OTHER, CheckInReason.fromRaw("???"))
    }

    @Test
    fun voteDefaultsToUpVoteAndReadsNumbers() {
        assertTrue(Vote.fromFirestore("v", emptyMap()).isUpVote)
        assertTrue(Vote.fromFirestore("v", mapOf("upVote" to true)).isUpVote)
        assertFalse(Vote.fromFirestore("v", mapOf("upVote" to false)).isUpVote)
        assertTrue(Vote.fromFirestore("v", mapOf("upVote" to 1L)).isUpVote)
        assertFalse(Vote.fromFirestore("v", mapOf("upVote" to 0L)).isUpVote)
        val v = Vote.fromFirestore("v", mapOf("objectID" to "o", "trackpackId" to "p", "userID" to "u", "userName" to "Bo"))
        assertEquals("o", v.objectId)
        assertEquals("p", v.trackPackId)
        assertEquals("u", v.userId)
        assertEquals("Bo", v.userName)
    }

    @Test
    fun contributedWaypointIsActiveWhenAbsent() {
        val w = ContributedWaypoint.fromFirestore(
            "w", mapOf("name" to "Spring", "lat" to 46.0, "lon" to 7.0, "hasFuel" to true, "isSwimming" to 1L, "type" to "water"),
            fallbackTrackPackId = "pack",
        )
        assertTrue(w.isActive)
        assertEquals("pack", w.trackPackId)
        assertNotNull(w.geohash)
        assertTrue(w.hasFuel)
        assertTrue(w.isSwimming)
        assertFalse(w.isWaterfall)
        assertEquals(46.0, w.lat!!, 0.0)
        assertFalse(ContributedWaypoint.fromFirestore("w", mapOf("active" to false)).isActive)
        assertFalse(ContributedWaypoint.fromFirestore("w", mapOf("active" to 0L)).isActive)
        assertTrue(ContributedWaypoint.fromFirestore("w", mapOf("active" to 1L)).isActive)
    }

    @Test
    fun contributedWaypointLocationFallsBackToGeohash() {
        val hash = ch.overlandmap.map.geo.Geohash.encode(46.5, 7.5)
        val w = ContributedWaypoint.fromFirestore("w", mapOf("geohash" to hash))
        assertEquals(46.5, w.lat!!, 0.001)
        assertEquals(7.5, w.lon!!, 0.001)
    }

    @Test
    fun storedMapRoundTripsThroughFromFirestore() {
        val original = ContributedWaypoint.fromFirestore(
            "w",
            mapOf(
                "name" to "N", "description" to "D", "translatedName" to mapOf("fr" to "Nf"),
                "lat" to 1.0, "lon" to 2.0, "ele" to 300L, "userId" to "u", "active" to true,
                "isBridge" to true, "openKind" to "closed", "openDetails" to "rockslide",
                "type" to "x", "trackPackId" to "p", "itinDocumentId" to "i", "lastUpdate" to 1_700_000_000_000L,
                "content" to "c", "langCode" to "en", "userName" to "Ann",
            ),
        )
        val again = ContributedWaypoint.fromFirestore("w", original.stored())
        assertEquals(original.copy(extra = emptyMap()), again.copy(extra = emptyMap()))
        assertEquals(original.extra, again.extra)
        assertEquals(OpenKind.CLOSED, again.openKind)
        assertEquals(300, again.ele)
    }

    @Test
    fun discussionAccessors() {
        val d = Discussion.fromFirestore(
            "d",
            mapOf(
                "objectId" to "o", "topic" to "bordercrossing", "userId" to "u", "reason" to "borderCrossing",
                "content" to "text", "userName" to "Ann", "lang" to "en", "threadId" to "t", "deleted" to true,
                "upVotes" to 2L, "border_vehicle" to "Motorcycle", "border_direction" to "in", "border_passport" to "CH",
                "border_time_spent" to 45L, "border_ammount_paid" to 12.5, "border_crossing_date" to 1_700_000_000_000L,
                "location" to mapOf("latitude" to 1.0, "longitude" to 2.0),
                "translations" to mapOf("fr" to "texte", "en" to "text-en"),
            ),
        )
        assertEquals(DiscussionTopic.BORDER_CROSSING, d.topic)
        assertEquals(DiscussionReason.BORDER_CROSSING, d.reason)
        assertTrue(d.isCrossingReport)
        assertFalse(d.isTestimonial)
        assertTrue(d.isDeleted)
        assertEquals("t", d.threadId)
        assertEquals(2, d.upVotes)
        assertEquals(CrossingVehicle.MOTO, d.crossingVehicle)
        assertEquals("in", d.crossingDirection)
        assertEquals("CH", d.crossingPassport)
        assertEquals(45, d.crossingTimeSpent)
        assertEquals("45", d.crossingTimeString)
        assertEquals(12.5, d.crossingAmountPaid!!, 0.0)
        assertEquals("12.5", d.crossingAmountString)
        assertEquals(1_700_000_000_000L, d.crossingDate)
        assertEquals(1.0, d.lat!!, 0.0)
        assertEquals("texte", d.displayContent("fr"))
        assertEquals("text-en", d.displayContent("de"))
        assertTrue(d.matches("BORDERCROSSING"))
    }

    @Test
    fun discussionEnumsResolveLeniently() {
        assertEquals(DiscussionTopic.GENERAL, DiscussionTopic.fromRaw(null))
        assertEquals(DiscussionTopic.OTHER, DiscussionTopic.fromRaw("nonsense"))
        assertEquals(DiscussionTopic.VEHICLE_INSURANCE, DiscussionTopic.fromRaw("VehicleInsurance"))
        assertEquals(22, DiscussionTopic.entries.size)
        assertEquals(16, DiscussionReason.entries.size)
        assertEquals(DiscussionReason.OTHER, DiscussionReason.fromRaw(null))
        assertEquals(DiscussionReason.FOLLOWUP, DiscussionReason.fromRaw("FollowUp"))
        assertFalse(DiscussionReason.inputReasons.contains(DiscussionReason.BORDER_CROSSING))
        assertEquals(CrossingVehicle.ON_FOOT, CrossingVehicle.fromRaw("On foot"))
        assertEquals(CrossingVehicle.OTHER, CrossingVehicle.fromRaw(null))
        assertEquals("on foot", CrossingVehicle.ON_FOOT.dbName)
        assertNull(CrossingVehicle.OTHER.dbName)
        val reply = Discussion.fromFirestore("r", mapOf("reason" to "followup", "threadId" to "t"))
        assertTrue(reply.isFollowup)
        assertFalse(Discussion.fromFirestore("r", mapOf("reason" to "followup")).isFollowup)
        assertTrue(Discussion.fromFirestore("r", mapOf("reason" to "Testimonial")).isTestimonial)
    }

    // ── Comment ─────────────────────────────────────────────────────────────

    @Test
    fun commentRatingIsClampedAndTranslationTolerant() {
        val c = Comment.fromFirestore(
            "c",
            mapOf(
                "objectId" to "o", "content" to "Hallo", "langCode" to "de", "rating" to 9L,
                "translations" to mapOf("pt-PT" to "Olá"), "englishTranslation" to "Hello",
                "trackPackId" to "p", "itinDocumentId" to "i", "userId" to "u", "collection" to "x",
            ),
        )
        assertEquals(5, c.ratingValue)
        assertEquals(0, Comment.fromFirestore("c", mapOf("rating" to -3L)).ratingValue)
        assertEquals(0, Comment.fromFirestore("c", emptyMap()).ratingValue)
        assertEquals("Olá", c.translation("pt"))
        assertEquals("Hello", c.translation("en"))
        assertNull(c.translation("fr"))
        assertEquals("Hallo", c.content("de"))
        assertEquals("Olá", c.content("pt"))
        assertEquals("Hallo", c.content("fr"))
        assertEquals("p", c.trackPackId)
        assertEquals("i", c.itinDocumentId)
        assertEquals("u", c.userId)
        assertEquals("x", c.extra["collection"])
    }

    // ── Waypoint flags & entitlements ───────────────────────────────────────

    @Test
    fun waypointAndStepReadAllEighteenFlags() {
        val data = WaypointFlags.KEYS.associateWith { true }
        val flags = WaypointFlags.from(data)
        assertEquals(WaypointFlags(true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true), flags)
        assertEquals(18, WaypointFlags.KEYS.size)
        val w = Waypoint.fromFirestore("w", data + ("name" to "n"))
        assertTrue(w.isWaterfall && w.isSwimming && w.isFerry && w.isCafe && w.hasFuel)
        val s = ItineraryStep.fromFirestore("s", "i", data + ("id" to 1L))
        assertTrue(s.isWaterfall && s.isSwimming && s.isFerry && s.isCafe && s.hasFuel)
    }

    @Test
    fun openKindUnknownStringsBecomeOther() {
        assertEquals(OpenKind.OTHER, OpenKind.fromRaw("whatever"))
        assertEquals(OpenKind.TEMP_CLOSED, OpenKind.fromRaw("tempClosed"))
        assertNull(OpenKind.fromRaw(null))
    }

    @Test
    fun entitlementsRecogniseSpecialIds() {
        fun p(id: String, purchased: Boolean = true, validityEnd: Long? = null) =
            UserPurchase(id, null, null, purchased, null, validityEnd, null)
        val none = Entitlements()
        assertFalse(none.hasAdmin || none.hasBeta || none.hasPro())
        val e = Entitlements(listOf(p("Admin"), p("BETA"), p("pro", validityEnd = 2_000), p("packA")))
        assertTrue(e.hasAdmin)
        assertTrue(e.hasBeta)
        assertTrue(e.hasPro(now = 1_000))
        assertFalse(e.hasPro(now = 3_000))
        assertTrue(e.owns("packA"))
        assertFalse(e.owns("packB"))
        assertFalse(Entitlements(listOf(p("pro"))).hasPro(now = 1))
        assertFalse(Entitlements(listOf(p("packA", purchased = false))).owns("packA"))
    }

    @Test
    fun itineraryLengthDaysIsAnInteger() {
        assertEquals(3, Itinerary.fromFirestore("i", mapOf("name" to "x", "lengthDays" to 3.0)).lengthDays)
        assertEquals(0, Itinerary.fromFirestore("i", mapOf("name" to "x")).lengthDays)
    }
}
