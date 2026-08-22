package com.henry.encodec.ecdc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EcdcReaderTest {
    @Test
    fun `reads official version zero header`() {
        val bytes = file("encodec_24khz", 24_000, 2, false, intArrayOf(0, 1, 1023, 42))
        val header = EcdcReader.inspect(ByteArrayInputStream(bytes))

        assertEquals(EncodecVariant.MONO_24_KHZ, header.variant)
        assertEquals(24_000, header.audioLengthSamples)
        assertEquals(2, header.numCodebooks)
        assertEquals(1_500, header.nominalBitrateBps)
    }

    @Test
    fun `unpacks little endian 10 bit codes in time major order`() {
        val expected = IntArray(150) { (it * 17) % 1024 }
        val bytes = file("encodec_24khz", 24_000, 2, false, expected)

        EcdcReader(ByteArrayInputStream(bytes)).use { reader ->
            val frame = requireNotNull(reader.readFrame())
            assertContentEquals(expected, frame.codes)
            assertEquals(75, frame.timeSteps)
            assertEquals(null, reader.readFrame())
        }
    }

    @Test
    fun `rejects lm stream before treating arithmetic codes as raw bits`() {
        val bytes = file("encodec_24khz", 24_000, 2, true, intArrayOf())
        EcdcReader(ByteArrayInputStream(bytes)).use { reader ->
            assertFailsWith<EcdcFormatException> { reader.readFrame() }
        }
    }

    @Test
    fun `chunks long 24 kHz streams with decoder context`() {
        val codebooks = 3
        val expected = IntArray(375 * codebooks) { (it * 29) % 1024 }
        val bytes = file("encodec_24khz", 120_000, codebooks, false, expected)

        EcdcReader(ByteArrayInputStream(bytes)).use { reader ->
            val first = requireNotNull(reader.readFrame())
            assertEquals(300, first.timeSteps)
            assertEquals(96_000, first.outputLengthSamples)
            assertEquals(0, first.trimLeadingSamples)
            assertContentEquals(expected.copyOfRange(0, 300 * codebooks), first.codes)

            val second = requireNotNull(reader.readFrame())
            assertEquals(83, second.timeSteps)
            assertEquals(96_000, second.outputOffsetSamples)
            assertEquals(24_000, second.outputLengthSamples)
            assertEquals(2_560, second.trimLeadingSamples)
            assertContentEquals(expected.copyOfRange(292 * codebooks, expected.size), second.codes)
            assertEquals(null, reader.readFrame())
        }
    }

    private fun file(model: String, length: Int, codebooks: Int, lm: Boolean, codes: IntArray): ByteArray {
        val metadata = "{\"m\":\"$model\",\"al\":$length,\"nc\":$codebooks,\"lm\":$lm}"
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBytes("ECDC")
                out.writeByte(0)
                out.writeInt(metadata.toByteArray().size)
                out.writeBytes(metadata)
                pack10(codes).forEach { out.writeByte(it.toInt()) }
            }
        }.toByteArray()
    }

    private fun pack10(values: IntArray): ByteArray {
        val result = ByteArrayOutputStream()
        var current = 0L
        var bits = 0
        values.forEach { value ->
            current += value.toLong() shl bits
            bits += 10
            while (bits >= 8) {
                result.write((current and 0xff).toInt())
                current = current shr 8
                bits -= 8
            }
        }
        if (bits > 0) result.write(current.toInt())
        return result.toByteArray()
    }
}
