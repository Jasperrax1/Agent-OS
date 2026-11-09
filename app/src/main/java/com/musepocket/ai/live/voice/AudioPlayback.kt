package com.musepocket.ai.live.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue

internal class AudioPlayback {
    @Volatile private var active: Playback? = null

    fun start(): Boolean {
        stop()
        val rate = DoubaoConfig.SAMPLE_RATE_OUT
        val channel = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioTrack.getMinBufferSize(rate, channel, encoding)
        if (minimum <= 0) return false
        val usage = if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes.USAGE_ASSISTANT
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate).setChannelMask(channel).setEncoding(encoding).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 24_000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false
        val output = Playback(track)
        val ready = runCatching {
            if (track.state != AudioTrack.STATE_INITIALIZED) return@runCatching false
            track.play()
            track.playState == AudioTrack.PLAYSTATE_PLAYING
        }.getOrDefault(false)
        if (!ready) {
            output.release()
            return false
        }

        active = output
        output.worker = Thread({
            try {
                while (active === output) {
                    val chunk = output.queue.take()
                    var offset = 0
                    while (offset < chunk.size && active === output) {
                        val count = track.write(chunk, offset, chunk.size - offset)
                        if (count <= 0) return@Thread
                        offset += count
                    }
                }
            } catch (_: InterruptedException) {
                // stop() owns cancellation and releases the platform track.
            } catch (_: IllegalStateException) {
                // AudioTrack may close while a blocking write is unwinding.
            } finally {
                output.release()
            }
        }, "muse-live-output").apply { start() }
        return true
    }

    fun enqueue(pcm: ByteArray) {
        if (pcm.isNotEmpty()) active?.queue?.offer(pcm)
    }

    fun stop() {
        val output = active ?: return
        active = null
        output.queue.clear()
        output.worker?.interrupt()
        output.release()
    }

    private class Playback(val track: AudioTrack) {
        val queue = LinkedBlockingQueue<ByteArray>(64)
        private var released = false
        var worker: Thread? = null

        @Synchronized
        fun release() {
            if (released) return
            released = true
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }
}
