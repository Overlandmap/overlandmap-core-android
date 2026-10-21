package ch.overlandmap.map.ui.shop

import ch.overlandmap.map.data.PackDownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class PackStatusTest {
    @Test
    fun statusFollowsIosTileStates() {
        assertEquals(PackStatus.NONE, packStatus(false, null, null))
        assertEquals(PackStatus.DOWNLOADABLE, packStatus(true, null, null))
        assertEquals(PackStatus.LOCAL, packStatus(true, false, null))
        assertEquals(PackStatus.UPDATABLE, packStatus(true, true, null))
        assertEquals(PackStatus.DOWNLOADING, packStatus(true, null, PackDownloadProgress(fraction = 0.3f)))
        assertEquals(PackStatus.FAILED, packStatus(true, null, PackDownloadProgress(error = "x")))
        // A finished download no longer counts as running.
        assertEquals(PackStatus.LOCAL, packStatus(true, false, PackDownloadProgress(done = true)))
    }
}
