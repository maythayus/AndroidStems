package com.junkfood.seal.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.junkfood.seal.App
import com.junkfood.seal.R
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gère une séparation à la fois, en arrière-plan, indépendamment de l'écran affiché. */
object StemSeparation {

    /** Au-delà, le morceau ne tient plus confortablement en mémoire. */
    private const val MAX_SECONDS = 20 * 60

    data class Input(val uri: Uri, val name: String)

    enum class Step {
        DownloadModel,
        Decode,
        Separate,
    }

    data class Output(val labelRes: Int, val fileName: String, val location: String)

    sealed interface State {
        data object Idle : State

        data class Running(val step: Step, val progress: Float) : State

        data class Done(val outputs: List<Output>, val seconds: Long) : State

        data class Failed(val message: String) : State
    }

    private val _input = MutableStateFlow<Input?>(null)
    val input: StateFlow<Input?> = _input.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean
        get() = _state.value is State.Running

    fun select(context: Context, uri: Uri, name: String? = null) {
        if (isRunning) return
        _input.value = Input(uri, name ?: displayName(context, uri) ?: uri.lastPathSegment ?: "audio")
        _state.value = State.Idle
    }

    fun selectFile(context: Context, path: String, name: String) =
        select(context, Uri.fromFile(File(path)), name)

    fun cancel() {
        job?.cancel()
    }

    fun start(context: Context) {
        val input = _input.value ?: return
        if (isRunning) return
        val appContext = context.applicationContext
        _state.value = State.Running(Step.Decode, 0f)
        job =
            App.applicationScope.launch(Dispatchers.Default) {
                withContext(Dispatchers.Main) { App.startService() }
                val begin = System.currentTimeMillis()
                try {
                    val outputs = run(appContext, input)
                    _state.value =
                        State.Done(outputs, (System.currentTimeMillis() - begin) / 1000)
                } catch (e: CancellationException) {
                    _state.value = State.Idle
                } catch (e: AudioTooLongException) {
                    _state.value =
                        State.Failed(appContext.getString(R.string.stems_error_too_long, e.maxMinutes))
                } catch (e: NoAudioTrackException) {
                    _state.value = State.Failed(appContext.getString(R.string.stems_error_no_audio))
                } catch (e: Throwable) {
                    _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                } finally {
                    withContext(Dispatchers.Main) { App.stopService() }
                }
            }
    }

    private suspend fun run(context: Context, input: Input): List<Output> {
        val scope = kotlin.coroutines.coroutineContext
        val checkCancelled = { scope.ensureActive() }

        if (!StemModel.isInstalled(context)) {
            _state.value = State.Running(Step.DownloadModel, 0f)
            withContext(Dispatchers.IO) {
                StemModel.download(
                    context,
                    onProgress = { _state.value = State.Running(Step.DownloadModel, it) },
                    checkCancelled = checkCancelled,
                )
            }
        }

        _state.value = State.Running(Step.Decode, 0f)
        val pcm =
            withContext(Dispatchers.IO) {
                AudioDecoder.decode(
                    context,
                    input.uri,
                    MdxSeparator.SAMPLE_RATE,
                    MAX_SECONDS,
                    onProgress = { _state.value = State.Running(Step.Decode, it) },
                    checkCancelled = checkCancelled,
                )
            }
        if (pcm.length == 0) throw NoAudioTrackException()

        _state.value = State.Running(Step.Separate, 0f)
        val title = StemWavFile.safeName(input.name.substringBeforeLast('.'))
        val instrumentalName = "$title - ${context.getString(R.string.stems_instrumental)}.wav"
        val vocalsName = "$title - ${context.getString(R.string.stems_vocals)}.wav"
        val instrumental =
            StemWavFile.create(context, title, instrumentalName, pcm.length, pcm.sampleRate)
        val vocals =
            try {
                StemWavFile.create(context, title, vocalsName, pcm.length, pcm.sampleRate)
            } catch (e: Throwable) {
                instrumental.discard()
                throw e
            }

        val env = OrtEnvironment.getEnvironment()
        val options =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
        try {
            env.createSession(StemModel.file(context).absolutePath, options).use { session ->
                val inputName = session.inputNames.first()
                val separator = MdxSeparator { spec ->
                    checkCancelled()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), MdxSeparator.INPUT_SHAPE)
                        .use { tensor ->
                            session.run(mapOf(inputName to tensor)).use { result ->
                                val out = (result[0] as OnnxTensor).floatBuffer
                                FloatArray(out.remaining()).also { out.get(it) }
                            }
                        }
                }
                val scale = 1f / 32768f
                separator.separate(
                    length = pcm.length,
                    sample = { ch, i -> (if (ch == 0) pcm.left[i] else pcm.right[i]) * scale },
                    onChunkDone = { done, total ->
                        _state.value = State.Running(Step.Separate, done.toFloat() / total)
                    },
                ) { primary, offset, start, count ->
                    val l = primary[0]
                    val r = primary[1]
                    for (k in 0 until count) {
                        val il = l[offset + k]
                        val ir = r[offset + k]
                        instrumental.write(il, ir)
                        // voix = morceau d'origine - instrumental
                        vocals.write(pcm.left[start + k] * scale - il, pcm.right[start + k] * scale - ir)
                    }
                }
            }
            instrumental.close()
            vocals.close()
        } catch (e: Throwable) {
            instrumental.discard()
            vocals.discard()
            throw e
        } finally {
            options.close()
        }
        return listOf(
            Output(R.string.stems_vocals, vocalsName, vocals.location),
            Output(R.string.stems_instrumental, instrumentalName, instrumental.location),
        )
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }
            .getOrNull()
}
