package com.henry.encodec.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.henry.encodec.decoder.DecodedPcm
import kotlin.math.max

class AudioTrackSink(sampleRate: Int, private val channels: Int) : AutoCloseable {
    private var framesWritten = 0L
    private val channelMask = if (channels == 1) {
        AudioFormat.CHANNEL_OUT_MONO
    } else {
        AudioFormat.CHANNEL_OUT_STEREO
    }
    private val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        channelMask,
        AudioFormat.ENCODING_PCM_FLOAT,
    )
    private val track = AudioTrack.Builder()
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
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build(),
        )
        // Neural inference arrives in multi-second bursts. Keep enough queued PCM
        // for the decoder to prepare the following chunk without an underrun.
        .setBufferSizeInBytes(
            max(
                minBuffer,
                sampleRate * channels * Float.SIZE_BYTES * DECODE_AHEAD_SECONDS,
            ),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

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
    ): Boolean {
        var offset = 0
        while (offset < pcm.samples.size && !shouldStop()) {
            val written = synchronized(this) {
                track.write(
                    pcm.samples,
                    offset,
                    pcm.samples.size - offset,
                    AudioTrack.WRITE_NON_BLOCKING,
                )
            }
            check(written >= 0) { "AudioTrack write failed: $written" }
            onPlaybackAdvanced(playedFrames())
            if (written == 0) {
                Thread.sleep(5)
                continue
            }
            offset += written
            framesWritten += written / channels
        }
        return offset == pcm.samples.size
    }

    /** Wait for queued PCM to reach the speaker before closing or changing tracks. */
    fun drain(
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit = {},
    ) {
        while (
            !shouldStop() &&
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            track.playbackHeadPosition.toLong() < framesWritten
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

    private companion object {
        const val DECODE_AHEAD_SECONDS = 6
    }
}
