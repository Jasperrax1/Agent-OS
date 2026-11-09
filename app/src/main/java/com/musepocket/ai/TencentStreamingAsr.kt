package com.musepocket.ai

import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal class TencentStreamingAsr(onPartial: (String) -> Unit) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handshake = CompletableDeferred<Unit>()
    private val result = CompletableDeferred<String>()
    private val frames = Channel<ByteString>(capacity = 48)
    private val inputEnded = AtomicBoolean()
    private val socket: WebSocket

    init {
        val voiceId = UUID.randomUUID().toString()
        val url = TencentAsrProtocol.signedUrl(
            appId = BuildConfig.TENCENT_ASR_APP_ID,
            secretId = BuildConfig.TENCENT_ASR_SECRET_ID,
            secretKey = BuildConfig.TENCENT_ASR_SECRET_KEY,
            timestamp = System.currentTimeMillis() / 1_000,
            nonce = random.nextInt(1_000_000_000) + 1,
            voiceId = voiceId,
        )
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            private val transcript = TencentAsrTranscript()
            private var lastPartial = ""

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (result.isCompleted) return
                try {
                    val update = transcript.accept(text)
                    handshake.complete(Unit)
                    if (update.isFinal) {
                        if (update.text.isBlank()) {
                            fail("腾讯云实时转写没有识别到文字。")
                        } else {
                            result.complete(update.text)
                        }
                        webSocket.close(1000, null)
                    } else if (update.text.isNotBlank() && update.text != lastPartial) {
                        lastPartial = update.text
                        onPartial(update.text)
                    }
                } catch (failure: TencentAsrException) {
                    fail(failure.message ?: "腾讯云实时转写失败。")
                    webSocket.cancel()
                } catch (_: RuntimeException) {
                    fail("腾讯云实时转写返回了无法读取的数据。")
                    webSocket.cancel()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) fail("腾讯云实时语音连接提前关闭。")
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = when (response?.code) {
                    401, 403 -> "腾讯云语音鉴权失败，请检查 AppID、密钥和服务权限。"
                    429 -> "腾讯云语音请求过多，请稍后重试。"
                    else -> "腾讯云实时语音连接失败，请检查网络。"
                }
                fail(message)
            }
        })
        scope.launch {
            // Wait for the service's JSON acknowledgment, beyond the WebSocket upgrade.
            try {
                if (withTimeoutOrNull(3_500) { handshake.await() } == null) {
                    fail("腾讯云实时语音握手超时。")
                    socket.cancel()
                    return@launch
                }
            } catch (_: TencentAsrException) {
                return@launch
            }
            sendQueuedAudio()
        }
    }

    fun send(samples: ShortArray, offset: Int, count: Int, last: Boolean) {
        if (result.isCompleted || inputEnded.get()) return
        var position = offset
        val end = offset + count
        while (position < end) {
            val size = minOf(SAMPLES_PER_FRAME, end - position)
            if (!frames.trySend(TencentAsrProtocol.pcmBytes(samples, position, size).toByteString()).isSuccess) {
                fail("腾讯云实时转写未能接收音频。")
                return
            }
            position += size
        }
        if (last && inputEnded.compareAndSet(false, true)) frames.close()
    }

    private suspend fun sendQueuedAudio() {
        var sendAt = System.nanoTime()
        for (frame in frames) {
            val remaining = sendAt - System.nanoTime()
            if (remaining > 0) delay((remaining + 999_999) / 1_000_000)
            if (result.isCompleted) return
            if (!socket.send(frame)) {
                fail("腾讯云实时转写未能接收音频。")
                return
            }
            val samples = frame.size / Short.SIZE_BYTES
            sendAt = System.nanoTime() + samples * 1_000_000_000L / SAMPLE_RATE
        }
        if (!result.isCompleted && !socket.send(TencentAsrProtocol.END_MESSAGE)) {
            fail("腾讯云实时转写未能结束音频流。")
        }
    }

    suspend fun awaitText(): String =
        withTimeoutOrNull(5_500) { result.await() } ?: throw TencentAsrException("腾讯云实时转写超时。")

    fun close() {
        result.cancel()
        handshake.cancel()
        frames.cancel()
        scope.cancel()
        socket.cancel()
    }

    private fun fail(message: String) {
        val error = TencentAsrException(message)
        handshake.completeExceptionally(error)
        if (result.completeExceptionally(error)) frames.cancel()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_FRAME = SAMPLE_RATE / 5
        val random = SecureRandom()
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

internal object TencentAsrProtocol {
    const val END_MESSAGE = """{"type":"end"}"""

    fun signedUrl(
        appId: String,
        secretId: String,
        secretKey: String,
        timestamp: Long,
        nonce: Int,
        voiceId: String,
    ): String {
        require(appId.isNotEmpty() && appId.all { it in '0'..'9' } && secretId.isNotBlank() && secretKey.isNotBlank()) {
            "腾讯云语音配置格式有误。"
        }
        val endpoint = "asr.cloud.tencent.com/asr/v2/$appId"
        val parameters = sortedMapOf(
            "engine_model_type" to "16k_zh",
            "expired" to (timestamp + 300).toString(),
            "needvad" to "1",
            "nonce" to nonce.toString(),
            "secretid" to secretId,
            "timestamp" to timestamp.toString(),
            "voice_format" to "1",
            "voice_id" to voiceId,
        )
        val rawQuery = parameters.entries.joinToString("&") { (name, value) -> "$name=$value" }
        val canonical = "$endpoint?$rawQuery"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toByteString().base64()
        val encodedQuery = parameters.entries.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "wss://$endpoint?$encodedQuery&signature=${encode(signature)}"
    }

    fun pcmBytes(samples: ShortArray, offset: Int, count: Int): ByteArray {
        val output = ByteBuffer.allocate(count * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (index in offset until offset + count) output.putShort(samples[index])
        return output.array()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

internal class TencentAsrTranscript {
    private val confirmed = sortedMapOf<Int, String>()
    private var partialIndex = -1
    private var partialText = ""

    fun accept(message: String): Update {
        val json = JsonParser.parseString(message).asJsonObject
        val code = json.get("code")?.asInt ?: throw IllegalArgumentException("Missing speech status")
        if (code != 0) throw TencentAsrException(errorMessage(code))
        val slice = json.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
        if (slice != null) {
            val index = slice.get("index").asInt
            val type = slice.get("slice_type").asInt
            val text = slice.get("voice_text_str")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (type == 2) {
                confirmed[index] = text
                if (partialIndex == index) {
                    partialIndex = -1
                    partialText = ""
                }
            } else if ((type == 0 || type == 1) && index !in confirmed && index >= partialIndex) {
                partialIndex = index
                partialText = text
            }
        }
        val isFinal = json.get("final")?.takeIf { it.isJsonPrimitive }?.asInt == 1
        val confirmedText = confirmed.values.joinToString("")
        return Update((if (isFinal) confirmedText else confirmedText + partialText).trim(), isFinal)
    }

    data class Update(val text: String, val isFinal: Boolean)

    private fun errorMessage(code: Int): String = when (code) {
        4001 -> "腾讯云语音请求参数无效（4001）。"
        4002 -> "腾讯云语音鉴权失败，请检查 AppID 和密钥（4002）。"
        4003 -> "腾讯云账号尚未开通语音识别服务（4003）。"
        4004, 4005 -> "腾讯云语音资源或余额不足（$code）。"
        4006 -> "腾讯云语音调用并发达到上限（4006）。"
        4007 -> "腾讯云无法解码上传的音频（4007）。"
        4008 -> "腾讯云等待音频超时（4008）。"
        6001 -> "腾讯云语音服务与当前网络地区不匹配（6001）。"
        else -> "腾讯云实时转写失败（服务码 $code）。"
    }
}

internal class TencentAsrException(message: String) : Exception(message)
