package com.henry.encodec.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

internal object HttpsStreams {
    private const val MAX_REDIRECTS = 5
    private const val MAX_OPEN_ATTEMPTS = 3

    /** Finite playlist URLs remain HTTPS-only at their initial address. */
    fun open(url: String): InputStream {
        var lastError: IOException? = null
        repeat(MAX_OPEN_ATTEMPTS) { attempt ->
            try {
                return openOnce(url, noCache = false, allowInitialHttp = false)
            } catch (error: IOException) {
                lastError = error
                if (attempt < MAX_OPEN_ATTEMPTS - 1) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Could not open the stream")
    }

    /** Cancellable, bounded HTTP(S) download used by live manifests and segments. */
    suspend fun readBytes(url: String, maxBytes: Int, noCache: Boolean = false): ByteArray =
        withContext(Dispatchers.IO) {
            require(maxBytes > 0)
            val activeConnection = AtomicReference<HttpURLConnection?>()
            val cancellation = coroutineContext.job.invokeOnCompletion {
                activeConnection.getAndSet(null)?.disconnect()
            }
            try {
                var lastError: IOException? = null
                repeat(MAX_OPEN_ATTEMPTS) { attempt ->
                    coroutineContext.ensureActive()
                    try {
                        openOnce(
                            url = url,
                            noCache = noCache,
                            allowInitialHttp = true,
                            onConnection = activeConnection::set,
                        ).use { input ->
                            val declaredLength = activeConnection.get()?.contentLengthLong ?: -1L
                            if (declaredLength > maxBytes) {
                                throw IOException("HTTP response exceeds $maxBytes bytes")
                            }
                            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (output.size() + read > maxBytes) {
                                    throw IOException("HTTP response exceeds $maxBytes bytes")
                                }
                                output.write(buffer, 0, read)
                            }
                            val result = output.toByteArray()
                            // A fully consumed response can return its socket to
                            // HttpURLConnection's keep-alive pool. This avoids a
                            // fresh DNS lookup and TLS handshake per segment.
                            activeConnection.set(null)
                            return@withContext result
                        }
                    } catch (error: IOException) {
                        coroutineContext.ensureActive()
                        activeConnection.getAndSet(null)?.disconnect()
                        lastError = error
                        if (attempt < MAX_OPEN_ATTEMPTS - 1) delay(500L * (attempt + 1))
                    }
                }
                throw lastError ?: IOException("Could not download the response")
            } finally {
                cancellation.dispose()
                activeConnection.getAndSet(null)?.disconnect()
            }
        }

    private fun openOnce(
        url: String,
        noCache: Boolean,
        allowInitialHttp: Boolean,
        onConnection: (HttpURLConnection) -> Unit = {},
    ): InputStream {
        var current = URI(url)
        require(
            current.scheme.equals("https", ignoreCase = true) ||
                (allowInitialHttp && current.scheme.equals("http", ignoreCase = true)),
        ) { if (allowInitialHttp) "Only HTTP and HTTPS URLs are supported" else "Only HTTPS URLs are supported" }

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(
                current.scheme.equals("https", ignoreCase = true) ||
                    current.scheme.equals("http", ignoreCase = true),
            ) { "Redirect uses an unsupported protocol" }
            val connection = current.toURL().openConnection() as HttpURLConnection
            onConnection(connection)
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "EnCodec-Android-Player/0.8.2")
            if (noCache) {
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                connection.setRequestProperty("Pragma", "no-cache")
            }

            val status = connection.responseCode
            if (status in 200..299) {
                return KeepAliveInputStream(connection.inputStream)
            }
            if (status in 300..399 && redirectCount < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("HTTP redirect has no destination")
                connection.disconnect()
                current = current.resolve(location)
                return@repeat
            }
            val message = connection.responseMessage
            connection.disconnect()
            throw IOException("Server returned HTTP $status${message?.let { ": $it" } ?: ""}")
        }
        throw IOException("Too many HTTP redirects")
    }

    private class KeepAliveInputStream(source: InputStream) : FilterInputStream(source)
}
