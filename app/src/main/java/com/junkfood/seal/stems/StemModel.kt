package com.junkfood.seal.stems

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Modèle de séparation UVR-MDX-NET-Inst_HQ_3 (Ultimate Vocal Remover, licence MIT). Il n'est pas
 * inclus dans l'APK : il est téléchargé une seule fois (67 Mo) depuis le dépôt officiel de UVR, puis
 * tout se fait sans Internet.
 */
object StemModel {
    const val NAME = "UVR-MDX-NET-Inst_HQ_3"
    private const val URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val SHA256 = "317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc"
    const val SIZE_BYTES = 66_759_214L

    fun file(context: Context) = File(context.filesDir, "models/$NAME.onnx")

    fun isInstalled(context: Context) = file(context).let { it.exists() && it.length() == SIZE_BYTES }

    fun download(context: Context, onProgress: (Float) -> Unit, checkCancelled: () -> Unit) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val request = Request.Builder().url(URL).build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Réponse vide")
            val total = body.contentLength().takeIf { it > 0 } ?: SIZE_BYTES
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != SHA256) {
                part.delete()
                throw IOException("Modèle corrompu (somme de contrôle invalide)")
            }
        }
        if (!part.renameTo(target)) throw IOException("Impossible d'enregistrer le modèle")
    }
}
