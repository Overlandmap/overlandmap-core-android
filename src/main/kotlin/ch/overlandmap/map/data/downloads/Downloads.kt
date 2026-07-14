package ch.overlandmap.map.data.downloads

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The backend refused a download (e.g. the pack was not purchased). Not a
 * transient failure: workers give up instead of retrying.
 */
class DownloadRefusedException(message: String) : IOException(message)

/**
 * Streaming download and unzip primitives shared by the download workers.
 * Downloads write to `{file}.part` and resume it with a Range request when
 * interrupted, so a worker retry continues where the transfer stopped.
 */
object Downloads {

    private const val BUFFER_SIZE = 64 * 1024
    private const val REPORT_EVERY_BYTES = 256L * 1024

    /**
     * Downloads [url] into [file]. [onProgress] receives the total bytes
     * transferred so far (including a resumed prefix). When [expectedSha256]
     * is given the finished file is verified and deleted on mismatch.
     */
    fun download(
        url: String,
        file: File,
        expectedSha256: String? = null,
        onProgress: (Long) -> Unit = {},
    ) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        val resumeFrom = if (part.isFile) part.length() else 0L

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            val code = connection.responseCode
            val append = when {
                code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0 -> true
                code == HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("HTTP $code for ${file.name}")
            }
            var transferred = if (append) resumeFrom else 0L
            var sinceReport = 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        transferred += read
                        sinceReport += read
                        if (sinceReport >= REPORT_EVERY_BYTES) {
                            sinceReport = 0
                            onProgress(transferred)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (expectedSha256 != null) {
            val actual = sha256(part)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                part.delete()
                throw IOException("Checksum mismatch for ${file.name}")
            }
        }
        file.delete()
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            part.delete()
        }
    }

    /** Unzips [zip] into [targetDir], guarding against zip-slip entries. */
    fun unzip(zip: File, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(targetDir, entry.name)
                if (!target.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                    throw IOException("Illegal zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
