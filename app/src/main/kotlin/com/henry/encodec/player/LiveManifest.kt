package com.henry.encodec.player

import com.henry.encodec.ecdc.EcdcReader
import com.henry.encodec.ecdc.EncodecVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

data class LiveCodecInit(
    val model: String,
    val sampleRate: Int,
    val channels: Int,
    val bandwidthKbps: Double,
    val codebooks: Int,
)

data class LiveSegmentInfo(
    val sequence: Long,
    val url: String,
    val duration: Double,
    val sampleCount: Long,
    val ptsSamples: Long,
    val programDateTime: Instant,
    val epoch: String,
    val discontinuity: Boolean,
    val byteLength: Int,
    val sha256: String,
)

data class LiveManifest(
    val mediaSequence: Long,
    val discontinuitySequence: Long,
    val targetDuration: Double,
    val init: LiveCodecInit,
    val segments: List<LiveSegmentInfo>,
)

data class LiveSelection(val segment: LiveSegmentInfo, val discontinuity: Boolean)

data class DownloadedLiveSegment(
    val bytes: ByteArray,
    val sequence: Long,
    val discontinuity: Boolean,
    val codebooks: Int,
    val bandwidthKbps: Double,
)

class LiveProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object LiveManifestParser {
    fun parse(json: String, manifestUrl: String): LiveManifest = try {
        val base = URI(manifestUrl)
        protocol(base.isAbsolute && base.scheme.isHttp()) { "Manifest URL must use HTTP or HTTPS" }
        val root = JSONObject(json)
        protocol(root.getString("format") == "encodec-live-v1") { "Not an EnCodec live v1 manifest" }
        protocol(root.getInt("version") == 1) { "Unsupported live manifest version" }
        protocol(root.getBoolean("independent_segments")) { "Live segments are not independent" }
        Instant.parse(root.getString("updated_at"))
        val mediaSequence = root.getLong("media_sequence")
        val discontinuitySequence = root.getLong("discontinuity_sequence")
        protocol(mediaSequence >= 0 && discontinuitySequence >= 0) { "Invalid manifest sequence" }
        val targetDuration = root.getDouble("target_duration")
        protocol(targetDuration.isFinite() && targetDuration in 0.25..60.0) { "Invalid target duration" }

        val initJson = root.getJSONObject("init")
        protocol(initJson.getString("container") == "ecdc") { "Live container is not ECDC" }
        protocol(initJson.getInt("container_version") == 0) { "Unsupported ECDC container version" }
        protocol(initJson.getString("model") == "encodec_48khz") { "Only HQ EnCodec live streams are supported" }
        protocol(initJson.getInt("sample_rate") == 48_000) { "Live stream is not 48 kHz" }
        protocol(initJson.getInt("channels") == 2) { "Live stream is not stereo" }
        protocol(initJson.getInt("bits_per_codebook") == 10) { "Unsupported code width" }
        protocol(!initJson.getBoolean("language_model")) { "LM-coded live streams are not supported" }
        protocol(initJson.getBoolean("self_initializing_segments")) { "Segments lack initialization" }
        val codebooks = initJson.getInt("codebooks")
        protocol(codebooks in SUPPORTED_CODEBOOKS) { "Unsupported live codebook count" }
        val bandwidth = initJson.getDouble("bandwidth_kbps")
        protocol(abs(bandwidth - codebooks * 1.5) < 0.001) { "Bandwidth and codebooks do not match" }
        val init = LiveCodecInit("encodec_48khz", 48_000, 2, bandwidth, codebooks)

        val array = root.getJSONArray("segments")
        val segments = buildList {
            var previous = Long.MIN_VALUE
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val sequence = item.getLong("sequence")
                protocol(sequence >= 0 && sequence > previous) { "Live segment sequences are not strictly increasing" }
                previous = sequence
                val duration = item.getDouble("duration")
                val sampleCount = item.getLong("sample_count")
                protocol(duration.isFinite() && duration > 0.0 && sampleCount > 0) { "Invalid live segment duration" }
                protocol(abs(duration - sampleCount.toDouble() / 48_000.0) <= 0.02) {
                    "Segment duration and sample count disagree"
                }
                val ptsSamples = item.getLong("pts_samples")
                protocol(ptsSamples >= 0) { "Invalid segment PTS" }
                val epoch = item.getString("epoch")
                UUID.fromString(epoch)
                val programDateTime = Instant.parse(item.getString("program_date_time"))
                val byteLength = item.getInt("byte_length")
                protocol(byteLength in 1..MAX_SEGMENT_BYTES) { "Invalid live segment byte length" }
                val sha256 = item.getString("sha256").lowercase()
                protocol(SHA256_REGEX.matches(sha256)) { "Invalid live segment SHA-256" }
                val resolved = base.resolve(item.getString("uri"))
                protocol(resolved.isAbsolute && resolved.scheme.isHttp()) { "Segment URI must use HTTP or HTTPS" }
                add(
                    LiveSegmentInfo(
                        sequence, resolved.toString(), duration, sampleCount, ptsSamples,
                        programDateTime, epoch, item.getBoolean("discontinuity"),
                        byteLength, sha256,
                    ),
                )
            }
        }
        protocol(segments.firstOrNull()?.sequence?.let { it == mediaSequence } ?: true) {
            "media_sequence does not match the first segment"
        }
        LiveManifest(mediaSequence, discontinuitySequence, targetDuration, init, segments)
    } catch (error: LiveProtocolException) {
        throw error
    } catch (error: Exception) {
        throw LiveProtocolException("Malformed live manifest: ${error.message ?: "invalid JSON"}", error)
    }

    private fun String?.isHttp(): Boolean =
        equals("http", ignoreCase = true) || equals("https", ignoreCase = true)

    private fun protocol(condition: Boolean, message: () -> String) {
        if (!condition) throw LiveProtocolException(message())
    }

    internal const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
    internal const val MAX_MANIFEST_BYTES = 1024 * 1024
    private val SUPPORTED_CODEBOOKS = setOf(2, 4, 8, 16)
    private val SHA256_REGEX = Regex("[0-9a-f]{64}")
}

class LiveSequenceTracker {
    private var nextSequence: Long? = null
    private var previousEpoch: String? = null

    fun select(manifest: LiveManifest): LiveSegmentInfo? {
        if (manifest.segments.isEmpty()) return null
        val expected = nextSequence
        if (expected == null || expected < manifest.segments.first().sequence) {
            return manifest.segments[(manifest.segments.lastIndex - LIVE_EDGE_OFFSET).coerceAtLeast(0)]
        }
        return manifest.segments.firstOrNull { it.sequence >= expected }
    }

    fun accept(segment: LiveSegmentInfo): LiveSelection {
        val expected = nextSequence
        val discontinuity = segment.discontinuity ||
            (expected != null && segment.sequence != expected) ||
            (previousEpoch != null && previousEpoch != segment.epoch)
        nextSequence = segment.sequence + 1
        previousEpoch = segment.epoch
        return LiveSelection(segment, discontinuity)
    }

    fun reset() {
        nextSequence = null
        previousEpoch = null
    }

    internal fun expectedSequence(): Long? = nextSequence

    private companion object {
        const val LIVE_EDGE_OFFSET = 2
    }
}

class LiveStreamSource(
    private val manifestUrl: String,
    private val fetchManifestBytes: suspend () -> ByteArray = {
        HttpsStreams.readBytes(manifestUrl, LiveManifestParser.MAX_MANIFEST_BYTES, noCache = true)
    },
    private val fetchSegmentBytes: suspend (String) -> ByteArray = { url ->
        HttpsStreams.readBytes(url, LiveManifestParser.MAX_SEGMENT_BYTES)
    },
) {
    private val tracker = LiveSequenceTracker()
    private var retryCount = 0

    suspend fun nextSegment(onStatus: (String) -> Unit): DownloadedLiveSegment {
        while (currentCoroutineContext().isActive) {
            try {
                onStatus(if (retryCount == 0) "Checking live edge…" else "Reconnecting…")
                val manifest = LiveManifestParser.parse(
                    fetchManifestBytes().toString(Charsets.UTF_8),
                    manifestUrl,
                )
                val selected = tracker.select(manifest)
                if (selected == null) {
                    onStatus("Waiting for sequence ${tracker.expectedSequence() ?: manifest.mediaSequence}…")
                    delay(pollDelayMillis(manifest))
                    continue
                }
                onStatus("Buffering segment ${selected.sequence}…")
                val bytes = fetchSegmentBytes(selected.url)
                verifySegment(bytes, selected, manifest.init)
                val accepted = tracker.accept(selected)
                retryCount = 0
                return DownloadedLiveSegment(
                    bytes, selected.sequence, accepted.discontinuity,
                    manifest.init.codebooks, manifest.init.bandwidthKbps,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (protocol: LiveProtocolException) {
                throw protocol
            } catch (error: IOException) {
                retryCount++
                onStatus("Network error: ${error.message ?: "retrying"}")
                delay(min(5_000L, 500L * retryCount))
            }
        }
        throw CancellationException("Live stream stopped")
    }

    fun jumpToLive() = tracker.reset()

    private fun pollDelayMillis(manifest: LiveManifest): Long =
        (manifest.targetDuration * 500).toLong().coerceIn(250L, 5_000L)

    companion object {
        fun verifySegment(bytes: ByteArray, segment: LiveSegmentInfo, init: LiveCodecInit) {
            if (bytes.size != segment.byteLength) {
                throw LiveProtocolException(
                    "Segment ${segment.sequence} has ${bytes.size} bytes, expected ${segment.byteLength}",
                )
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (digest != segment.sha256) {
                throw LiveProtocolException("Segment ${segment.sequence} failed SHA-256 validation")
            }
            val header = try {
                ByteArrayInputStream(bytes).use(EcdcReader::inspect)
            } catch (error: Exception) {
                throw LiveProtocolException("Segment ${segment.sequence} is not valid ECDC v0", error)
            }
            if (header.version != 0 || header.variant != EncodecVariant.STEREO_48_KHZ ||
                header.usesLanguageModel || header.numCodebooks != init.codebooks ||
                header.numCodebooks !in setOf(2, 4, 8, 16) ||
                header.audioLengthSamples != segment.sampleCount
            ) {
                throw LiveProtocolException("Segment ${segment.sequence} codec initialization changed")
            }
        }
    }
}
