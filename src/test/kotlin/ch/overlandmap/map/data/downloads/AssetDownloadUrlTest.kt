package ch.overlandmap.map.data.downloads

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AssetDownloadUrlTest {
    @Test fun resolvesCountriesStoragePath() = runBlocking {
        val path = "countries/entity-dump-2026-08-29.json.gz"
        val resolved = resolveAssetDownloadUrl(path) {
            assertEquals(path, it)
            "https://example.com/countries.json.gz?token=download-token"
        }
        assertEquals("https://example.com/countries.json.gz?token=download-token", resolved)
    }

    @Test fun preservesDirectUrlsIncludingSignedQuery() = runBlocking {
        for (url in listOf("https://example.com/map.pmtiles?signature=a%2Bb", "http://example.com/map", "HTTPS://example.com/map")) {
            assertEquals(url, resolveAssetDownloadUrl(url) { error("HTTP URLs must not use Storage resolution") })
        }
    }

    @Test fun resolvesExplicitBucketReference() = runBlocking {
        val path = "gs://example-bucket/countries/dump.json.gz"
        assertEquals("https://example.com/dump", resolveAssetDownloadUrl(path) {
            assertEquals(path, it)
            "https://example.com/dump"
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyValue() { runBlocking { resolveAssetDownloadUrl(" ") { error("Not a Storage path") } } }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedScheme() { runBlocking { resolveAssetDownloadUrl("ftp://example.com/dump") { error("Not a Storage path") } } }
}
