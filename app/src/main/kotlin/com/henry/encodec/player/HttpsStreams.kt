package com.henry.encodec.player

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

internal object HttpsStreams {
    private const val MAX_REDIRECTS = 5

    fun open(url: String): InputStream {
        var current = URI(url)
        require(current.scheme.equals("https", ignoreCase = true)) {
            "Only HTTPS URLs are supported"
        }

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(
                current.scheme.equals("https", ignoreCase = true) ||
                    current.scheme.equals("http", ignoreCase = true),
            ) { "Redirect uses an unsupported protocol" }
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "EnCodec-Android-Player/0.4")

            val status = connection.responseCode
            if (status in 200..299) {
                return DisconnectingInputStream(connection.inputStream, connection)
            }
            if (status in 300..399 && redirectCount < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("HTTPS redirect has no destination")
                connection.disconnect()
                current = current.resolve(location)
                return@repeat
            }

            val message = connection.responseMessage
            connection.disconnect()
            throw IllegalStateException("Server returned HTTP $status${message?.let { ": $it" } ?: ""}")
        }
        throw IllegalStateException("Too many HTTPS redirects")
    }

    private class DisconnectingInputStream(
        source: InputStream,
        private val connection: HttpURLConnection,
    ) : FilterInputStream(source) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}
