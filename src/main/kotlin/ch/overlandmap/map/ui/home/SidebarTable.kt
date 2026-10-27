package ch.overlandmap.map.ui.home

import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.FS
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryDifficulty
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.OpenKind
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.model.WaypointType
import ch.overlandmap.map.model.localized
import ch.overlandmap.map.units.UnitFormatter

/** What a tap on a cell's text does (iOS `SidebarTableCell.Link`). */
sealed interface CellLink {
    /** Opens the popup of the row's own object (a waypoint, a step or an itinerary). */
    object OwnEntity : CellLink

    /** Opens the popup of the itinerary with this document id. */
    data class Itinerary(val documentId: String) : CellLink
}

/** How a cell is tinted. */
enum class CellTone { NONE, GOOD, NORMAL, WARNING, BAD, ACCENT, MUTED }

data class SidebarCell(val text: String, val tone: CellTone = CellTone.NONE, val link: CellLink? = null)

/** One table row, with what the map and the popups need to show the same object. */
data class SidebarRow(
    val id: String,
    val lat: Double?,
    val lon: Double?,
    /** An itinerary row zooms to its extent rather than a point. */
    val bounds: GeoBounds?,
    val elevation: Int?,
    val cells: List<SidebarCell>,
    val itinerary: Itinerary?,
    val title: String,
    val openKind: OpenKind?,
    val openDetails: String?,
    /** The underlying [Waypoint], [ItineraryStep] or [Itinerary], for its popup. */
    val source: Any?,
)

/** The labels the table needs that live in string resources; implemented by the UI. */
interface SidebarLabels {
    fun openKind(kind: OpenKind): String
    fun difficulty(difficulty: ItineraryDifficulty): String
    fun offroad(percent: Int?): String
}

/**
 * Builds a sidebar's table from the pack's objects (iOS `SidebarViewer.buildTableRows`): the
 * entity kind (`Waypoint` — which also takes steps —, `ItineraryStep` or `Itinerary`) picks the
 * objects, `tableFilter` or the `sidebarDocId` marker selects them, `tableSort` orders them and
 * each column definition turns an object into a cell.
 */
object SidebarTable {

    /** Map membership is independent of table columns and table flag filters. */
    fun buildForMap(
        sidebar: Sidebar, waypoints: List<Waypoint>, steps: List<ItineraryStep>, itineraries: List<Itinerary>,
        units: UnitFormatter, lang: String, labels: SidebarLabels,
    ): List<SidebarRow> {
        fun belongs(packId: String, extra: Map<String, Any?>) =
            packId == sidebar.trackPackId && FS.str(extra["sidebarDocId"]) == sidebar.documentId
        return build(
            sidebar.copy(extra = sidebar.extra - "tableFilter"),
            waypoints.filter { belongs(it.trackPackId, it.extra) },
            if (sidebar.tableEntity == "ItineraryStep") steps.filter { belongs(it.trackPackId, it.extra) } else emptyList(),
            itineraries.filter { belongs(it.trackPackId, it.extra) }, units, lang, labels,
        )
    }

    /** An object flattened to the fields the columns can ask for. */
    private class Entry(
        val id: String,
        val lat: Double?,
        val lon: Double?,
        val bounds: GeoBounds?,
        val elevation: Int?,
        val extra: Map<String, Any?>,
        val name: String,
        val translatedName: Map<String, String>?,
        val description: String?,
        val translatedDesc: Map<String, String>?,
        val itineraryDocumentId: String?,
        val difficulty: ItineraryDifficulty?,
        val offroadPercent: Int?,
        val itinerary: Itinerary?,
        val openKind: OpenKind?,
        val openDetails: String?,
        val source: Any?,
        val matchesFlag: (String) -> Boolean,
    ) {
        fun displayName(lang: String) = localized(name, translatedName, lang) ?: name
        fun displayDescription(lang: String) = localized(description, translatedDesc, lang)
    }

    fun columnTitles(sidebar: Sidebar, lang: String): List<String> =
        sidebar.tableColumns.orEmpty().map { column ->
            (column["title"] as? Map<*, *>)?.get(lang) as? String ?: ""
        }

    fun build(
        sidebar: Sidebar,
        waypoints: List<Waypoint>,
        steps: List<ItineraryStep>,
        itineraries: List<Itinerary>,
        units: UnitFormatter,
        lang: String,
        labels: SidebarLabels,
    ): List<SidebarRow> {
        val columns = sidebar.tableColumns.orEmpty()
        val filter = sidebar.tableFilter
        val entries = ArrayList<Entry>()

        /** Without a filter, an object belongs to the sidebar that names it; with one, it must match. */
        fun selected(extra: Map<String, Any?>, matches: (String) -> Boolean) =
            if (filter != null) matches(filter) else FS.str(extra["sidebarDocId"]) == sidebar.documentId

        fun typeEntry(o: WaypointType, extra: Map<String, Any?>, maki: String?) = Entry(
            source = o,
            id = o.documentId, lat = o.lat, lon = o.lon, bounds = null, elevation = o.ele, extra = extra,
            name = o.name, translatedName = o.translatedName, description = o.description,
            translatedDesc = o.translatedDesc,
            itineraryDocumentId = (o as? Waypoint)?.itineraryId ?: (o as? ItineraryStep)?.itineraryId,
            difficulty = null, offroadPercent = null, itinerary = null,
            openKind = o.openKind, openDetails = o.openDetails,
            matchesFlag = { f -> flagMatches(o, extra, maki, f) },
        )

        when (sidebar.tableEntity ?: "Waypoint") {
            "Itinerary" -> itineraries.forEach { itin ->
                val matches = { f: String -> FS.boolOrNull(itin.extra[f]) == true }
                if (!selected(itin.extra, matches)) return@forEach
                val bounds = if (itin.latMin != null && itin.latMax != null && itin.lonMin != null && itin.lonMax != null) {
                    GeoBounds(itin.latMin, itin.latMax, itin.lonMin, itin.lonMax)
                } else null
                entries += Entry(
                    source = itin,
                    id = itin.documentId, lat = null, lon = null, bounds = bounds, elevation = null,
                    extra = itin.extra, name = itin.name, translatedName = itin.translatedName,
                    description = itin.description, translatedDesc = itin.translatedDesc,
                    itineraryDocumentId = itin.documentId,
                    difficulty = ItineraryDifficulty.fromRaw(itin.difficulty), offroadPercent = itin.offroadPercent,
                    itinerary = itin, openKind = null, openDetails = null, matchesFlag = matches,
                )
            }
            "ItineraryStep" -> steps.forEach { step ->
                val e = typeEntry(step, step.extra, null)
                if (selected(step.extra, e.matchesFlag)) entries += e
            }
            else -> {
                waypoints.forEach { wp ->
                    val e = typeEntry(wp, wp.extra, wp.maki)
                    if (selected(wp.extra, e.matchesFlag)) entries += e
                }
                steps.forEach { step ->
                    val e = typeEntry(step, step.extra, null)
                    if (selected(step.extra, e.matchesFlag)) entries += e
                }
            }
        }

        sidebar.tableSort?.let { (field, descending) ->
            val sorted = entries.sortedBy { sortValue(it, field) }
            entries.clear()
            entries += if (descending) sorted.reversed() else sorted
        }

        val itinerariesByDoc = itineraries.associateBy { it.documentId }
        return entries.map { entry ->
            SidebarRow(
                id = entry.id, lat = entry.lat, lon = entry.lon, bounds = entry.bounds,
                elevation = entry.elevation,
                cells = columns.map { cell(it, entry, itinerariesByDoc, units, lang, labels) },
                itinerary = entry.itinerary, title = entry.displayName(lang),
                openKind = entry.openKind, openDetails = entry.openDetails, source = entry.source,
            )
        }
    }

    private fun cell(
        column: Map<String, Any?>,
        obj: Entry,
        itinerariesByDoc: Map<String, Itinerary>,
        units: UnitFormatter,
        lang: String,
        labels: SidebarLabels,
    ): SidebarCell {
        val type = FS.str(column["type"]) ?: "text"
        val field = FS.str(column["field"])?.let { if (it == "description") "translatedDesc" else it }
        return when (type) {
            "text", "translated" -> {
                if (field == null || field == "name" || field == "translatedName") {
                    return SidebarCell(obj.displayName(lang), link = CellLink.OwnEntity)
                }
                if (field == "translatedDesc") {
                    obj.displayDescription(lang)?.takeIf { it.isNotEmpty() }
                        ?.let { return SidebarCell(it, link = CellLink.OwnEntity) }
                    return SidebarCell(extraText(obj.extra["translatedDesc"] ?: obj.extra["description"], lang), link = CellLink.OwnEntity)
                }
                SidebarCell(extraText(obj.extra[field], lang), link = CellLink.OwnEntity)
            }
            "openKind" -> {
                val kind = obj.openKind ?: field?.let { OpenKind.fromRaw(FS.str(obj.extra[it])) }
                if (kind == null) SidebarCell("") else SidebarCell(labels.openKind(kind), openKindTone(kind))
            }
            "difficulty" -> {
                val difficulty = obj.difficulty
                    ?: field?.let { FS.int(obj.extra[it]) }?.let { ItineraryDifficulty.entries.getOrNull(it) }
                if (difficulty == null) SidebarCell("") else SidebarCell(labels.difficulty(difficulty), difficultyTone(difficulty))
            }
            "offroadPercent" -> {
                val percent = obj.offroadPercent ?: field?.let { FS.int(obj.extra[it]) }
                SidebarCell(labels.offroad(percent), offroadTone(percent))
            }
            "elevation" -> {
                val custom = if (field != null && field != "ele") FS.double(obj.extra[field]) else null
                when {
                    custom != null -> SidebarCell(units.meters(custom))
                    obj.elevation != null -> SidebarCell(units.meters(obj.elevation.toDouble()))
                    else -> SidebarCell("")
                }
            }
            "distance" -> {
                val km = field?.let { FS.double(obj.extra[it]) }
                if (km != null) SidebarCell(units.distanceKm(km)) else SidebarCell("")
            }
            "itineraryId" -> {
                val documentId = field?.let { FS.str(obj.extra[it]) } ?: obj.itineraryDocumentId
                val itinerary = documentId?.let { itinerariesByDoc[it] }
                if (documentId == null || itinerary == null) SidebarCell("")
                else SidebarCell(itinerary.itineraryId, link = CellLink.Itinerary(documentId))
            }
            else -> SidebarCell("")
        }
    }

    /** A string field, or a translations map resolved in [lang], then English, then any value. */
    private fun extraText(raw: Any?, lang: String): String = when (raw) {
        is String -> raw
        is Map<*, *> -> (raw[lang] ?: raw["en"] ?: raw.values.firstOrNull())?.toString().orEmpty()
        else -> ""
    }

    fun openKindTone(kind: OpenKind) = when (kind) {
        OpenKind.OPEN -> CellTone.GOOD
        OpenKind.CLOSED -> CellTone.BAD
        OpenKind.TEMP_CLOSED, OpenKind.RESTRICTED -> CellTone.WARNING
        OpenKind.PERMIT, OpenKind.TOLL -> CellTone.ACCENT
        OpenKind.OTHER -> CellTone.NONE
    }

    fun difficultyTone(d: ItineraryDifficulty) = when (d) {
        ItineraryDifficulty.EASY -> CellTone.GOOD
        ItineraryDifficulty.NORMAL -> CellTone.NORMAL
        ItineraryDifficulty.HARD -> CellTone.WARNING
        ItineraryDifficulty.EXTREME -> CellTone.BAD
    }

    /** iOS `OffroadDisplay.displayColor`: 0 green, 75 orange, 100 red, unknown grey, else the accent. */
    fun offroadTone(percent: Int?) = when (percent) {
        0 -> CellTone.GOOD
        75 -> CellTone.WARNING
        100 -> CellTone.BAD
        null -> CellTone.MUTED
        else -> CellTone.ACCENT
    }

    private fun sortValue(e: Entry, field: String): Double = when (field) {
        "ele", "elevation" -> (e.elevation ?: 0).toDouble()
        "offroadPercent" -> (e.offroadPercent ?: 0).toDouble()
        "difficulty" -> (e.difficulty?.let { ItineraryDifficulty.entries.indexOf(it) } ?: 0).toDouble()
        else -> FS.double(e.extra[field]) ?: 0.0
    }

    /** The `tableFilter` names a flag (several spellings), a maki icon, or a boolean extra field. */
    fun flagMatches(o: WaypointType, extra: Map<String, Any?>, maki: String?, filter: String): Boolean {
        if (FS.boolOrNull(extra[filter]) == true) return true
        if (FS.str(extra[filter])?.lowercase() == "true") return true
        if (maki != null && maki.equals(filter, ignoreCase = true)) return true
        return when (filter) {
            "isMountainPass", "mountainPass", "mountain_pass" -> o.isMountainPass
            "hasFuel", "isFuel", "fuel", "gas_station" -> o.hasFuel
            "hasHotel", "isHotel", "hotel", "lodging" -> o.hasHotel
            "isViewpoint", "viewpoint" -> o.isViewpoint
            "isBivouac", "bivouac", "campSite", "camp_site" -> o.isBivouac
            "isPoliceCheckpoint", "policeCheckpoint", "police" -> o.isPoliceCheckpoint
            "isBorder", "border" -> o.isBorder
            "isEmbassy", "embassy" -> o.isEmbassy
            "isBridge", "bridge" -> o.isBridge
            "isWaterCrossing", "waterCrossing", "water_crossing" -> o.isWaterCrossing
            "isWaterfall", "waterfall" -> o.isWaterfall
            "isSwimming" -> o.isSwimming
            "isHistoricalSite", "historicalSite", "historic" -> o.isHistoricalSite
            "isReligiousSite", "religiousSite", "religious" -> o.isReligiousSite
            "isHotSpring", "hotSpring", "hot_spring" -> o.isHotSpring
            "isIntersection", "intersection" -> o.isIntersection
            "isFerry", "ferry" -> o.isFerry
            "isCafe", "hasCafe", "cafe" -> o.isCafe
            else -> false
        }
    }
}
