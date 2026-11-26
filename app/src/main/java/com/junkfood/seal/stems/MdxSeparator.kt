package com.junkfood.seal.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import org.jtransforms.fft.FloatFFT_1D

/**
 * Pipeline MDX-Net (modèles Ultimate Vocal Remover) : le morceau est découpé en blocs qui se
 * chevauchent, chaque bloc passe en STFT, le modèle prédit le spectre de la piste principale, puis
 * une ISTFT ramène le résultat en audio.
 *
 * Paramètres de UVR-MDX-NET-Inst_HQ_3 : n_fft 6144, hop 1024, dim_f 3072, dim_t 256, 44,1 kHz
 * stéréo. Entrée et sortie du modèle : [1, 4, 3072, 256] = (G réel, G imag, D réel, D imag).
 *
 * [model] reçoit et rend un tableau de taille [SPEC_SIZE], disposé en [canal][fréquence][trame].
 */
class MdxSeparator(private val model: (FloatArray) -> FloatArray) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val N_FFT = 6144
        const val HOP = 1024
        const val DIM_F = 3072
        const val DIM_T = 256
        const val CHUNK = HOP * (DIM_T - 1)
        const val TRIM = N_FFT / 2
        const val GEN = CHUNK - 2 * TRIM
        const val SPEC_SIZE = 4 * DIM_F * DIM_T
        private const val FT = DIM_F * DIM_T
        val INPUT_SHAPE = longArrayOf(1, 4, DIM_F.toLong(), DIM_T.toLong())

        /** Nombre de blocs nécessaires pour [n] échantillons par canal. */
        fun chunkCount(n: Int): Int = (n + GEN - n % GEN) / GEN
    }

    private val fft = FloatFFT_1D(N_FFT.toLong())
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    private val frame = FloatArray(N_FFT)
    private val padded = FloatArray(CHUNK + N_FFT)
    private val accumulator = FloatArray(CHUNK + N_FFT)

    /** Somme des fenêtres au carré (overlap-add), identique pour tous les blocs. */
    private val windowSum =
        FloatArray(CHUNK + N_FFT).also { sum ->
            for (t in 0 until DIM_T) for (k in 0 until N_FFT) sum[t * HOP + k] += window[k] * window[k]
        }

    /** STFT d'un bloc stéréo [2][CHUNK], avec remplissage par réflexion (comme torch center=True). */
    fun stft(chunk: Array<FloatArray>): FloatArray {
        val spec = FloatArray(SPEC_SIZE)
        for (ch in 0..1) {
            val x = chunk[ch]
            for (i in padded.indices) {
                var j = i - TRIM
                if (j < 0) j = -j
                if (j >= CHUNK) j = 2 * (CHUNK - 1) - j
                padded[i] = x[j]
            }
            val reBase = 2 * ch * FT
            val imBase = (2 * ch + 1) * FT
            for (t in 0 until DIM_T) {
                val start = t * HOP
                for (k in 0 until N_FFT) frame[k] = padded[start + k] * window[k]
                fft.realForward(frame)
                // Format JTransforms : frame[0] = Re(0), frame[1] = Re(N/2), puis (Re, Im) par paire.
                spec[reBase + t] = frame[0]
                spec[imBase + t] = 0f
                for (f in 1 until DIM_F) {
                    spec[reBase + f * DIM_T + t] = frame[2 * f]
                    spec[imBase + f * DIM_T + t] = frame[2 * f + 1]
                }
            }
        }
        return spec
    }

    /** ISTFT inverse de [stft] : rend un bloc stéréo [2][CHUNK]. */
    fun istft(spec: FloatArray): Array<FloatArray> {
        val out = Array(2) { FloatArray(CHUNK) }
        for (ch in 0..1) {
            accumulator.fill(0f)
            val reBase = 2 * ch * FT
            val imBase = (2 * ch + 1) * FT
            for (t in 0 until DIM_T) {
                frame[0] = spec[reBase + t]
                frame[1] = 0f // la bande de Nyquist n'est pas prédite par le modèle
                for (f in 1 until DIM_F) {
                    frame[2 * f] = spec[reBase + f * DIM_T + t]
                    frame[2 * f + 1] = spec[imBase + f * DIM_T + t]
                }
                fft.realInverse(frame, true)
                val start = t * HOP
                for (k in 0 until N_FFT) accumulator[start + k] += frame[k] * window[k]
            }
            val y = out[ch]
            for (i in 0 until CHUNK) {
                val w = windowSum[i + TRIM]
                y[i] = if (w > 1e-8f) accumulator[i + TRIM] / w else 0f
            }
        }
        return out
    }

    /**
     * Sépare un morceau stéréo de [length] échantillons par canal. [sample] lit l'échantillon (de -1
     * à 1) du canal `ch` à l'index `i`. Pour chaque bloc, [sink] reçoit la piste principale :
     * `primary[ch][offset + k]` correspond à l'échantillon `start + k` du morceau, pour k < count.
     */
    fun separate(
        length: Int,
        sample: (ch: Int, i: Int) -> Float,
        onChunkDone: (done: Int, total: Int) -> Unit = { _, _ -> },
        sink: (primary: Array<FloatArray>, offset: Int, start: Int, count: Int) -> Unit,
    ) {
        val total = chunkCount(length)
        val block = Array(2) { FloatArray(CHUNK) }
        var start = 0
        for (index in 0 until total) {
            for (ch in 0..1) {
                val b = block[ch]
                for (k in 0 until CHUNK) {
                    val j = start + k - TRIM
                    b[k] = if (j in 0 until length) sample(ch, j) else 0f
                }
            }
            val primary = istft(model(stft(block)))
            val count = min(GEN, length - start)
            if (count > 0) sink(primary, TRIM, start, count)
            start += GEN
            onChunkDone(index + 1, total)
        }
    }
}
