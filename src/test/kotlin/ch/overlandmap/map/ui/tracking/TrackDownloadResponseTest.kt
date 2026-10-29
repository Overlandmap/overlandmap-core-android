package ch.overlandmap.map.ui.tracking

import org.junit.Assert.*
import org.junit.Test

class TrackDownloadResponseTest {
    @Test fun acceptsGpxAndZipResponseTypesWithoutFileExtensions() {
        assertTrue(isTrackFileResponse("application/gpx+xml; charset=utf-8", null))
        assertTrue(isTrackFileResponse("application/zip", null))
        assertTrue(isTrackFileResponse("application/x-zip-compressed", null))
    }

    @Test fun acceptsFileNamesDeclaredByTheResponse() {
        assertTrue(isTrackFileResponse("application/octet-stream", "attachment; filename=route.GPX"))
        assertTrue(isTrackFileResponse("application/octet-stream", "attachment; filename=\"tracks.zip\""))
        assertTrue(isTrackFileResponse("application/octet-stream", "attachment; filename*=UTF-8''my%20route.gpx"))
        assertTrue(isTrackFileResponse("text/plain", null, "generated.gpx"))
    }

    @Test fun ignoresOtherFilesAndHtmlIncludingLoginResponses() {
        assertFalse(isTrackFileResponse("application/pdf", "attachment; filename=guide.pdf"))
        assertFalse(isTrackFileResponse("image/png", null))
        assertFalse(isTrackFileResponse("application/octet-stream", null))
        assertFalse(isTrackFileResponse("text/html", "attachment; filename=route.gpx"))
        assertFalse(isTrackFileResponse("text/html", null, "route.zip"))
        assertFalse(isTrackFileResponse(null, "attachment; filename=route.gpx.html"))
    }
    @Test fun recognizesActualGpxAndZipBodiesWhenHeadersAreGeneric() {
        assertTrue(looksLikeTrackFile("<?xml version='1.0'?>\n<!-- exported track -->\n<gpx version='1.1'>".toByteArray()))
        assertTrue(looksLikeTrackFile("<gps:gpx xmlns:gps='http://www.topografix.com/GPX/1/1'>".toByteArray()))
        assertTrue(looksLikeTrackFile(byteArrayOf(0x50, 0x4b, 3, 4)))
        assertFalse(looksLikeTrackFile("<html><body>Sign in to download</body></html>".toByteArray()))
        assertFalse(looksLikeTrackFile("<!-- <gpx> --><other/>".toByteArray()))
        assertFalse(looksLikeTrackFile("<gpx-not-a-track/>".toByteArray()))
        assertFalse(looksLikeTrackFile("%PDF-1.7".toByteArray()))
    }

}
