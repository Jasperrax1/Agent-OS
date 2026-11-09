package com.musepocket.ai.live.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object DoubaoProtocol {
    const val MSG_FULL_CLIENT = 0x1
    const val MSG_AUDIO_CLIENT = 0x2
    const val MSG_FULL_SERVER = 0x9
    const val MSG_AUDIO_SERVER = 0xB
    const val MSG_ERROR = 0xF

    const val START_CONNECTION = 1
    const val FINISH_CONNECTION = 2
    const val CONNECTION_STARTED = 50
    const val CONNECTION_FAILED = 51
    const val START_SESSION = 100
    const val FINISH_SESSION = 102
    const val SESSION_STARTED = 150
    const val SESSION_FINISHED = 152
    const val SESSION_FAILED = 153
    const val TASK_REQUEST = 200
    const val SAY_HELLO = 300
    const val TTS_RESPONSE = 352
    const val TTS_ENDED = 359
    const val ASR_INFO = 450
    const val ASR_RESPONSE = 451
    const val ASR_ENDED = 459
    const val CHAT_RESPONSE = 550
    const val DIALOG_ERROR = 599

    data class Frame(val messageType: Int, val event: Int, val id: String?, val payload: ByteArray)

    fun build(
        event: Int,
        payload: ByteArray = "{}".toByteArray(),
        sessionId: String? = null,
        messageType: Int = MSG_FULL_CLIENT,
        serialization: Int = 1,
        compress: Boolean = false,
    ): ByteArray {
        val body = if (compress) {
            ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(payload) } }.toByteArray()
        } else {
            payload
        }
        val id = sessionId?.toByteArray()
        return ByteBuffer.allocate(12 + (id?.size?.plus(4) ?: 0) + body.size)
            .put(0x11)
            .put(((messageType shl 4) or 4).toByte())
            .put(((serialization shl 4) or (if (compress) 1 else 0)).toByte())
            .put(0)
            .putInt(event)
            .apply { if (id != null) putInt(id.size).put(id) }
            .putInt(body.size)
            .put(body)
            .array()
    }

    fun parse(bytes: ByteArray): Frame {
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.get().toInt() and 0xff
        val typeFlags = buffer.get().toInt() and 0xff
        val format = buffer.get().toInt() and 0xff
        val headerLength = (version and 0xf) * 4
        require(version shr 4 == 1 && headerLength in 4..bytes.size)
        val type = typeFlags shr 4
        val flags = typeFlags and 0xf
        buffer.position(headerLength)
        if (flags and 1 != 0) buffer.int // Optional sequence
        if (type == MSG_ERROR) {
            val code = buffer.int
            return Frame(type, code, null, byteArrayOf())
        }
        require(flags and 4 != 0)
        val event = buffer.int
        val id = if (type == MSG_FULL_SERVER || type == MSG_AUDIO_SERVER) {
            buffer.sizedBytes(maximum = 256).toString(Charsets.UTF_8)
        } else {
            null
        }
        val payload = buffer.sizedBytes(maximum = MAX_PAYLOAD)
        val body = when (format and 0xf) {
            0 -> payload
            1 -> GZIPInputStream(payload.inputStream()).use { gzip ->
                val output = ByteArrayOutputStream()
                val scratch = ByteArray(8_192)
                while (true) {
                    val count = gzip.read(scratch)
                    if (count < 0) break
                    require(output.size() + count <= MAX_PAYLOAD)
                    output.write(scratch, 0, count)
                }
                output.toByteArray()
            }
            else -> error("Unsupported Live compression")
        }
        return Frame(type, event, id, body)
    }

    private fun ByteBuffer.sizedBytes(maximum: Int): ByteArray {
        val length = int
        require(length in 0..minOf(remaining(), maximum))
        return ByteArray(length).also { get(it) }
    }

    private const val MAX_PAYLOAD = 2 * 1024 * 1024
}
