package com.junkfood.seal.stems

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Audio stéréo 16 bits en mémoire (deux canaux séparés). */
class StereoPcm(val left: ShortArray, val right: ShortArray, val length: Int, val sampleRate: Int)

class AudioTooLongException(val maxMinutes: Int) : Exception()

class NoAudioTrackException : Exception()

/**
 * Décode n'importe quel fichier lisible par Android (MP3, M4A, Opus, WebM, MP4, WAV…) en PCM stéréo
 * 16 bits, rééchantillonné à [targetRate].
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decode(
        context: Context,
        uri: Uri,
        targetRate: Int,
        maxSeconds: Int,
        onProgress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): StereoPcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") ==
                        true
                } ?: throw NoAudioTrackException()
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val durationUs =
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                    inputFormat.getLong(MediaFormat.KEY_DURATION)
                else 0L
            if (durationUs > maxSeconds * 1_000_000L) throw AudioTooLongException(maxSeconds / 60)

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val left = ShortBuffer()
            val right = ShortBuffer()
            val maxFrames = maxSeconds.toLong() * 48_000L * 2 // garde-fou si la durée est inconnue

            val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            try {
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    checkCancelled()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) {
                                    onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm =
                                f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                        AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            if (floatPcm) {
                                val fb = buffer.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    val l = fb.get()
                                    val r = if (channels > 1) fb.get() else l
                                    repeat(channels - 2) { fb.get() }
                                    left.add(floatToShort(l))
                                    right.add(floatToShort(r))
                                }
                            } else {
                                val sb = buffer.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    val l = sb.get()
                                    val r = if (channels > 1) sb.get() else l
                                    repeat(channels - 2) { sb.get() }
                                    left.add(l)
                                    right.add(r)
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (left.size > maxFrames) throw AudioTooLongException(maxSeconds / 60)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            if (left.size.toLong() > maxSeconds.toLong() * sampleRate) {
                throw AudioTooLongException(maxSeconds / 60)
            }
            onProgress(1f)
            return if (sampleRate == targetRate) {
                StereoPcm(left.array, right.array, left.size, sampleRate)
            } else {
                val l = resample(left.array, left.size, sampleRate, targetRate)
                val r = resample(right.array, right.size, sampleRate, targetRate)
                StereoPcm(l, r, l.size, targetRate)
            }
        } finally {
            extractor.release()
        }
    }

    private fun floatToShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    /** Rééchantillonnage linéaire (suffisant pour 48 kHz -> 44,1 kHz). */
    fun resample(input: ShortArray, length: Int, from: Int, to: Int): ShortArray {
        if (length == 0) return ShortArray(0)
        val outLength = (length.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return ShortArray(outLength) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(length - 1)
            val i1 = (i0 + 1).coerceAtMost(length - 1)
            val frac = pos - i0
            (input[i0] * (1 - frac) + input[i1] * frac).roundToInt().toShort()
        }
    }

    /** Tableau de Short qui grandit au besoin (évite les listes d'objets Short). */
    private class ShortBuffer {
        var array = ShortArray(1 shl 20)
        var size = 0

        fun add(v: Short) {
            if (size == array.size) array = array.copyOf(array.size * 2)
            array[size++] = v
        }
    }
}
