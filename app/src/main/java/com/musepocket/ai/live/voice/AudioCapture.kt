package com.musepocket.ai.live.voice

import android.Manifest.permission.RECORD_AUDIO
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission

internal class AudioCapture {
    @Volatile private var active: Capture? = null

    @RequiresPermission(RECORD_AUDIO)
    fun start(onChunk: (ByteArray) -> Unit, onError: () -> Unit): Boolean {
        stop()
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val sampleRate = DoubaoConfig.SAMPLE_RATE_IN
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuffer <= 0) return false
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, channel, encoding, maxOf(minBuffer, CHUNK_BYTES * 4),
            )
        }.getOrNull() ?: return false
        val capture = Capture(record)
        val ready = runCatching {
            if (record.state != AudioRecord.STATE_INITIALIZED) return@runCatching false
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
        if (!ready) {
            capture.release()
            return false
        }
        active = capture
        capture.worker = Thread({
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (active === capture) {
                    val count = runCatching { record.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                    if (active !== capture) break
                    if (count < 0) {
                        onError()
                        break
                    }
                    if (count > 0) onChunk(buffer.copyOf(count))
                }
            } finally {
                capture.release()
            }
        }, "muse-live-input").apply { start() }
        return true
    }

    fun stop() {
        val capture = active ?: return
        active = null
        capture.release()
        capture.worker?.interrupt()
    }

    private class Capture(val record: AudioRecord) {
        private var released = false
        var worker: Thread? = null

        @Synchronized
        fun release() {
            if (released) return
            released = true
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 3_200
    }
}
