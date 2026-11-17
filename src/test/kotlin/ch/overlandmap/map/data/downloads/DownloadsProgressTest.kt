package ch.overlandmap.map.data.downloads

import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadsProgressTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = ByteArray(1024) { (it % 251).toByte() }

    @Test fun reportsActualLengthAndResumedBytes() = verifyResponse(resume = true, honorRange = true)

    @Test fun restartsProgressWhenServerIgnoresRange() = verifyResponse(resume = true, honorRange = false)

    @Test fun leavesLengthUnknownForChunkedResponses() = verifyResponse(resume = false, honorRange = false, chunked = true)

    private fun verifyResponse(resume: Boolean, honorRange: Boolean, chunked: Boolean = false) {
        val target = temporary.newFile("asset")
        if (resume) target.resolveSibling("asset.part").writeBytes(bytes.copyOfRange(0, 200))
        ServerSocket(0).use { server ->
            var request = ""
            val worker = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers += line
                    }
                    request = headers.joinToString("\n")
                    val data = if (honorRange) bytes.copyOfRange(200, bytes.size) else bytes
                    val output = socket.getOutputStream()
                    val status = if (honorRange) "206 Partial Content" else "200 OK"
                    val sizeHeader = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: ${data.size}"
                    output.write("HTTP/1.1 $status\r\n$sizeHeader\r\nConnection: close\r\n\r\n".toByteArray())
                    if (chunked) output.write("${data.size.toString(16)}\r\n".toByteArray())
                    output.write(data)
                    if (chunked) output.write("\r\n0\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
            var length: Long? = 42
            val progress = mutableListOf<Long>()
            Downloads.download("http://127.0.0.1:${server.localPort}/asset", target,
                onLength = { length = it }, onProgress = { progress += it })
            worker.join()
            if (resume) assertEquals(true, request.contains("Range: bytes=200-", ignoreCase = true))
            if (chunked) assertNull(length) else assertEquals(bytes.size.toLong(), length)
            assertEquals(listOf(if (honorRange) 200L else 0L, bytes.size.toLong()), progress)
            assertArrayEquals(bytes, target.readBytes())
        }
    }
}
