package com.henry.encodec.decoder

import com.henry.encodec.ecdc.EcdcFrame
import com.henry.encodec.ecdc.EncodecVariant
import java.io.File

/** Lightweight Eigen/NEON EnCodec decoder backed by one native inference worker. */
class CppEncodecDecoder(
    modelFile: File,
    override val variant: EncodecVariant,
    private val rescale: Boolean = true,
) : EncodecDecoder {
    private var nativeHandle = try {
        nativeCreate(modelFile.absolutePath, variant.sampleRate, variant.channels)
    } catch (error: Throwable) {
        throw DecoderUnavailableException("Could not load ${modelFile.name}", error)
    }

    @Synchronized
    override fun decode(frame: EcdcFrame): DecodedPcm {
        check(nativeHandle != 0L) { "EnCodec decoder is closed" }
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
            )
        } catch (error: Throwable) {
            throw DecoderUnavailableException("C++ EnCodec inference failed", error)
        }
        return DecodedPcm(samples, variant.sampleRate, variant.channels)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
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
    ): FloatArray
    private external fun nativeDestroy(nativeHandle: Long)

    private companion object {
        init {
            System.loadLibrary("encodec_android")
        }
    }
}
