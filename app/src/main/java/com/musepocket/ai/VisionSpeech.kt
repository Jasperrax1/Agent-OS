package com.musepocket.ai

import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class VisionSpeech(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val microphoneCapture = Mutex()
    private val hasRealtimeConfig = BuildConfig.TENCENT_ASR_APP_ID.isNotBlank() &&
        BuildConfig.TENCENT_ASR_SECRET_ID.isNotBlank() && BuildConfig.TENCENT_ASR_SECRET_KEY.isNotBlank()
    private var nativeHadLivePartial: Boolean? = null
    private var session: Session? = null

    private class Session(
        val onStatus: (String) -> Unit,
        val onText: (String) -> Unit,
        val onAudio: (ByteArray) -> Unit,
        val onError: (String) -> Unit,
        val fallbackUnavailable: String?,
        val onPartial: (String) -> Unit,
        val onLevel: (Float) -> Unit,
        val onEndpoint: () -> Unit,
        val onEmpty: () -> Unit,
    ) {
        var recognizer: SpeechRecognizer? = null
        var supportPending = false
        var listening = false
        var endpointSent = false
        var hadLivePartial = false
        var level = 0f
        var lastLevelAt = 0L
        val finishRequested = AtomicBoolean()
        var timeout: Job? = null
        var recording: Job? = null
    }

    @MainThread
    fun start(
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
        onAudio: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        fallbackUnavailable: String? = null,
        onPartial: (String) -> Unit = {},
        onLevel: (Float) -> Unit = {},
        onEndpoint: () -> Unit = {},
        onEmpty: () -> Unit = {},
    ) {
        cancel()
        if (ContextCompat.checkSelfPermission(appContext, RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("麦克风权限未授予。")
            return
        }

        val current = Session(
            onStatus, onText, onAudio, onError, fallbackUnavailable, onPartial, onLevel, onEndpoint, onEmpty,
        )
        session = current
        // Android cannot report partial-result support ahead of a real utterance.
        if (hasRealtimeConfig && nativeHadLivePartial == false) {
            recordFallback(current)
            return
        }
        val recognizer = try {
            when {
                SpeechRecognizer.isRecognitionAvailable(appContext) ->
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                else -> null
            }
        } catch (_: RuntimeException) {
            null
        }
        if (recognizer == null) {
            recordFallback(current)
            return
        }

        current.recognizer = recognizer
        recognizer.setRecognitionListener(listenerFor(current))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            checkChineseSupport(current, recognizer, intent)
        } else {
            listen(current, intent)
        }
    }

    @RequiresApi(33)
    private fun checkChineseSupport(current: Session, recognizer: SpeechRecognizer, intent: Intent) {
        current.supportPending = true
        current.onStatus("正在检查中文语音服务…")
        current.timeout = scope.launch {
            delay(1_800)
            if (current.supportPending) listen(current, intent)
        }
        try {
            recognizer.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(appContext),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (!current.supportPending) return
                        val available = support.installedOnDeviceLanguages + support.onlineLanguages
                        val reported = available + support.supportedOnDeviceLanguages +
                            support.pendingOnDeviceLanguages
                        if (reported.isEmpty() || available.any(::isChinese)) {
                            listen(current, intent)
                        } else {
                            recordFallback(current)
                        }
                    }

                    override fun onError(error: Int) {
                        if (!current.supportPending) return
                        if (isUnavailable(error)) recordFallback(current) else listen(current, intent)
                    }
                },
            )
        } catch (_: RuntimeException) {
            listen(current, intent)
        }
    }

    private fun listen(current: Session, intent: Intent) {
        val recognizer = current.recognizer ?: return
        current.supportPending = false
        current.timeout?.cancel()
        current.onStatus("正在启动系统语音…")
        current.timeout = scope.launch {
            delay(30_000)
            if (current.hadLivePartial && current.listening) finish() else empty(current)
        }
        try {
            recognizer.startListening(intent)
        } catch (_: SecurityException) {
            fail(current, "系统语音服务无法访问麦克风，请检查权限。")
        } catch (_: RuntimeException) {
            recordFallback(current)
        }
    }

    private fun listenerFor(current: Session) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = ready()
        override fun onBeginningOfSpeech() = ready()

        private fun ready() {
            if (current.recognizer == null || current.listening || current.endpointSent) return
            current.listening = true
            current.onStatus("正在聆听")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (current.recognizer != null && rmsdB.isFinite()) {
                reportLevel(current, (rmsdB + 2f) / 12f)
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (current.recognizer == null) return
            current.listening = false
            if (current.hadLivePartial) endpoint(current) else current.onLevel(0f)
            awaitNativeResult(current)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (current.recognizer == null) return
            resultText(partialResults).takeIf(String::isNotEmpty)?.let {
                if (!current.endpointSent) current.hadLivePartial = true
                current.onPartial(it)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            if (current.recognizer == null) return
            val text = resultText(results)
            if (text.isEmpty()) {
                empty(current)
                return
            }
            nativeHadLivePartial = current.hadLivePartial
            endpoint(current)
            if (detach(current)) current.onText(text)
        }

        override fun onError(error: Int) {
            if (current.recognizer == null) return
            if (isUnavailable(error) && !current.endpointSent) {
                recordFallback(current)
                return
            }
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                empty(current)
                return
            }
            val message = when (error) {
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "系统语音服务网络连接失败，请检查网络后重试。"
                SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "系统语音服务无法读取麦克风，请检查权限或麦克风占用。"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "系统语音服务正忙，请稍后重试。"
                else -> "系统语音识别失败（错误码 $error），请重试。"
            }
            fail(current, message)
        }
    }

    private fun resultText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun reportLevel(current: Session, level: Float) {
        val now = SystemClock.elapsedRealtime()
        if (session !== current || !current.listening || now - current.lastLevelAt < 65) return
        current.lastLevelAt = now
        val target = level.coerceIn(0f, 1f)
        val weight = if (target > current.level) 0.65f else 0.32f
        current.level += (target - current.level) * weight
        current.onLevel(current.level)
    }

    private fun endpoint(current: Session) {
        if (session !== current || current.endpointSent) return
        current.endpointSent = true
        current.listening = false
        current.onLevel(0f)
        current.onStatus("正在转写语音…")
        current.onEndpoint()
    }

    private fun recordFallback(current: Session) {
        if (session !== current) return
        stopRecognizer(current)
        current.listening = false
        current.onLevel(0f)
        current.onPartial("")
        if (!hasRealtimeConfig && current.fallbackUnavailable != null) {
            fail(current, "系统中文语音不可用。${current.fallbackUnavailable}")
            return
        }

        current.onStatus("正在准备麦克风…")
        current.recording = scope.launch {
            val utterance = try {
                recordUtterance(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CaptureFailure) {
                fail(current, failure.message ?: "录音失败，请重试。")
                return@launch
            } catch (_: RuntimeException) {
                fail(current, "录音无法使用麦克风，请检查权限或麦克风占用后重试。")
                return@launch
            }
            current.recording = null
            if (detach(current)) {
                when (utterance) {
                    is Utterance.Text -> current.onText(utterance.value)
                    is Utterance.Audio -> current.onAudio(utterance.wav)
                    Utterance.Empty -> current.onEmpty()
                }
            }
        }
    }

    private suspend fun recordUtterance(current: Session): Utterance =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(appContext, RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw CaptureFailure("麦克风权限未授予。")
            }
            var streaming: TencentStreamingAsr? = null
            try {
                val audio = microphoneCapture.withLock {
                    captureUtterance(
                        current.finishRequested,
                        startStreaming = {
                            if (hasRealtimeConfig) {
                                streaming = try {
                                    TencentStreamingAsr { text ->
                                        scope.launch {
                                            if (session === current) current.onPartial(text)
                                        }
                                    }
                                } catch (_: IllegalArgumentException) {
                                    if (current.fallbackUnavailable != null) {
                                        throw CaptureFailure("腾讯云语音配置格式有误。")
                                    }
                                    null
                                }
                            }
                            streaming
                        },
                        onReady = {
                            withContext(Dispatchers.Main.immediate) {
                                if (session === current) {
                                    current.listening = true
                                    current.onStatus(if (hasRealtimeConfig) {
                                        "正在聆听（腾讯云实时）"
                                    } else {
                                        "正在聆听（方舟备用录音）"
                                    })
                                }
                            }
                        },
                        onLevel = { level ->
                            withContext(Dispatchers.Main.immediate) { reportLevel(current, level) }
                        },
                    )
                } ?: return@withContext Utterance.Empty
                withContext(Dispatchers.Main.immediate) { endpoint(current) }
                val activeStream = streaming
                if (activeStream != null) {
                    try {
                        return@withContext Utterance.Text(activeStream.awaitText())
                    } catch (failure: TencentAsrException) {
                        if (current.fallbackUnavailable != null) {
                            throw CaptureFailure(failure.message ?: "腾讯云实时转写失败。")
                        }
                        withContext(Dispatchers.Main.immediate) {
                            if (session === current) current.onStatus("实时转写不可用，正在使用方舟转写…")
                        }
                    }
                }
                Utterance.Audio(wavBytes(audio.samples, audio.size))
            } finally {
                streaming?.close()
            }
        }

    @MainThread
    fun finish() {
        val current = session ?: return
        if (!current.listening || !current.finishRequested.compareAndSet(false, true)) return
        val recognizer = current.recognizer ?: return
        if (current.hadLivePartial) endpoint(current)
        awaitNativeResult(current)
        try {
            recognizer.stopListening()
        } catch (_: RuntimeException) {
            fail(current, "无法结束系统语音，请重试。")
        }
    }

    private fun awaitNativeResult(current: Session) {
        if (session !== current) return
        current.timeout?.cancel()
        current.timeout = scope.launch {
            delay(4_000)
            empty(current)
        }
    }

    @MainThread
    fun cancel() {
        session?.let(::detach)
    }

    @MainThread
    fun close() {
        cancel()
        scope.cancel()
    }

    private fun empty(current: Session) {
        if (detach(current)) current.onEmpty()
    }

    private fun fail(current: Session, message: String) {
        if (detach(current)) current.onError(message)
    }

    private fun detach(current: Session): Boolean {
        if (session !== current) return false
        session = null
        stopRecognizer(current)
        current.recording?.cancel()
        current.recording = null
        return true
    }

    private fun stopRecognizer(current: Session) {
        current.supportPending = false
        current.timeout?.cancel()
        current.timeout = null
        val recognizer = current.recognizer
        current.recognizer = null
        recognizer?.destroy()
    }

    private fun isChinese(tag: String): Boolean =
        Locale.forLanguageTag(tag.replace('_', '-')).language in setOf("zh", "cmn")

    private fun isUnavailable(error: Int): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
}

private const val SAMPLE_RATE = 16_000
private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 8
private const val PREROLL_SAMPLES = SAMPLE_RATE * 3 / 10
private const val READ_SAMPLES = SAMPLE_RATE / 50
private const val STREAM_PACKET_SAMPLES = SAMPLE_RATE / 5

private class CaptureFailure(message: String) : Exception(message)

private sealed interface Utterance {
    data class Text(val value: String) : Utterance
    data class Audio(val wav: ByteArray) : Utterance
    data object Empty : Utterance
}

private class CapturedAudio(val samples: ShortArray, val size: Int)

// Counts consecutive PCM samples, so scheduling hiccups do not create false onset or silence.
internal class SpeechActivityDetector {
    private var noiseFloor = 90.0
    private var onsetSamples = 0
    private var quietSamples = 0
    var started = false
        private set
    var ended = false
        private set

    fun observe(rms: Double, count: Int) {
        if (ended) return
        if (started) {
            val voiceThreshold = maxOf(220.0, noiseFloor * 1.7 + 35.0)
            quietSamples = if (rms >= voiceThreshold) 0 else quietSamples + count
            ended = quietSamples >= SAMPLE_RATE * 17 / 20 // 850 ms trailing silence
            return
        }
        val onsetThreshold = maxOf(320.0, noiseFloor * 2.5 + 45.0)
        if (rms >= onsetThreshold) {
            onsetSamples += count
            started = onsetSamples >= SAMPLE_RATE * 7 / 50 // 140 ms sustained onset
        } else {
            onsetSamples = 0
            noiseFloor += (rms.coerceAtMost(900.0) - noiseFloor) * 0.07
        }
    }
}

@RequiresPermission(RECORD_AUDIO)
private suspend fun captureUtterance(
    finishRequested: AtomicBoolean,
    startStreaming: () -> TencentStreamingAsr?,
    onReady: suspend () -> Unit,
    onLevel: suspend (Float) -> Unit,
): CapturedAudio? {
    val channel = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
    if (minBuffer <= 0) throw CaptureFailure("麦克风不支持录音格式。")
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channel, encoding,
        maxOf(minBuffer, READ_SAMPLES * Short.SIZE_BYTES * 4),
    )
    val chunk = ShortArray(READ_SAMPLES)
    val preroll = ShortArray(PREROLL_SAMPLES)
    var prerollSize = 0
    var samples: ShortArray? = null
    var size = 0
    var sentSamples = 0
    var levelSamples = 0
    var streaming: TencentStreamingAsr? = null
    val activity = SpeechActivityDetector()
    try {
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            throw CaptureFailure("无法初始化麦克风。")
        }
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw CaptureFailure("无法开始录音，请检查麦克风占用。")
        }
        onReady()
        while (!finishRequested.get() && !activity.ended) {
            coroutineContext.ensureActive()
            val count = recorder.read(chunk, 0, chunk.size)
            coroutineContext.ensureActive()
            if (count <= 0) throw CaptureFailure("无法读取麦克风。")
            var energy = 0L
            for (index in 0 until count) {
                val sample = chunk[index].toLong()
                energy += sample * sample
            }
            val rms = sqrt(energy.toDouble() / count)
            activity.observe(rms, count)
            levelSamples += count
            if (levelSamples >= SAMPLE_RATE / 12) {
                levelSamples = 0
                onLevel((rms / 2_500.0).toFloat())
            }
            val buffer = samples
            if (buffer == null) {
                // Keep only a short local lead-in while idle. No audio leaves the phone here.
                val overflow = (prerollSize + count - preroll.size).coerceAtLeast(0)
                if (overflow > 0) {
                    preroll.copyInto(preroll, startIndex = overflow, endIndex = prerollSize)
                    prerollSize -= overflow
                }
                chunk.copyInto(preroll, destinationOffset = prerollSize, endIndex = count)
                prerollSize += count
                if (activity.started) {
                    samples = ShortArray(prerollSize + MAX_UTTERANCE_SAMPLES)
                    preroll.copyInto(samples, endIndex = prerollSize)
                    size = prerollSize
                    streaming = startStreaming()
                }
            } else {
                val remaining = minOf(count, buffer.size - size)
                chunk.copyInto(buffer, destinationOffset = size, endIndex = remaining)
                size += remaining
                if (size == buffer.size) break
            }
            val activeStream = streaming
            val activeBuffer = samples
            if (activeStream != null && activeBuffer != null) {
                while (size - sentSamples >= STREAM_PACKET_SAMPLES) {
                    activeStream.send(activeBuffer, sentSamples, STREAM_PACKET_SAMPLES, last = false)
                    sentSamples += STREAM_PACKET_SAMPLES
                }
            }
        }
    } finally {
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } finally {
            recorder.release()
        }
    }
    coroutineContext.ensureActive()
    val buffer = samples ?: return null
    streaming?.send(buffer, sentSamples, size - sentSamples, last = true)
    return CapturedAudio(buffer, size)
}

private fun wavBytes(samples: ShortArray, count: Int): ByteArray {
    val dataSize = count * Short.SIZE_BYTES
    return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + dataSize)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // PCM format chunk size
        putShort(1) // PCM
        putShort(1) // Mono
        putInt(SAMPLE_RATE)
        putInt(SAMPLE_RATE * Short.SIZE_BYTES)
        putShort(Short.SIZE_BYTES.toShort())
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize)
        for (i in 0 until count) putShort(samples[i])
    }.array()
}
