package srv.scrtvwr.media.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HttpDirectoryClientTest {
    @Test
    fun displayListing_doesNotWaitForStalledBackgroundListing() = runBlocking {
        StalledListingServer().use { server ->
            val background = async(Dispatchers.IO) { HttpDirectoryClient.list(server.baseUrl) }
            try {
                assertTrue(server.firstRequestStarted.await(2, TimeUnit.SECONDS))

                val entries = withTimeout(2_000) { HttpDirectoryClient.listForDisplay(server.baseUrl) }

                assertEquals(listOf("photo.jpg"), entries.map(RemoteEntry::name))
                assertEquals(2, server.requests.get())
            } finally {
                server.releaseFirstRequest.countDown()
                background.await()
            }
        }
    }

    @Test
    fun displayListing_reusesCompletedListingWithoutFetchingFileDetails() = runBlocking {
        TestHttpServer(mapOf("photo.jpg" to ByteArray(1))).use { server ->
            val first = HttpDirectoryClient.listForDisplay(server.baseUrl)
            val second = HttpDirectoryClient.listForDisplay(server.baseUrl)

            assertEquals(first, second)
            assertEquals(1, server.directoryRequests)
            assertEquals(0, server.fileRequests)
        }
    }

    @Test
    fun displayListing_explicitRefreshDownloadsListingAgain() = runBlocking {
        TestHttpServer(mapOf("photo.jpg" to ByteArray(1))).use { server ->
            HttpDirectoryClient.listForDisplay(server.baseUrl)
            HttpDirectoryClient.listForDisplay(server.baseUrl, refresh = true)

            assertEquals(2, server.directoryRequests)
            assertEquals(0, server.fileRequests)
        }
    }

    @Test(timeout = 10_000)
    fun displayListing_handlesLargeDirectoryWithoutFetchingFileDetails() = runBlocking {
        val files = (1..10_000).associate { "photo $it.jpg" to ByteArray(0) }
        TestHttpServer(files).use { server ->
            val entries = HttpDirectoryClient.listForDisplay(server.baseUrl)

            assertEquals(files.size, entries.size)
            assertEquals(files.keys, entries.map(RemoteEntry::name).toSet())
            assertEquals(1, server.directoryRequests)
            assertEquals(0, server.fileRequests)
        }
    }
}

/** Holds the first request open while allowing a second request to complete immediately. */
private class StalledListingServer : Closeable {
    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<Socket>()
    val baseUrl = "http://127.0.0.1:${listener.localPort}/"
    val requests = AtomicInteger()
    val firstRequestStarted = CountDownLatch(1)
    val releaseFirstRequest = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "stalled-listing-server") {
            while (!listener.isClosed) {
                val client = try {
                    listener.accept()
                } catch (_: IOException) {
                    break
                }
                clients.add(client)
                thread(isDaemon = true, name = "listing-response") {
                    client.use {
                        try {
                            respond(it)
                        } catch (_: IOException) {
                            // Cancellation and fixture shutdown may close a connection early.
                        } finally {
                            clients.remove(it)
                        }
                    }
                }
            }
        }
    }

    private fun respond(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        while (!input.readLine().isNullOrEmpty()) {
            // Consume the request line and headers before replying.
        }
        if (requests.incrementAndGet() == 1) {
            firstRequestStarted.countDown()
            check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
        }
        val body = "<a href=\"photo.jpg\">photo.jpg</a>".toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(headers.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    override fun close() {
        releaseFirstRequest.countDown()
        listener.close()
        clients.forEach(Socket::close)
    }
}
