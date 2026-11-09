package com.musepocket.ai.live.voice

import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

internal class DoubaoRealtimeClient(context: Context, private val listener: Listener) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    interface Listener {
        fun onStateChanged(state: State, message: String? = null, retryable: Boolean = false)
        fun onUserSpeakingStarted()
        fun onAsrPartial(text: String)
        fun onAsrFinal(text: String)
        fun onReplyText(delta: String) = Unit
        fun onAudioChunk(pcm: ByteArray)
        fun onRoundEnd()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val capture = AudioCapture()
    @Volatile private var active: Connection? = null

    private class Connection(val speechText: String?) {
        var socket: WebSocket? = null
        var sessionId: String? = null
        var ready = false
        var timeout: Runnable? = null
        var finishTimeout: Runnable? = null
        var finishing = false
        var lastAsrText = ""
        var asrFinal = false
    }

    fun connect() = connect(speechText = null)

    // Text mode reads the supplied content without taking the microphone.
    fun connectForSpeech(text: String) = connect(speechText = text)

    private fun connect(speechText: String?) {
        if (active != null) return
        DoubaoConfig.missingCredentialsHint?.let {
            listener.onStateChanged(State.ERROR, it)
            return
        }
        if (speechText == null && ContextCompat.checkSelfPermission(appContext, RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onStateChanged(State.ERROR, "请允许麦克风权限后再连接 Live。")
            return
        }

        val owner = Connection(speechText)
        val request = try {
            Request.Builder()
                .url(DoubaoConfig.ENDPOINT)
                .header("X-Api-App-ID", DoubaoConfig.APP_ID)
                .header("X-Api-Key", DoubaoConfig.API_KEY)
                .header("X-Api-Resource-Id", DoubaoConfig.RESOURCE_ID)
                .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                .build()
        } catch (_: IllegalArgumentException) {
            listener.onStateChanged(State.ERROR, "Live 配置格式有误，请检查本地配置。")
            return
        }
        active = owner
        listener.onStateChanged(State.CONNECTING)
        owner.timeout = Runnable {
            fail(owner, "Live 连接或会话初始化超时。", retryable = true)
        }.also { main.postDelayed(it, 8_000) }
        owner.socket = http.newWebSocket(request, socketListener(owner))
    }

    fun disconnect() {
        val owner = active
        if (owner != null) {
            retire(owner)
            val session = owner.sessionId
            if (owner.ready && session != null &&
                owner.socket?.send(DoubaoProtocol.build(DoubaoProtocol.FINISH_SESSION, sessionId = session).toByteString()) == true
            ) {
                owner.finishing = true
                owner.finishTimeout = Runnable { finishConnection(owner) }.also { main.postDelayed(it, 400) }
            } else {
                finishConnection(owner)
            }
        }
        listener.onStateChanged(State.DISCONNECTED)
    }

    private fun finishConnection(owner: Connection) {
        owner.finishing = false
        owner.finishTimeout?.let(main::removeCallbacks)
        val socket = owner.socket ?: return
        owner.socket = null
        socket.send(DoubaoProtocol.build(DoubaoProtocol.FINISH_CONNECTION).toByteString())
        socket.close(1000, null)
        main.postDelayed({ socket.cancel() }, 350)
    }

    private fun socketListener(owner: Connection) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (active === owner) {
                    send(owner, DoubaoProtocol.build(DoubaoProtocol.START_CONNECTION))
                } else {
                    webSocket.cancel()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = try {
                DoubaoProtocol.parse(bytes.toByteArray())
            } catch (_: Exception) {
                main.post { fail(owner, "Live 返回了无法识别的协议数据。", retryable = false) }
                return
            }
            main.post {
                if (active === owner) {
                    handle(owner, frame)
                } else if (owner.finishing && frame.event == DoubaoProtocol.SESSION_FINISHED) {
                    finishConnection(owner)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            main.post {
                when (status) {
                    401, 403 -> fail(owner, "Live 鉴权失败，请检查 App ID、API Key 和服务权限。", retryable = false)
                    429 -> fail(owner, "Live 当前达到请求上限。", retryable = false)
                    else -> fail(owner, "Live 网络连接失败。", retryable = status == null || status >= 500)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { fail(owner, "Live 会话已断开。", retryable = true) }
        }
    }

    private fun handle(owner: Connection, frame: DoubaoProtocol.Frame) {
        if (frame.messageType == DoubaoProtocol.MSG_ERROR) {
            val retryable = frame.event in 50_000_000..59_999_999
            fail(owner, "Live 服务返回错误（${frame.event}）。", retryable)
            return
        }
        if (frame.event >= DoubaoProtocol.SESSION_STARTED && frame.id != owner.sessionId) return
        when (frame.event) {
            DoubaoProtocol.CONNECTION_STARTED -> startSession(owner)
            DoubaoProtocol.CONNECTION_FAILED, DoubaoProtocol.SESSION_FAILED ->
                fail(owner, "Live 会话未获服务授权或初始化失败。", retryable = false)
            DoubaoProtocol.SESSION_STARTED -> {
                if (owner.speechText == null) startCapture(owner) else startSpeechOutput(owner, owner.speechText)
            }
            DoubaoProtocol.SESSION_FINISHED ->
                fail(owner, "Live 会话已结束。", retryable = true)
            DoubaoProtocol.TTS_RESPONSE -> if (frame.payload.isNotEmpty()) listener.onAudioChunk(frame.payload)
            DoubaoProtocol.TTS_ENDED -> listener.onRoundEnd()
            DoubaoProtocol.ASR_INFO -> {
                owner.lastAsrText = ""
                owner.asrFinal = false
                listener.onUserSpeakingStarted()
            }
            DoubaoProtocol.ASR_RESPONSE -> readAsr(owner, frame.payload)
            DoubaoProtocol.ASR_ENDED -> finalAsr(owner)
            DoubaoProtocol.CHAT_RESPONSE -> if (owner.speechText == null) {
                val delta = runCatching { JSONObject(frame.payload.toString(Charsets.UTF_8)).opt("content") as? String }
                    .getOrNull()
                if (!delta.isNullOrEmpty()) listener.onReplyText(delta)
            }
            DoubaoProtocol.DIALOG_ERROR ->
                fail(owner, "Live 对话服务返回错误。", retryable = false)
        }
    }

    private fun readAsr(owner: Connection, payload: ByteArray) {
        val json = runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val candidate = json.optJSONArray("results")?.optJSONObject(0) ?: return
        val text = (candidate.opt("text") as? String)?.trim().orEmpty()
        if (text.isEmpty() || owner.asrFinal) return
        if (text != owner.lastAsrText) {
            owner.lastAsrText = text
            listener.onAsrPartial(text)
        }
        // Support the donor endpoint marker as well as the current ASREnded event.
        if (json.optJSONObject("extra")?.optBoolean("endpoint") == true) finalAsr(owner)
    }

    private fun finalAsr(owner: Connection) {
        if (!owner.asrFinal && owner.lastAsrText.isNotBlank()) {
            owner.asrFinal = true
            listener.onAsrFinal(owner.lastAsrText)
        }
    }

    private fun startSession(owner: Connection) {
        if (owner.sessionId != null) return
        val session = UUID.randomUUID().toString()
        owner.sessionId = session
        val config = JSONObject()
            .put("dialog", JSONObject()
                .put("bot_name", "小豆")
                .put("system_role", DoubaoConfig.SYSTEM_ROLE)
                .put("speaking_style", DoubaoConfig.SPEAKING_STYLE)
                .put("extra", JSONObject().put("strict_audit", false)
                    .put("input_mod", if (owner.speechText == null) "audio" else "text")))
            // Doubao "pcm" is 32-bit. AudioTrack consumes 16-bit little-endian samples.
            .put("tts", JSONObject().put("speaker", DoubaoConfig.SPEAKER)
                .put("audio_config", JSONObject().put("channel", 1).put("format", "pcm_s16le")
                    .put("sample_rate", DoubaoConfig.SAMPLE_RATE_OUT)))
            .put("asr", JSONObject().put("extra", JSONObject().put("end_smooth_window_ms", 1_500)))
        send(owner, DoubaoProtocol.build(DoubaoProtocol.START_SESSION, config.toString().toByteArray(),
            sessionId = session, compress = true))
    }

    private fun startSpeechOutput(owner: Connection, text: String) {
        val session = owner.sessionId ?: return
        if (owner.ready) return
        owner.timeout?.let(main::removeCallbacks)
        owner.ready = true
        listener.onStateChanged(State.CONNECTED)
        val message = JSONObject().put("content", text).toString().toByteArray()
        send(owner, DoubaoProtocol.build(DoubaoProtocol.SAY_HELLO, message, session))
    }

    private fun startCapture(owner: Connection) {
        val session = owner.sessionId ?: return
        if (owner.ready) return
        if (ContextCompat.checkSelfPermission(appContext, RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(owner, "请允许麦克风权限后再连接 Live。", retryable = false)
            return
        }
        owner.timeout?.let(main::removeCallbacks)
        val socket = owner.socket ?: return
        val started = capture.start(
            onChunk = { pcm ->
                if (active === owner) {
                    val frame = DoubaoProtocol.build(DoubaoProtocol.TASK_REQUEST, pcm, session,
                        DoubaoProtocol.MSG_AUDIO_CLIENT, serialization = 0)
                    if (!socket.send(frame.toByteString())) {
                        main.post { fail(owner, "Live 麦克风数据发送失败。", retryable = true) }
                    }
                }
            },
            onError = { main.post { fail(owner, "Live 无法读取麦克风。", retryable = false) } },
        )
        if (!started) {
            fail(owner, "Live 无法启动麦克风，请检查权限或占用。", retryable = false)
            return
        }
        owner.ready = true
        listener.onStateChanged(State.CONNECTED)
    }

    private fun send(owner: Connection, frame: ByteArray) {
        if (owner.socket?.send(frame.toByteString()) != true) {
            fail(owner, "Live 会话数据发送失败。", retryable = true)
        }
    }

    private fun fail(owner: Connection, message: String, retryable: Boolean) {
        if (active !== owner) return
        retire(owner)
        owner.socket?.cancel()
        owner.socket = null
        listener.onStateChanged(State.ERROR, message, retryable)
    }

    private fun retire(owner: Connection) {
        active = null
        owner.timeout?.let(main::removeCallbacks)
        capture.stop()
    }

    private companion object {
        val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
