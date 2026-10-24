package ch.overlandmap.map.data.local

import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Sidebar
import org.junit.Assert.*
import org.junit.Test

class PhotoCaptionsTest {
    private val data = mapOf<String, Any?>(
        "name" to "Place", "titlePhotoCaption" to "Original title",
        "translatedTitleCaption" to mapOf("fr" to "Titre traduit", "pt-PT" to "Legenda"),
        "otherPhotoCaptions" to listOf("First", "Second"),
        "translatedOtherPhotoCaptions" to mapOf("fr" to listOf("Première", "")),
    )

    @Test fun stepTitleTranslationSurvivesOfflineStorage() {
        val step = ItineraryStep.fromFirestore("s", "i", data).toRow().toModel()
        assertEquals("Titre traduit", step.titlePhotoCaption("fr"))
        assertEquals("Legenda", step.titlePhotoCaption("pt"))
        assertEquals("Original title", step.titlePhotoCaption("de"))
    }

    @Test fun itineraryCaptionsKeepPhotoIndexesAndFallback() {
        val itinerary = Itinerary.fromFirestore("i", data).toRow().toModel()
        assertEquals("Titre traduit", itinerary.titlePhotoCaption("fr"))
        assertEquals("Première", itinerary.otherPhotoCaption(0, "fr"))
        assertEquals("Second", itinerary.otherPhotoCaption(1, "fr"))
        assertEquals("First", itinerary.otherPhotoCaption(0, "de"))
        assertNull(itinerary.otherPhotoCaption(2, "fr"))
    }

    @Test fun sidebarCaptionsSurviveOfflineStorage() {
        val sidebar = Sidebar.fromFirestore("article", data).toRow().toModel()
        assertEquals("Titre traduit", sidebar.titlePhotoCaption("fr"))
        assertEquals("Première", sidebar.otherPhotoCaption(0, "fr"))
        assertEquals("Second", sidebar.otherPhotoCaption(1, "fr"))
    }

    @Test fun oldCachedStepReadsTranslationsFromExtras() {
        val row = ItineraryStep.fromFirestore("s", "i", data).toRow()
        val legacy = row.copy(json = """{"titlePhotoCaption":"Original title","extra":{"translatedTitleCaption":{"fr":"Ancienne traduction"}}}""")
        assertEquals("Ancienne traduction", legacy.toModel().titlePhotoCaption("fr"))
    }
}
