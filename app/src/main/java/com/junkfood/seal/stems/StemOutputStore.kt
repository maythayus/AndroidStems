package com.junkfood.seal.stems

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Un fichier WAV 16 bits stéréo écrit dans Musique/Seal Stems/<titre>/. */
class StemWavFile
private constructor(
    private val context: Context,
    private val stream: OutputStream,
    /** URI content:// (Android 10+) ou chemin de fichier (Android 9 et moins). */
    val location: String,
    private val pendingUri: Uri?,
) {
    private val buffer = ByteBuffer.allocate(2 * 2 * 8192).order(ByteOrder.LITTLE_ENDIAN)

    fun write(left: Float, right: Float) {
        buffer.putShort(toShort(left))
        buffer.putShort(toShort(right))
        if (!buffer.hasRemaining()) flushBuffer()
    }

    private fun flushBuffer() {
        stream.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    fun close() {
        flushBuffer()
        stream.close()
        if (pendingUri != null) {
            context.contentResolver.update(
                pendingUri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } else {
            MediaScannerConnection.scanFile(context, arrayOf(location), arrayOf("audio/x-wav"), null)
        }
    }

    /** Supprime un fichier incomplet (annulation ou erreur). */
    fun discard() {
        runCatching { stream.close() }
        val uri = pendingUri
        if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
        else File(location).delete()
    }

    private fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    companion object {
        const val FOLDER = "Seal Stems"

        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifEmpty { "audio" }

        fun create(
            context: Context,
            folder: String,
            fileName: String,
            frames: Int,
            sampleRate: Int,
        ): StemWavFile {
            val wav: StemWavFile
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                        put(
                            MediaStore.Audio.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_MUSIC}/$FOLDER/$folder",
                        )
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                val collection =
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri =
                    context.contentResolver.insert(collection, values)
                        ?: throw IOException("Impossible de créer $fileName")
                val stream =
                    context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Impossible d'écrire $fileName")
                wav = StemWavFile(context, BufferedOutputStream(stream), uri.toString(), uri)
            } else {
                @Suppress("DEPRECATION")
                val dir =
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "$FOLDER/$folder",
                    )
                dir.mkdirs()
                val file = File(dir, fileName)
                wav = StemWavFile(context, BufferedOutputStream(file.outputStream()), file.path, null)
            }
            wav.stream.write(wavHeader(frames, sampleRate))
            return wav
        }

        fun wavHeader(frames: Int, sampleRate: Int, channels: Int = 2): ByteArray {
            val dataSize = frames.toLong() * channels * 2
            return ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt((36 + dataSize).toInt())
                    put("WAVE".toByteArray())
                    put("fmt ".toByteArray())
                    putInt(16)
                    putShort(1) // PCM
                    putShort(channels.toShort())
                    putInt(sampleRate)
                    putInt(sampleRate * channels * 2)
                    putShort((channels * 2).toShort())
                    putShort(16)
                    put("data".toByteArray())
                    putInt(dataSize.toInt())
                }
                .array()
        }
    }
}
