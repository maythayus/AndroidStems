package com.junkfood.seal.stems

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxSeparatorTest {

    /** La STFT doit suivre la convention de numpy/torch (exp(-i...)), sinon le modèle se trompe. */
    @Test
    fun stftMatchesNaiveDft() {
        val separator = MdxSeparator { it }
        val rnd = Random(1)
        val chunk = Array(2) { FloatArray(MdxSeparator.CHUNK) { rnd.nextFloat() - 0.5f } }
        val spec = separator.stft(chunk)

        val n = MdxSeparator.N_FFT
        val t = 10
        val ch = 1
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        // trame t sans réflexion (assez loin du début) : échantillons t*HOP - TRIM + k
        for (f in listOf(1, 37, 1500, 3071)) {
            var re = 0.0
            var im = 0.0
            for (k in 0 until n) {
                val x = chunk[ch][t * MdxSeparator.HOP - MdxSeparator.TRIM + k] * window[k]
                re += x * cos(2 * PI * f * k / n)
                im -= x * sin(2 * PI * f * k / n)
            }
            val ft = MdxSeparator.DIM_F * MdxSeparator.DIM_T
            val gotRe = spec[2 * ch * ft + f * MdxSeparator.DIM_T + t]
            val gotIm = spec[(2 * ch + 1) * ft + f * MdxSeparator.DIM_T + t]
            assertEquals("re f=$f", re, gotRe.toDouble(), 1e-2)
            assertEquals("im f=$f", im, gotIm.toDouble(), 1e-2)
        }
    }

    /** Avec un « modèle » identité, la séparation doit rendre le signal d'origine. */
    @Test
    fun identityModelReconstructsSignal() {
        val separator = MdxSeparator { it }
        val length = MdxSeparator.GEN * 2 + 12345
        val rnd = Random(2)
        val input = Array(2) { FloatArray(length) { (rnd.nextFloat() - 0.5f) * 0.2f } }
        val output = Array(2) { FloatArray(length) }
        var chunks = 0
        separator.separate(
            length = length,
            sample = { c, i -> input[c][i] },
            onChunkDone = { done, total ->
                chunks = done
                assertEquals(MdxSeparator.chunkCount(length), total)
            },
        ) { primary, offset, start, count ->
            for (c in 0..1) for (k in 0 until count) output[c][start + k] = primary[c][offset + k]
        }
        assertEquals(3, chunks)
        var maxErr = 0f
        for (c in 0..1) for (i in 0 until length) maxErr = maxOf(maxErr, abs(output[c][i] - input[c][i]))
        // seule la bande de Nyquist est perdue : erreur très faible
        assertTrue("erreur max $maxErr", maxErr < 0.01f)
    }
}
