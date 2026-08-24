package com.henry.encodec.decoder

import com.henry.encodec.ecdc.EcdcFrame
import com.henry.encodec.ecdc.EncodecVariant
import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * Runs an exported decoder whose forward signature is:
 *   codes: int64[max_codebooks, 1, time], active: float32[max_codebooks]
 *   -> pcm: float32[1, channels, samples]
 */
class ExecuTorchEncodecDecoder(
    modelFile: File,
    override val variant: EncodecVariant,
    private val maxCodebooks: Int,
    private val modelTimeSteps: Int = variant.frameRate,
    inferenceThreads: Int = 4,
) : EncodecDecoder {
    private val module = try {
        Module.load(
            modelFile.absolutePath,
            Module.LOAD_MODE_MMAP,
            inferenceThreads.coerceIn(1, 4),
        )
    } catch (error: Throwable) {
        throw DecoderUnavailableException("Could not load ${modelFile.name}", error)
    }

    override fun decode(frame: EcdcFrame): DecodedPcm {
        val startedNanos = System.nanoTime()
        require(frame.codebookCount <= maxCodebooks) {
            "${frame.codebookCount} codebooks exceed model capacity $maxCodebooks"
        }
        require(frame.timeSteps <= modelTimeSteps) {
            "${frame.timeSteps} time steps exceed model capacity $modelTimeSteps"
        }

        val paddedCodes = LongArray(maxCodebooks * modelTimeSteps)
        for (time in 0 until frame.timeSteps) {
            for (codebook in 0 until frame.codebookCount) {
                paddedCodes[codebook * modelTimeSteps + time] =
                    frame.codes[time * frame.codebookCount + codebook].toLong()
            }
        }
        val active = FloatArray(maxCodebooks) { if (it < frame.codebookCount) 1f else 0f }

        val output = try {
            module.forward(
                EValue.from(Tensor.fromBlob(paddedCodes, longArrayOf(maxCodebooks.toLong(), 1, modelTimeSteps.toLong()))),
                EValue.from(Tensor.fromBlob(active, longArrayOf(maxCodebooks.toLong()))),
            ).first().toTensor().dataAsFloatArray
        } catch (error: Throwable) {
            throw DecoderUnavailableException("EnCodec inference failed", error)
        }

        val availablePerChannel = output.size / variant.channels
        val outputStart = frame.trimLeadingSamples.coerceAtMost(availablePerChannel)
        val wantedPerChannel = minOf(
            frame.outputLengthSamples,
            availablePerChannel - outputStart,
        )
        val scale = frame.scale ?: 1f
        val interleaved = FloatArray(wantedPerChannel * variant.channels)
        for (sample in 0 until wantedPerChannel) {
            for (channel in 0 until variant.channels) {
                interleaved[sample * variant.channels + channel] =
                    output[channel * availablePerChannel + outputStart + sample] * scale
            }
        }
        return DecodedPcm(interleaved, variant.sampleRate, variant.channels).also {
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            Log.i(
                "EnCodecDecoder",
                "Decoded ${frame.timeSteps} steps (${frame.codebookCount} codebooks) " +
                    "in ${elapsedMillis}ms",
            )
        }
    }

    override fun close() {
        module.destroy()
    }
}
