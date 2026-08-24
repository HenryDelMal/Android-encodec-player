package com.henry.encodec.playback

import com.henry.encodec.decoder.DecodedPcm
import com.henry.encodec.decoder.EncodecDecoder
import com.henry.encodec.ecdc.EcdcReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

data class LiveEcdcSegment(
    val input: InputStream,
    val sequence: Long,
    val discontinuity: Boolean,
)

/**
 * Opens every live segment as an independent ECDC file while keeping one
 * decoder and one AudioTrack for the complete live session.
 */
class LiveEcdcPlaybackSession(
    private val decoder: EncodecDecoder,
    private val sharedSink: AudioTrackSink? = null,
) {
    @Volatile private var currentSink: AudioTrackSink? = null
    @Volatile private var paused = false
    @Volatile private var stopRequested = false

    fun pause() {
        paused = true
        currentSink?.pause()
    }

    fun resume() {
        paused = false
        currentSink?.resume()
    }

    fun stop() {
        stopRequested = true
        currentSink?.abortQueued()
    }

    suspend fun play(
        nextSegment: suspend () -> LiveEcdcSegment,
        onSegmentPlaying: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val ownsSink = sharedSink == null
        val sink = sharedSink ?: AudioTrackSink(
            decoder.variant.sampleRate,
            decoder.variant.channels,
        )
        try {
            currentSink = sink
            try {
                sink.flushQueued()
                sink.start()
                if (paused) sink.pause()
                var first = true
                while (!stopRequested) {
                    coroutineContext.ensureActive()
                    val segment = nextSegment()
                    if (!first && segment.discontinuity) {
                        sink.flushQueued()
                        if (!paused) sink.resume()
                    }
                    segment.input.use { decodeAndWrite(it, sink) }
                    if (!stopRequested) onSegmentPlaying(segment.sequence)
                    first = false
                }
            } finally {
                currentSink = null
            }
        } finally {
            if (ownsSink) sink.close()
        }
    }

    private fun decodeAndWrite(input: InputStream, sink: AudioTrackSink) {
        EcdcReader(input).use { reader ->
            require(reader.header.variant == decoder.variant) {
                "Live segment uses ${reader.header.variant.wireName}, decoder is ${decoder.variant.wireName}"
            }
            require(!reader.header.usesLanguageModel) { "LM-coded live segments are unsupported" }
            val overlapSamples = reader.header.variant.segmentSamples
                ?.minus(reader.header.variant.segmentStrideSamples ?: 0) ?: 0
            var emittedSamples = 0
            // Retain only the overlap tail so frame 1 is audible after one
            // decoder invocation instead of waiting for frame 2.
            var pendingTail: DecodedPcm? = null
            while (!stopRequested) {
                val frame = reader.readFrame() ?: break
                val pcm = decoder.decode(frame)
                if (overlapSamples == 0) {
                    if (!write(sink, pcm)) return
                    emittedSamples += pcm.frameCount
                    continue
                }
                val previousTail = pendingTail
                if (previousTail == null) {
                    val bodyEnd = (pcm.frameCount - overlapSamples).coerceAtLeast(0)
                    if (bodyEnd > 0 && !write(sink, pcm.sliceFrames(0, bodyEnd))) return
                    emittedSamples += bodyEnd
                    pendingTail = pcm.sliceFrames(bodyEnd, pcm.frameCount)
                } else {
                    val overlap = minOf(overlapSamples, previousTail.frameCount, pcm.frameCount)
                    if (overlap > 0 && !write(sink, crossfade(previousTail, pcm, overlap))) return
                    emittedSamples += overlap
                    val bodyEnd = (pcm.frameCount - overlapSamples).coerceAtLeast(overlap)
                    if (bodyEnd > overlap && !write(sink, pcm.sliceFrames(overlap, bodyEnd))) return
                    emittedSamples += bodyEnd - overlap
                    pendingTail = pcm.sliceFrames(bodyEnd, pcm.frameCount)
                }
            }
            pendingTail?.let { last ->
                val remaining = (reader.header.audioLengthSamples - emittedSamples)
                    .coerceAtMost(last.frameCount.toLong()).toInt()
                if (!stopRequested && remaining > 0) write(sink, last.sliceFrames(0, remaining))
            }
        }
    }

    private fun write(sink: AudioTrackSink, pcm: DecodedPcm): Boolean =
        sink.write(pcm, shouldStop = { stopRequested })

    private val DecodedPcm.frameCount: Int get() = samples.size / channels

    private fun DecodedPcm.sliceFrames(from: Int, until: Int): DecodedPcm =
        copy(samples = samples.copyOfRange(from * channels, until * channels))

    private fun crossfade(left: DecodedPcm, right: DecodedPcm, frames: Int): DecodedPcm {
        require(left.channels == right.channels)
        val channels = left.channels
        val leftStart = left.frameCount - frames
        val mixed = FloatArray(frames * channels)
        for (frame in 0 until frames) {
            val rightWeight = (frame + 1f) / (frames + 1f)
            val leftWeight = 1f - rightWeight
            for (channel in 0 until channels) {
                mixed[frame * channels + channel] =
                    left.samples[(leftStart + frame) * channels + channel] * leftWeight +
                    right.samples[frame * channels + channel] * rightWeight
            }
        }
        return DecodedPcm(mixed, left.sampleRate, channels)
    }
}
