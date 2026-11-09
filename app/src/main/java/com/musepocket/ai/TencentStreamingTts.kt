package com.musepocket.ai

import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal object TencentStreamingTts {
    val isConfigured: Boolean
        get() = BuildConfig.TENCENT_ASR_APP_ID.isNotBlank() &&
            BuildConfig.TENCENT_ASR_SECRET_ID.isNotBlank() && BuildConfig.TENCENT_ASR_SECRET_KEY.isNotBlank()

    suspend fun play(text: String, write: suspend (ByteArray) -> Unit) {
        val url = TencentTtsProtocol.signedUrl(
            appId = BuildConfig.TENCENT_ASR_APP_ID,
            secretId = BuildConfig.TENCENT_ASR_SECRET_ID,
            secretKey = BuildConfig.TENCENT_ASR_SECRET_KEY,
            timestamp = System.currentTimeMillis() / 1_000,
            sessionId = UUID.randomUUID().toString(),
            voiceType = BuildConfig.TENCENT_TTS_VOICE_TYPE,
            text = text,
        )
        val frames = Channel<ByteString>(Channel.UNLIMITED)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            private var receivedBytes = 0L
            private var receivedStatus = false

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val complete = TencentTtsProtocol.isFinal(text)
                    receivedStatus = true
                    if (complete) {
                        if (receivedBytes == 0L) throw TencentTtsException("腾讯云播报没有返回音频。")
                        frames.close()
                        webSocket.close(1000, null)
                    }
                } catch (error: TencentTtsException) {
                    fail(webSocket, error)
                } catch (_: RuntimeException) {
                    fail(webSocket, TencentTtsException("腾讯云播报返回了无法读取的数据。"))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                receivedBytes += bytes.size
                if (!receivedStatus || receivedBytes > MAX_AUDIO_BYTES) {
                    fail(webSocket, TencentTtsException("腾讯云播报返回了无效的音频。"))
                    return
                }
                if (bytes.size > 0) frames.trySend(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                frames.close(TencentTtsException("腾讯云播报连接提前关闭。"))
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = when (response?.code) {
                    401, 403 -> "腾讯云播报鉴权失败，请检查密钥和语音合成权限。"
                    429 -> "腾讯云播报请求过多，请稍后重试。"
                    else -> "腾讯云播报连接失败，请检查网络。"
                }
                frames.close(TencentTtsException(message))
            }

            private fun fail(webSocket: WebSocket, error: TencentTtsException) {
                frames.close(error)
                webSocket.cancel()
            }
        })
        var finished = false
        try {
            while (true) {
                val frame = withTimeoutOrNull(8_000) { frames.receiveCatching() }
                    ?: throw TencentTtsException("腾讯云播报等待音频超时。")
                frame.exceptionOrNull()?.let { throw it }
                if (frame.isClosed) break
                write(frame.getOrThrow().toByteArray())
            }
            finished = true
        } finally {
            frames.cancel()
            if (!finished) socket.cancel()
        }
    }

    private const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}

internal object TencentTtsProtocol {
    const val SAMPLE_RATE = 16_000
    private const val ENDPOINT = "tts.cloud.tencent.com/stream_ws"

    fun signedUrl(
        appId: String,
        secretId: String,
        secretKey: String,
        timestamp: Long,
        sessionId: String,
        voiceType: Int,
        text: String,
    ): String {
        if (appId.toLongOrNull()?.let { it > 0 } != true || secretId.isBlank() || secretKey.isBlank()) {
            throw TencentTtsException("腾讯云播报配置格式有误。")
        }
        val textLength = text.codePointCount(0, text.length)
        val spokenText = if (textLength > 600) text.substring(0, text.offsetByCodePoints(0, 600)) else text
        val parameters = sortedMapOf(
            "Action" to "TextToStreamAudioWS",
            "AppId" to appId,
            "Codec" to "pcm",
            "Expired" to (timestamp + 300).toString(),
            "SampleRate" to SAMPLE_RATE.toString(),
            "SecretId" to secretId,
            "SessionId" to sessionId,
            "Text" to spokenText,
            "Timestamp" to timestamp.toString(),
            "VoiceType" to voiceType.toString(),
        )
        val rawQuery = parameters.entries.joinToString("&") { (name, value) -> "$name=$value" }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = mac.doFinal("GET$ENDPOINT?$rawQuery".toByteArray(Charsets.UTF_8)).toByteString().base64()
        val query = parameters.entries.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "wss://$ENDPOINT?$query&Signature=${encode(signature)}"
    }

    fun isFinal(message: String): Boolean {
        val frame = JsonParser.parseString(message).asJsonObject
        when (val code = frame.get("code").asInt) {
            0 -> return frame.get("final")?.asInt == 1
            10001 -> throw TencentTtsException("腾讯云播报参数无效，请检查音色设置（10001）。")
            10002 -> throw TencentTtsException("腾讯云播报达到并发上限（10002）。")
            10003 -> throw TencentTtsException("腾讯云播报鉴权失败，请检查密钥和语音合成权限（10003）。")
            else -> throw TencentTtsException("腾讯云播报失败（服务码 $code）。")
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

internal class TencentTtsException(message: String) : IOException(message)
