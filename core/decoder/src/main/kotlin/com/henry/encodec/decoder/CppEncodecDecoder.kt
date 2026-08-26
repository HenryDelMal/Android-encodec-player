package com.henry.encodec.decoder

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import com.henry.encodec.ecdc.EcdcFrame
import com.henry.encodec.ecdc.EncodecVariant
import java.io.File

/** Lightweight Eigen/NEON EnCodec decoder backed by one native inference worker. */
class CppEncodecDecoder(
    modelFile: File,
    override val variant: EncodecVariant,
    private val rescale: Boolean = true,
    context: Context? = null,
    private val diagnosticsEnabled: () -> Boolean = { false },
) : EncodecDecoder {
    private val powerHint = context?.let(::DecoderPowerHint)
    private var nativeHandle = try {
        nativeCreate(modelFile.absolutePath, variant.sampleRate, variant.channels)
    } catch (error: Throwable) {
        throw DecoderUnavailableException("Could not load ${modelFile.name}", error)
    }

    @Synchronized
    override fun decode(frame: EcdcFrame): DecodedPcm {
        check(nativeHandle != 0L) { "EnCodec decoder is closed" }
        // Leave enough headroom to deliver PCM before its playback deadline.
        // Long live segments may contain several independently decoded frames,
        // so derive the hint from this frame rather than the segment duration.
        val audioDurationNanos = frame.outputLengthSamples.toLong() * 1_000_000_000L /
            variant.sampleRate
        val targetWorkNanos = (audioDurationNanos * 7L / 10L)
            .coerceIn(MIN_TARGET_WORK_NANOS, MAX_TARGET_WORK_NANOS)
        val hintStarted = powerHint?.beginWork(targetWorkNanos)
        val samples = try {
            nativeDecode(
                nativeHandle = nativeHandle,
                codes = frame.codes,
                codebooks = frame.codebookCount,
                timeSteps = frame.timeSteps,
                trimLeadingFrames = frame.trimLeadingSamples,
                outputFrames = frame.outputLengthSamples,
                frameScale = frame.scale ?: 1f,
                rescale = rescale,
                diagnostics = diagnosticsEnabled(),
            )
        } catch (error: Throwable) {
            throw DecoderUnavailableException("C++ EnCodec inference failed", error)
        } finally {
            if (hintStarted != null) powerHint?.endWork(hintStarted)
        }
        return DecodedPcm(samples, variant.sampleRate, variant.channels)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        powerHint?.close()
    }

    private external fun nativeCreate(modelPath: String, sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(
        nativeHandle: Long,
        codes: IntArray,
        codebooks: Int,
        timeSteps: Int,
        trimLeadingFrames: Int,
        outputFrames: Int,
        frameScale: Float,
        rescale: Boolean,
        diagnostics: Boolean,
    ): FloatArray
    private external fun nativeDestroy(nativeHandle: Long)

    private companion object {
        const val MIN_TARGET_WORK_NANOS = 100_000_000L
        const val MAX_TARGET_WORK_NANOS = 3_000_000_000L

        init {
            System.loadLibrary("encodec_android")
        }
    }
}

private class DecoderPowerHint(context: Context) : AutoCloseable {
    private val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(PerformanceHintManager::class.java)
    } else {
        null
    }
    private var session: PerformanceHintManager.Session? = null
    private var sessionThreadId = -1
    private var targetWorkNanos = 0L

    fun beginWork(requestedTargetNanos: Long): Long {
        if (manager == null) return SystemClock.uptimeNanos()
        val threadId = Process.myTid()
        runCatching {
            val active = session
            if (active == null) {
                createSession(threadId, requestedTargetNanos)
            } else if (threadId != sessionThreadId) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    active.setThreads(intArrayOf(threadId))
                    sessionThreadId = threadId
                } else {
                    active.close()
                    session = null
                    createSession(threadId, requestedTargetNanos)
                }
            }
            if (session != null && requestedTargetNanos != targetWorkNanos) {
                session?.updateTargetWorkDuration(requestedTargetNanos)
                targetWorkNanos = requestedTargetNanos
            }
        }
        return SystemClock.uptimeNanos()
    }

    fun endWork(startedNanos: Long) {
        val actual = (SystemClock.uptimeNanos() - startedNanos).coerceAtLeast(1L)
        runCatching { session?.reportActualWorkDuration(actual) }
    }

    private fun createSession(threadId: Int, requestedTargetNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        session = manager?.createHintSession(intArrayOf(threadId), requestedTargetNanos)?.also {
            sessionThreadId = threadId
            targetWorkNanos = requestedTargetNanos
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                it.setPreferPowerEfficiency(true)
            }
        }
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
        sessionThreadId = -1
        targetWorkNanos = 0L
    }
}
