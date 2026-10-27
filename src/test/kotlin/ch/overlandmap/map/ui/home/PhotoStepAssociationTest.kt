package ch.overlandmap.map.ui.home

import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import org.junit.Assert.*
import org.junit.Test

class PhotoStepAssociationTest {
    @Test fun galleryKeepsStepLinksAndTranslatedCaptionsWithoutLinkingItineraryPhotos() {
        val step = ItineraryStep("step", "itin", "pack", 7, "Pass",
            translatedName = mapOf("fr" to "Col"), localPhotoPath = "/step.jpg",
            titlePhotoCaption = "Original", translatedTitleCaption = mapOf("fr" to "Traduction"))
        val itinerary = Itinerary("itin", "pack", name = "Route", localPhotoPath = "/title.jpg",
            titlePhotoCaption = "Title", localOtherPhotoPaths = listOf("/other.jpg"),
            otherPhotoCaptions = listOf("Other"))
        val photos = itineraryViewerPhotos(ItineraryState(itinerary = itinerary, steps = listOf(step)), "fr")
        assertEquals(3, photos.size)
        assertNull(photos[0].sourceStep)
        assertNull(photos[1].sourceStep)
        assertEquals("Title", photos[0].caption)
        assertEquals("Other", photos[1].caption)
        assertEquals(step, photos[2].sourceStep)
        assertEquals("Traduction", photos[2].caption)
        assertEquals("7. Col", photos[2].sourceStep!!.fullName("fr"))
    }

    @Test fun sharedItineraryPhotoRemainsUnlinkedAndMissingPhotosAreSkipped() {
        val itinerary = Itinerary("itin", "pack", name = "Route", localPhotoPath = "/shared.jpg")
        val steps = listOf(ItineraryStep("s1", "itin", "pack", 1, "First", localPhotoPath = "/shared.jpg"),
            ItineraryStep("s2", "itin", "pack", 2, "No photo"))
        val photos = itineraryViewerPhotos(ItineraryState(itinerary = itinerary, steps = steps), "en")
        assertEquals(1, photos.size)
        assertNull(photos.single().sourceStep)
    }
}
