package com.musepocket.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.musepocket.ai.live.voice.DoubaoConfig
import com.musepocket.ai.live.voice.DoubaoRealtimeClient
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class VisionVoice(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val initialized = CompletableDeferred<Boolean>()
    private val native = runCatching {
        TextToSpeech(context.applicationContext) { status ->
            initialized.complete(status == TextToSpeech.SUCCESS)
        }
    }.getOrElse {
        initialized.complete(false)
        null
    }
    private var playback: Job? = null
    private var cloudCall: Call? = null
    private var utterance: String? = null

    fun speak(text: String, onStart: () -> Unit, onDone: (String?) -> Unit) {
        stop()
        playback = scope.launch {
            var started = false
            fun markStarted() {
                if (!started) {
                    started = true
                    onStart()
                }
            }
            try {
                val live = if (DoubaoConfig.missingCredentialsHint == null) {
                    try {
                        withContext(Dispatchers.IO) {
                            playPcm(DoubaoConfig.SAMPLE_RATE_OUT, { withContext(Dispatchers.Main) { markStarted() } }) { write ->
                                receiveLiveSpeech(text, write)
                            }
                        }
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (started) throw PlaybackFailure("语音播报中断。")
                        false
                    }
                } else false
                val completed = live || playNative(text, ::markStarted)
                if (completed) {
                    onDone(null)
                } else if (started) {
                    onDone("语音播报中断。")
                } else {
                    playFallback(text, { started }, ::markStarted)
                    onDone(null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PlaybackFailure) {
                onDone(error.message)
            } catch (error: TencentTtsException) {
                onDone(error.message)
            } catch (_: Exception) {
                onDone("语音播报失败。")
            }
        }
    }

    private suspend fun receiveLiveSpeech(text: String, write: suspend (ByteArray) -> Unit) {
        val completed = withTimeoutOrNull(25_000L) {
            callbackFlow<ByteArray> {
                var remaining = DoubaoConfig.SAMPLE_RATE_OUT * 2 * 30
                val client = DoubaoRealtimeClient(appContext, object : DoubaoRealtimeClient.Listener {
                    override fun onStateChanged(state: DoubaoRealtimeClient.State, message: String?, retryable: Boolean) {
                        if (state == DoubaoRealtimeClient.State.ERROR) {
                            channel.close(PlaybackFailure("豆包 Live 播报暂不可用。"))
                        }
                    }

                    override fun onUserSpeakingStarted() = Unit
                    override fun onAsrPartial(text: String) = Unit
                    override fun onAsrFinal(text: String) = Unit

                    override fun onAudioChunk(pcm: ByteArray) {
                        remaining -= pcm.size
                        if (remaining >= 0) trySend(pcm)
                        else channel.close(PlaybackFailure("语音播报超出长度限制。"))
                    }

                    override fun onRoundEnd() {
                        channel.close()
                    }
                })
                // Post both operations in order so cancellation never leaves an owner behind.
                main.post { client.connectForSpeech(text.take(320)) }
                awaitClose { main.post(client::disconnect) }
            }.buffer(Channel.UNLIMITED).collect(write)
            true
        }
        if (completed != true) throw PlaybackFailure("豆包 Live 播报超时。")
    }

    private suspend fun playFallback(text: String, hasStarted: () -> Boolean, onStart: () -> Unit) {
        val hasDoubao = BuildConfig.SPEECH_API_KEY.isNotBlank()
        val notifyStart: suspend () -> Unit = { withContext(Dispatchers.Main) { onStart() } }
        if (TencentStreamingTts.isConfigured) {
            try {
                withContext(Dispatchers.IO) {
                    playPcm(TencentTtsProtocol.SAMPLE_RATE, notifyStart) { write ->
                        TencentStreamingTts.play(text, write)
                    }
                }
                return
            } catch (error: TencentTtsException) {
                if (hasStarted() || !hasDoubao) throw error
            }
        }
        if (!hasDoubao) {
            throw PlaybackFailure("设备没有可用的中文播报。请配置腾讯云或豆包语音合成。")
        }
        val call = cloudRequest(text)
        cloudCall = call
        try {
            withContext(Dispatchers.IO) {
                playPcm(DOUBAO_SAMPLE_RATE, notifyStart) { write -> playCloud(call, write) }
            }
        } finally {
            if (cloudCall === call) cloudCall = null
            call.cancel()
        }
    }

    private suspend fun playNative(text: String, onStart: () -> Unit): Boolean {
        val engine = native ?: return false
        if (withTimeoutOrNull(1_500) { initialized.await() } != true) return false
        val locale = Locale.SIMPLIFIED_CHINESE
        if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE ||
            engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE
        ) return false
        engine.voices.orEmpty().filter { voice ->
            voice.locale.language == "zh" && voice.locale.country in setOf("", "CN") &&
                !voice.isNetworkConnectionRequired && !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.maxByOrNull { it.quality }?.let { engine.voice = it }

        val id = UUID.randomUUID().toString()
        val firstAudio = CompletableDeferred<Boolean>()
        val done = CompletableDeferred<Boolean>()
        utterance = id
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post {
                    if (utteranceId == id && utterance == id) {
                        onStart.invoke()
                        firstAudio.complete(true)
                    }
                }
            }

            override fun onDone(utteranceId: String?) = finish(utteranceId, true)

            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)

            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)

            private fun finish(utteranceId: String?, success: Boolean) {
                main.post {
                    if (utteranceId == id && utterance == id) {
                        firstAudio.complete(success)
                        done.complete(success)
                    }
                }
            }
        })
        return try {
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) return false
            if (withTimeoutOrNull(4_000) { firstAudio.await() } != true) return false
            withTimeoutOrNull(35_000) { done.await() } == true
        } finally {
            if (utterance == id) {
                utterance = null
                if (!done.isCompleted || done.isCancelled) engine.stop()
            }
        }
    }

    private fun cloudRequest(text: String): Call {
        val audio = JSONObject().put("format", "pcm").put("sample_rate", DOUBAO_SAMPLE_RATE)
        val params = JSONObject().put("text", text).put("speaker", BuildConfig.SPEECH_TTS_SPEAKER)
            .put("audio_params", audio)
        val payload = JSONObject().put("user", JSONObject().put("uid", "muse-vision"))
            .put("req_params", params)
        return http.newCall(Request.Builder()
            .url("https://openspeech.bytedance.com/api/v3/tts/unidirectional")
            .header("X-Api-Key", BuildConfig.SPEECH_API_KEY)
            .header("X-Api-Resource-Id", BuildConfig.SPEECH_TTS_RESOURCE_ID)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build())
    }

    private suspend fun playCloud(call: Call, write: suspend (ByteArray) -> Unit) {
        call.execute().use { response ->
            if (!response.isSuccessful) throw PlaybackFailure("豆包语音播报失败（HTTP ${response.code}）。")
            val source = response.body?.source() ?: throw IOException("Empty TTS response")
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val line = source.readUtf8Line()?.trim() ?: throw IOException("Incomplete TTS response")
                if (line.isEmpty()) continue
                val frame = JSONObject(line)
                when (val code = frame.optInt("code", -1)) {
                    0 -> if (!frame.isNull("data")) write(Base64.decode(frame.getString("data"), Base64.DEFAULT))
                    20_000_000 -> return
                    else -> throw PlaybackFailure("豆包语音播报失败（错误码 $code）。")
                }
            }
        }
    }

    private suspend fun playPcm(
        sampleRate: Int,
        onStart: suspend () -> Unit,
        receive: suspend (suspend (ByteArray) -> Unit) -> Unit,
    ) {
        val track = newAudioTrack(sampleRate)
        try {
            var totalBytes = 0
            receive { bytes ->
                if (bytes.isNotEmpty() && totalBytes == 0) track.play()
                var offset = 0
                while (offset < bytes.size) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val count = track.write(bytes, offset, minOf(bytes.size - offset, 4096))
                    if (count <= 0) throw IOException("AudioTrack write failed")
                    offset += count
                    totalBytes += count
                    if (totalBytes == count) onStart()
                }
            }
            if (totalBytes == 0) throw PlaybackFailure("语音服务没有返回可播放的音频。")
            val drainDeadline = SystemClock.elapsedRealtime() + 2_000L
            while (track.playbackHeadPosition < totalBytes / 2 && SystemClock.elapsedRealtime() < drainDeadline) {
                delay(20)
            }
        } finally {
            track.release()
        }
    }

    private fun newAudioTrack(sampleRate: Int): AudioTrack {
        val channel = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channel, encoding)
        if (minimum <= 0) throw IOException("PCM playback is unavailable")
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(encoding)
                .setSampleRate(sampleRate).setChannelMask(channel).build())
            .setBufferSizeInBytes(maxOf(minimum, sampleRate / 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun stop() {
        playback?.cancel()
        playback = null
        cloudCall?.cancel()
        cloudCall = null
        utterance = null
        native?.stop()
    }

    fun close() {
        stop()
        native?.shutdown()
    }

    private class PlaybackFailure(message: String) : IOException(message)

    private companion object {
        const val DOUBAO_SAMPLE_RATE = 24_000
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    }
}
