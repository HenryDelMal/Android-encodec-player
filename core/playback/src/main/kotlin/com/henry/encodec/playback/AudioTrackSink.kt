package com.henry.encodec.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.henry.encodec.decoder.DecodedPcm
import kotlin.math.max
import kotlin.math.roundToInt

class AudioTrackSink(
    private val sampleRate: Int,
    private val channels: Int,
) : AutoCloseable {
    private var framesWritten = 0L
    private val channelMask = if (channels == 1) {
        AudioFormat.CHANNEL_OUT_MONO
    } else {
        AudioFormat.CHANNEL_OUT_STEREO
    }
    private val createdTrack = createCompatibleTrack()
    private val track = createdTrack.track
    private val encoding = createdTrack.encoding

    @Synchronized
    fun start() = track.play()

    @Synchronized
    fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
    }

    @Synchronized
    fun resume() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    /**
     * Uses short non-blocking writes so a track change can stop this writer without
     * another thread stopping or releasing AudioTrack underneath it.
     */
    fun write(
        pcm: DecodedPcm,
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit = {},
    ): Boolean = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
        writeFloat(pcm.samples, shouldStop, onPlaybackAdvanced)
    } else {
        writePcm16(pcm.samples, shouldStop, onPlaybackAdvanced)
    }

    private fun writeFloat(
        samples: FloatArray,
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit,
    ): Boolean {
        var offset = 0
        while (offset < samples.size && !shouldStop()) {
            val written = synchronized(this) {
                track.write(
                    samples,
                    offset,
                    samples.size - offset,
                    AudioTrack.WRITE_NON_BLOCKING,
                )
            }
            check(written >= 0) { "AudioTrack float write failed: $written" }
            onPlaybackAdvanced(playedFrames())
            if (written == 0) {
                Thread.sleep(5)
                continue
            }
            offset += written
            framesWritten += written / channels
        }
        return offset == samples.size
    }

    private fun writePcm16(
        samples: FloatArray,
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit,
    ): Boolean {
        val converted = ShortArray(samples.size) { index ->
            val sample = samples[index].coerceIn(-1f, 1f)
            val scale = if (sample < 0f) 32_768f else 32_767f
            (sample * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        var offset = 0
        while (offset < converted.size && !shouldStop()) {
            val written = synchronized(this) {
                track.write(
                    converted,
                    offset,
                    converted.size - offset,
                    AudioTrack.WRITE_NON_BLOCKING,
                )
            }
            check(written >= 0) { "AudioTrack 16-bit write failed: $written" }
            onPlaybackAdvanced(playedFrames())
            if (written == 0) {
                Thread.sleep(5)
                continue
            }
            offset += written
            framesWritten += written / channels
        }
        return offset == converted.size
    }

    /** Wait for queued PCM to reach the speaker before closing or changing tracks. */
    fun drain(
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit = {},
    ) {
        while (
            !shouldStop() &&
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            playedFrames() < framesWritten
        ) {
            onPlaybackAdvanced(playedFrames())
            Thread.sleep(10)
        }
        onPlaybackAdvanced(playedFrames())
    }

    /** Number of PCM frames that have actually reached the playback device. */
    fun playedFrames(): Long = track.playbackHeadPosition.toLong() and 0xffff_ffffL

    @Synchronized
    override fun close() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        track.release()
    }

    private fun createCompatibleTrack(): CreatedTrack {
        val errors = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            try {
                return CreatedTrack(buildLegacyPcm16Track(), AudioFormat.ENCODING_PCM_16BIT)
            } catch (error: RuntimeException) {
                errors += "legacy-pcm16/minimum: " +
                    (error.message ?: error::class.java.simpleName)
            }
        }
        val attempts = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            // Old AudioFlinger implementations can remain resource-starved after
            // a rejected multi-megabyte allocation, so start with safe buffers.
            listOf(
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, 0),
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, EMULATOR_BUFFER_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, 0),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, EMULATOR_BUFFER_SECONDS),
            )
        } else {
            listOf(
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, DECODE_AHEAD_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, EMULATOR_BUFFER_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, 0),
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, EMULATOR_BUFFER_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, 0),
            )
        }.distinct()
        attempts.forEach { attempt ->
            try {
                return CreatedTrack(buildTrack(attempt), attempt.encoding)
            } catch (error: RuntimeException) {
                errors += "${encodingName(attempt.encoding)}/${bufferName(attempt)}: " +
                    (error.message ?: error::class.java.simpleName)
            }
        }
        throw IllegalStateException(
            "Cannot create AudioTrack for $sampleRate Hz, $channels channels. " +
                errors.joinToString("; "),
        )
    }

    @Suppress("DEPRECATION")
    private fun buildLegacyPcm16Track(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) {
            "unsupported minimum buffer ($minBuffer)"
        }
        val candidate = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
            AudioTrack.MODE_STREAM,
        )
        if (candidate.state != AudioTrack.STATE_INITIALIZED) {
            candidate.release()
            error("legacy AudioTrack was not initialized")
        }
        return candidate
    }

    private fun buildTrack(attempt: TrackAttempt): AudioTrack {
        val bytesPerSample = if (attempt.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            Float.SIZE_BYTES
        } else {
            Short.SIZE_BYTES
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            attempt.encoding,
        )
        require(minBuffer > 0) {
            "unsupported minimum buffer ($minBuffer)"
        }
        val requestedBuffer = if (attempt.bufferSeconds > 0) {
            sampleRate * channels * bytesPerSample * attempt.bufferSeconds
        } else {
            minBuffer
        }
        val candidate = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(attempt.encoding)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, requestedBuffer))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (candidate.state != AudioTrack.STATE_INITIALIZED) {
            candidate.release()
            error("AudioTrack was not initialized")
        }
        return candidate
    }

    private fun encodingName(value: Int): String =
        if (value == AudioFormat.ENCODING_PCM_FLOAT) "float" else "pcm16"

    private fun bufferName(attempt: TrackAttempt): String =
        if (attempt.bufferSeconds == 0) "minimum" else "${attempt.bufferSeconds}s"

    private data class TrackAttempt(
        val encoding: Int,
        val bufferSeconds: Int,
    )

    private data class CreatedTrack(
        val track: AudioTrack,
        val encoding: Int,
    )

    private companion object {
        const val DECODE_AHEAD_SECONDS = 6
        const val EMULATOR_BUFFER_SECONDS = 2
    }
}
