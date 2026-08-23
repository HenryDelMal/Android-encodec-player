package com.henry.encodec.playback

import com.henry.encodec.decoder.DecodedPcm
import com.henry.encodec.decoder.EncodecDecoder
import com.henry.encodec.ecdc.EcdcReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class EcdcPlaybackSession(
    private val decoder: EncodecDecoder,
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
        // AudioTrack is stopped and released only by the playback thread,
        // preventing Next/Previous from racing a native write.
    }

    suspend fun play(
        input: InputStream,
        startSample: Long = 0,
        initialFrameIndex: Int = 0,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        EcdcReader(input, initialFrameIndex).use { reader ->
            require(reader.header.variant == decoder.variant) {
                "File uses ${reader.header.variant.wireName}, decoder is ${decoder.variant.wireName}"
            }
            AudioTrackSink(reader.header.variant.sampleRate, reader.header.variant.channels).use { sink ->
                currentSink = sink
                try {
                    sink.start()
                    if (paused) sink.pause()
                    val requestedStart = startSample.coerceIn(0, reader.header.audioLengthSamples - 1)
                    val stride = reader.header.variant.segmentStrideSamples
                        ?: reader.header.audioLengthSamples.toInt()
                    val targetFrameIndex = (requestedStart / stride).toInt()
                    require(initialFrameIndex <= targetFrameIndex)
                    repeat(targetFrameIndex - initialFrameIndex) {
                        if (stopRequested) return@withContext
                        reader.readFrame() ?: return@withContext
                    }

                    val progressIntervalSamples =
                        (reader.header.variant.sampleRate / 10).coerceAtLeast(1).toLong()
                    var lastReportedSample = requestedStart - progressIntervalSamples
                    fun reportPlayedPosition(force: Boolean = false) {
                        val playedSample = (requestedStart + sink.playedFrames())
                            .coerceAtMost(reader.header.audioLengthSamples)
                        if (force || playedSample - lastReportedSample >= progressIntervalSamples) {
                            lastReportedSample = playedSample
                            onProgress(
                                (playedSample.toDouble() / reader.header.audioLengthSamples)
                                    .toFloat().coerceIn(0f, 1f),
                            )
                        }
                    }
                    fun writeToSink(pcm: DecodedPcm): Boolean = sink.write(
                        pcm = pcm,
                        shouldStop = { stopRequested },
                        onPlaybackAdvanced = { reportPlayedPosition() },
                    )

                    var emittedSamples = requestedStart
                    var pending: DecodedPcm? = null
                    var firstFrame = true
                    val overlapSamples = reader.header.variant.segmentSamples
                        ?.minus(reader.header.variant.segmentStrideSamples ?: 0)
                        ?: 0
                    while (true) {
                        coroutineContext.ensureActive()
                        if (stopRequested) break
                        val frame = reader.readFrame() ?: break
                        var pcm = decoder.decode(frame)
                        if (firstFrame) {
                            val trim = (requestedStart - frame.outputOffsetSamples)
                                .coerceIn(0, (pcm.samples.size / pcm.channels).toLong())
                                .toInt()
                            if (trim > 0) {
                                pcm = pcm.sliceFrames(trim, pcm.samples.size / pcm.channels)
                            }
                            firstFrame = false
                        }
                        if (overlapSamples == 0) {
                            val pcmFrames = pcm.samples.size / pcm.channels
                            if (!writeToSink(pcm)) break
                            emittedSamples += pcmFrames
                            continue
                        }
                        val previous = pending
                        if (previous == null) {
                            pending = pcm
                        } else {
                            val overlap = minOf(
                                overlapSamples,
                                previous.samples.size / previous.channels,
                                pcm.samples.size / pcm.channels,
                            )
                            val keep = previous.samples.size / previous.channels - overlap
                            if (keep > 0) {
                                if (!writeToSink(previous.sliceFrames(0, keep))) break
                                emittedSamples += keep
                            }
                            if (overlap > 0) {
                                if (!writeToSink(crossfade(previous, pcm, overlap))) break
                                emittedSamples += overlap
                            }
                            pending = pcm.sliceFrames(overlap, pcm.samples.size / pcm.channels)
                        }
                    }
                    pending?.let { last ->
                        val remaining = (reader.header.audioLengthSamples - emittedSamples)
                            .coerceAtMost(last.samples.size.toLong() / last.channels)
                            .toInt()
                        if (!stopRequested && remaining > 0) {
                            writeToSink(last.sliceFrames(0, remaining))
                        }
                    }
                    if (!stopRequested) {
                        sink.drain(
                            shouldStop = { stopRequested },
                            onPlaybackAdvanced = { reportPlayedPosition() },
                        )
                        reportPlayedPosition(force = true)
                    }
                } finally {
                    currentSink = null
                }
            }
        }
    }

    private fun DecodedPcm.sliceFrames(from: Int, until: Int): DecodedPcm =
        copy(samples = samples.copyOfRange(from * channels, until * channels))

    private fun crossfade(left: DecodedPcm, right: DecodedPcm, frames: Int): DecodedPcm {
        require(left.channels == right.channels)
        val channels = left.channels
        val leftStart = left.samples.size / channels - frames
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
