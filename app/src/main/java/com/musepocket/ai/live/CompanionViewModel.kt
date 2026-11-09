package com.musepocket.ai.live

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musepocket.ai.live.character.CharacterState
import com.musepocket.ai.live.character.PokeSide
import com.musepocket.ai.live.voice.AudioPlayback
import com.musepocket.ai.live.voice.DoubaoRealtimeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class CompanionPage { LIVE, OPEN_NOTES, PREPARE_GAME, GAME }

internal enum class VoiceStatus { OFF, CONNECTING, LISTENING, ERROR }

internal data class UiState(
    val state: CharacterState = CharacterState.IDLE,
    val page: CompanionPage = CompanionPage.LIVE,
    val playToken: Int = 0,
    val subtitle: String? = null,
    val replySubtitle: String? = null,
    val speaking: Boolean = false,
    val voice: VoiceStatus = VoiceStatus.OFF,
    val voiceError: String? = null,
)

internal class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableUi = MutableStateFlow(UiState())
    val ui = mutableUi.asStateFlow()

    private val playback = AudioPlayback()
    private var voiceWanted = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var presentation: Job? = null
    private var subtitleCleanup: Job? = null
    private var gameTransition: Job? = null
    private var workFirstFrame: CompletableDeferred<Unit>? = null

    private val pendingAudio = ArrayList<ByteArray>()
    private var roundOpen = false
    private var roundEnded = false
    private var roundBytes = 0
    private var playing = false

    private val client = DoubaoRealtimeClient(
        application,
        object : DoubaoRealtimeClient.Listener {
            override fun onStateChanged(state: DoubaoRealtimeClient.State, message: String?, retryable: Boolean) {
                val status = when (state) {
                    DoubaoRealtimeClient.State.DISCONNECTED -> VoiceStatus.OFF
                    DoubaoRealtimeClient.State.CONNECTING -> VoiceStatus.CONNECTING
                    DoubaoRealtimeClient.State.CONNECTED -> VoiceStatus.LISTENING
                    DoubaoRealtimeClient.State.ERROR -> VoiceStatus.ERROR
                }
                mutableUi.update { it.copy(voice = status, voiceError = message) }
                when (state) {
                    DoubaoRealtimeClient.State.CONNECTED -> {
                        reconnectAttempts = 0
                        reconnectJob?.cancel()
                    }
                    DoubaoRealtimeClient.State.ERROR -> {
                        resetRound()
                        play(CharacterState.IDLE, clearSubtitle = true)
                        if (retryable) scheduleReconnect()
                    }
                    else -> Unit
                }
            }

            override fun onUserSpeakingStarted() {
                resetRound()
                roundOpen = true
                play(CharacterState.LISTENING, clearSubtitle = true)
            }

            override fun onAsrPartial(text: String) {
                if (!roundOpen) onUserSpeakingStarted()
                if (mutableUi.value.state == CharacterState.LISTENING) {
                    mutableUi.update { it.copy(subtitle = text) }
                    scheduleSubtitleCleanup()
                }
            }

            override fun onAsrFinal(text: String) {
                acceptFinalTranscript(text)
            }

            override fun onReplyText(delta: String) {
                if (!roundOpen) return
                mutableUi.update {
                    val current = it.replySubtitle.orEmpty()
                    val next = if (delta.startsWith(current)) delta else current + delta
                    it.copy(replySubtitle = next.take(320))
                }
            }

            override fun onAudioChunk(pcm: ByteArray) {
                if (!roundOpen || pcm.size > MAX_AUDIO_BYTES - roundBytes) return
                roundBytes += pcm.size
                if (playing) playback.enqueue(pcm) else pendingAudio.add(pcm)
            }

            override fun onRoundEnd() {
                if (roundOpen) roundEnded = true
            }
        },
    )

    fun startVoice() {
        if (mutableUi.value.page != CompanionPage.LIVE) return
        if (voiceWanted && mutableUi.value.voice in setOf(VoiceStatus.CONNECTING, VoiceStatus.LISTENING)) return
        voiceWanted = true
        reconnectAttempts = 0
        reconnectJob?.cancel()
        client.connect()
    }

    fun stopVoice() {
        voiceWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        client.disconnect()
        resetRound()
        play(CharacterState.IDLE, clearSubtitle = true)
    }

    fun onActivityPaused() {
        if (mutableUi.value.page == CompanionPage.PREPARE_GAME) closeGame()
        stopVoice()
    }

    fun acceptFinalTranscript(text: String) {
        if (mutableUi.value.page != CompanionPage.LIVE) return
        when (companionIntent(text)) {
            CompanionIntent.OPEN_NOTES -> {
                stopVoice()
                mutableUi.update { it.copy(page = CompanionPage.OPEN_NOTES) }
            }
            CompanionIntent.OPEN_GAME -> prepareGame()
            null -> {
                if (!roundOpen) {
                    roundOpen = true
                    play(CharacterState.LISTENING, clearSubtitle = true)
                }
                presentReply(text)
            }
        }
    }

    fun onNotesOpened() {
        mutableUi.update { it.copy(page = CompanionPage.LIVE) }
    }

    fun onCharacterFirstFrame(state: CharacterState, token: Int) {
        val current = mutableUi.value
        if (current.page == CompanionPage.PREPARE_GAME && state == CharacterState.WORK && current.playToken == token) {
            workFirstFrame?.complete(Unit)
        }
    }

    fun closeGame() {
        gameTransition?.cancel()
        gameTransition = null
        workFirstFrame = null
        mutableUi.update { it.copy(page = CompanionPage.LIVE, state = CharacterState.IDLE) }
    }

    private fun prepareGame() {
        stopVoice()
        val firstFrame = CompletableDeferred<Unit>()
        workFirstFrame = firstFrame
        mutableUi.update {
            it.copy(page = CompanionPage.PREPARE_GAME, state = CharacterState.WORK, playToken = it.playToken + 1)
        }
        gameTransition = viewModelScope.launch {
            // Start the animation clock at presentation, with a bound if the decoder cannot display it.
            if (withTimeoutOrNull(5_000L) { firstFrame.await(); true } == true) delay(THINK_MS)
            workFirstFrame = null
            gameTransition = null
            mutableUi.update { it.copy(page = CompanionPage.GAME) }
        }
    }

    fun onMicPermissionDenied() {
        stopVoice()
        mutableUi.update { it.copy(voice = VoiceStatus.ERROR, voiceError = "请允许麦克风权限后再连接 Live。") }
    }

    fun poke(side: PokeSide) {
        if (mutableUi.value.state != CharacterState.IDLE) return
        play(if (side == PokeSide.LEFT) CharacterState.POKE_LEFT else CharacterState.POKE_RIGHT, clearSubtitle = true)
    }

    fun onVideoFinished(state: CharacterState) {
        if (state.isPoke && mutableUi.value.state == state) {
            play(CharacterState.IDLE, clearSubtitle = true)
        }
    }

    private fun scheduleReconnect() {
        if (!voiceWanted || reconnectAttempts >= MAX_RECONNECTS) return
        reconnectJob?.cancel()
        val wait = 1_000L shl reconnectAttempts.coerceAtMost(3)
        reconnectJob = viewModelScope.launch {
            delay(wait)
            if (voiceWanted && mutableUi.value.voice == VoiceStatus.ERROR) {
                reconnectAttempts++
                client.connect()
            }
        }
    }

    private fun presentReply(text: String) {
        if (presentation?.isActive == true) return
        val isCommand = CREATE_VERBS.any { text.contains(it) } && CREATE_TARGETS.any { text.contains(it, ignoreCase = true) }
        play(CharacterState.THINK, clearSubtitle = true)
        presentation = viewModelScope.launch {
            delay(THINK_MS)
            if (isCommand) {
                play(CharacterState.WORK, clearSubtitle = true)
                delay(THINK_MS)
            }
            if (pendingAudio.isEmpty() && !roundEnded) {
                withTimeoutOrNull(3_000) {
                    while (pendingAudio.isEmpty() && !roundEnded) delay(30)
                }
            }
            play(CharacterState.ANSWER, clearSubtitle = true)
            if (pendingAudio.isNotEmpty() && playback.start()) {
                playing = true
                pendingAudio.forEach(playback::enqueue)
                pendingAudio.clear()
                mutableUi.update { it.copy(speaking = true) }
                val started = SystemClock.elapsedRealtime()
                withTimeoutOrNull(30_000) {
                    while (!roundEnded) delay(30)
                }
                val remaining = (roundBytes * 1_000L / PCM_BYTES_PER_SECOND + 300) -
                    (SystemClock.elapsedRealtime() - started)
                if (remaining > 0) delay(remaining)
            }
            presentation = null
            resetRound()
            play(CharacterState.IDLE, clearSubtitle = true)
        }
    }

    private fun resetRound() {
        presentation?.cancel()
        presentation = null
        subtitleCleanup?.cancel()
        subtitleCleanup = null
        playback.stop()
        playing = false
        roundOpen = false
        roundEnded = false
        roundBytes = 0
        pendingAudio.clear()
        mutableUi.update { it.copy(speaking = false, subtitle = null, replySubtitle = null) }
    }

    private fun play(state: CharacterState, clearSubtitle: Boolean = false) {
        mutableUi.update {
            it.copy(
                state = state,
                playToken = it.playToken + 1,
                subtitle = if (clearSubtitle) null else it.subtitle,
            )
        }
    }

    private fun scheduleSubtitleCleanup() {
        subtitleCleanup?.cancel()
        subtitleCleanup = viewModelScope.launch {
            delay(6_000)
            mutableUi.update { it.copy(subtitle = null) }
        }
    }

    override fun onCleared() {
        gameTransition?.cancel()
        stopVoice()
        super.onCleared()
    }

    private companion object {
        const val THINK_MS = 3_000L
        const val MAX_RECONNECTS = 8
        const val PCM_BYTES_PER_SECOND = 48_000
        const val MAX_AUDIO_BYTES = PCM_BYTES_PER_SECOND * 30
        val CREATE_VERBS = listOf("生成", "做", "写", "开发", "搭", "建", "设计", "画")
        val CREATE_TARGETS = listOf("应用", "app", "小程序", "游戏", "网页", "页面", "网站", "程序", "代码",
            "工具", "系统", "动画", "组件")
    }
}
