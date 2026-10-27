package ch.overlandmap.map.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultIdentityTest {
    @Test fun duplicateIndexHitsCannotCreateDuplicateListKeys() {
        val first = SearchResult("Waypoint", "same", "First", "Caption")
        val step = SearchResult("ItineraryStep", "same", "Step", "")
        assertEquals(listOf(first, step), SearchRepository.uniqueResults(listOf(first, first.copy(name = "Duplicate"), step)))
    }

    @Test fun differentTypeAndIdBoundariesAreNotMerged() {
        val a = SearchResult("ab", "c", "One", "")
        val b = SearchResult("a", "bc", "Two", "")
        assertEquals(listOf(a, b), SearchRepository.uniqueResults(listOf(a, b)))
    }
}
