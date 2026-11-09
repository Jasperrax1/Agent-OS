package com.musepocket.ai

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.musepocket.ai.live.CompanionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class Screen { HOME, VISION, AGENT_CONTROLLER, VIDEO_PLAYER }

internal fun generationHoldMs(elapsedMs: Long): Long = (2_200L - elapsedMs).coerceAtLeast(0L)

class MainActivity : ComponentActivity() {
    private val router = VisionRouter()
    private lateinit var speech: VisionSpeech
    private lateinit var voice: VisionVoice
    private var screen by mutableStateOf(Screen.HOME)
    private var homeNotice by mutableStateOf<String?>(null)
    private var vision by mutableStateOf(VisionUiState())
    private val speechLevel = mutableFloatStateOf(0f)
    private var previewCovered by mutableStateOf(false)
    private var camera: VisionCamera? = null
    private val conversation = ArrayDeque<VisionTurn>()
    private var photo: CompletableDeferred<ByteArray>? = null
    private var entryAt = 0L
    private var checkingAt = 0L
    private var generationFrame: CompletableDeferred<Long>? = null
    private var listenRestart: Job? = null
    private var attemptAt = 0L
    private var receivedPartial = false
    private var request: Job? = null
    private var attempt = 0
    private var autoListen = false
    private var companionOpening = false
    private var waitingForCamera = false
    private var waitingForMicrophone = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val wanted = waitingForCamera
        waitingForCamera = false
        if (wanted && screen == Screen.HOME) {
            if (granted) openVision() else homeNotice = "请允许相机权限。"
        }
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val wanted = waitingForMicrophone
        waitingForMicrophone = false
        if (wanted && screen == Screen.VISION && !vision.busy) {
            if (granted) startSpeech() else showError("请允许麦克风权限。", rearm = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = VisionSpeech(this)
        voice = VisionVoice(this, lifecycleScope)
        enableEdgeToEdge()
        hideMuseSystemBars()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BackHandler(enabled = screen != Screen.HOME, onBack = ::returnHome)
                LandscapeShell {
                    Box(Modifier.fillMaxSize()) {
                        if (screen == Screen.HOME || screen == Screen.VISION) {
                            val home = screen == Screen.HOME
                            val accessibility = if (home) Modifier else Modifier.clearAndSetSemantics { }
                            Box(accessibility.drawWithContent {
                                if (home || !previewCovered) drawContent()
                            }) {
                                LandscapeHome(
                                    notice = homeNotice.takeIf { home },
                                    onVision = { if (home) toggleVision() },
                                    onLive = { if (home) openCompanion() },
                                )
                            }
                        }
                        when (screen) {
                            Screen.HOME -> Unit
                            Screen.VISION -> VisionScreen(
                                state = vision,
                                speechLevel = speechLevel,
                                onCaptureReady = ::cameraChanged,
                                onPreviewCovered = { covered ->
                                    if (screen == Screen.VISION) previewCovered = covered
                                },
                                onMicrophone = ::requestSpeech,
                                onFinish = speech::finish,
                                onSubmit = ::submitInstruction,
                                onGenerationFrame = { generationFrame?.complete(SystemClock.elapsedRealtime()) },
                            )
                            Screen.AGENT_CONTROLLER, Screen.VIDEO_PLAYER -> LandscapeResult(screen, ::returnHome)
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideMuseSystemBars()
    }

    @SuppressLint("RestrictedApi") // Activity key-dispatch hook through AndroidX.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (screen == Screen.HOME && event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (down) openCompanion()
            return true
        }
        val visionKey = event.keyCode == KeyEvent.KEYCODE_CAMERA || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (visionKey && (screen == Screen.HOME || screen == Screen.VISION)) {
            if (down) toggleVision()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openCompanion() {
        if (screen != Screen.HOME || waitingForCamera || companionOpening) return
        companionOpening = true
        startActivity(Intent(this, CompanionActivity::class.java))
    }

    override fun onPostResume() {
        super.onPostResume()
        companionOpening = false
        window.decorView.post(::startWhenReady)
    }

    override fun onPause() {
        voice.stop()
        if (screen == Screen.VISION && !waitingForMicrophone) {
            cancelAttempt()
            autoListen = true
            if (vision.phase != VisionPhase.ENTERING) {
                vision = VisionUiState(phase = VisionPhase.LISTENING, footer = vision.footer)
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        speech.close()
        voice.close()
        super.onDestroy()
    }

    private fun toggleVision() {
        if (screen == Screen.VISION) {
            returnHome()
            return
        }
        if (screen != Screen.HOME || waitingForCamera || companionOpening) return
        homeNotice = null
        if (checkSelfPermission(CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openVision()
        } else {
            waitingForCamera = true
            cameraPermission.launch(CAMERA)
        }
    }

    private fun openVision() {
        conversation.clear()
        entryAt = SystemClock.elapsedRealtime()
        previewCovered = false
        vision = VisionUiState(phase = VisionPhase.ENTERING)
        autoListen = true
        screen = Screen.VISION
        hideMuseSystemBars()
        // The vision entry can attach a native view without a window focus change.
        window.decorView.postOnAnimation {
            if (screen == Screen.VISION && hasWindowFocus()) hideMuseSystemBars()
        }
    }

    private fun cameraChanged(value: VisionCamera?) {
        if (screen != Screen.VISION) return
        camera = value
        if (value != null && vision.phase == VisionPhase.ENTERING) markTiming("preview_ready", entryAt)
        startWhenReady()
    }

    private fun startWhenReady() {
        if (!autoListen || waitingForMicrophone || camera == null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        autoListen = false
        requestSpeech()
    }

    private fun returnHome() {
        if (screen == Screen.VISION) leaveVision(Screen.HOME) else screen = Screen.HOME
    }

    private fun leaveVision(destination: Screen) {
        autoListen = false
        waitingForMicrophone = false
        cancelAttempt()
        voice.stop()
        camera = null
        conversation.clear()
        screen = destination
    }

    private fun cancelAttempt() {
        attempt++
        speechLevel.floatValue = 0f
        speech.cancel()
        request?.cancel()
        request = null
        photo?.cancel()
        photo = null
        generationFrame?.cancel()
        generationFrame = null
        listenRestart?.cancel()
        listenRestart = null
    }

    private fun requestSpeech() {
        if (screen != Screen.VISION || camera == null || vision.busy || waitingForMicrophone) return
        voice.stop()
        if (checkSelfPermission(RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSpeech()
        } else {
            waitingForMicrophone = true
            microphonePermission.launch(RECORD_AUDIO)
        }
    }

    private fun startSpeech() {
        cancelAttempt()
        val current = attempt
        attemptAt = SystemClock.elapsedRealtime()
        receivedPartial = false
        vision = VisionUiState(
            busy = true, phase = VisionPhase.LISTENING, footer = vision.footer,
        )
        speech.start(
            onStatus = { status ->
                if (isCurrent(current) && status.startsWith("正在聆听")) markTiming("mic_ready", attemptAt)
            },
            onText = { text -> routeAttempt(current, text.trim()) },
            onAudio = { wav -> routeAttempt(current, "", wav) },
            onError = {
                if (isCurrent(current)) {
                    showError(it, rearm = router.configurationError() == null, delayMs = 1_100)
                }
            },
            fallbackUnavailable = router.configurationError(),
            onPartial = { text ->
                if (isCurrent(current) && vision.phase == VisionPhase.LISTENING) {
                    if (!receivedPartial && text.isNotBlank()) {
                        receivedPartial = true
                        markTiming("first_partial", attemptAt)
                    }
                    vision = if (text.isBlank()) vision.copy(partial = "") else {
                        vision.copy(partial = text, footer = null)
                    }
                }
            },
            onLevel = { level ->
                if (isCurrent(current) && vision.phase == VisionPhase.LISTENING) {
                    speechLevel.floatValue = level
                }
            },
            onEndpoint = { beginCheck(current) },
            onEmpty = { if (isCurrent(current)) resumeListening(vision.footer) },
        )
    }

    private fun submitInstruction(input: String) {
        val text = input.trim()
        if (screen != Screen.VISION || waitingForMicrophone || text.isEmpty() ||
            vision.phase !in setOf(VisionPhase.READY, VisionPhase.LISTENING, VisionPhase.ERROR)
        ) return
        voice.stop()
        cancelAttempt()
        vision = VisionUiState(busy = true, instruction = text, phase = VisionPhase.LISTENING)
        routeAttempt(attempt, text)
    }

    private fun beginCheck(current: Int): CompletableDeferred<ByteArray>? {
        if (!isCurrent(current)) return null
        photo?.let { return it }
        val configError = router.configurationError()
        val activeCamera = camera
        if (configError != null || activeCamera == null) {
            showError(configError ?: "相机画面暂不可用。", rearm = configError == null)
            return null
        }
        checkingAt = SystemClock.elapsedRealtime()
        val captureAt = checkingAt
        speechLevel.floatValue = 0f
        vision = vision.copy(
            status = "正在判断…", phase = VisionPhase.CHECKING, footer = null,
        )
        return CompletableDeferred<ByteArray>().also { shot ->
            photo = shot
            activeCamera.capture { captured ->
                if (!isCurrent(current)) return@capture
                captured.fold(
                    onSuccess = {
                        markTiming("jpeg_ready", captureAt)
                        shot.complete(it)
                    },
                    onFailure = { shot.completeExceptionally(VisionRequestException("暂时无法抓取相机画面。")) },
                )
            }
        }
    }

    private fun routeAttempt(current: Int, text: String, wav: ByteArray? = null) {
        if (!isCurrent(current) || request != null) return
        if (text.isBlank() && wav == null) {
            resumeListening()
            return
        }
        val shot = beginCheck(current) ?: return
        val endpointAt = checkingAt
        request = lifecycleScope.launch {
            try {
                val instruction = if (wav == null) text else router.transcribe(wav)
                if (instruction.isBlank()) throw VisionNoSpeechException()
                markTiming("final_transcript", endpointAt)
                vision = vision.copy(instruction = instruction, partial = instruction)
                val jpeg = withTimeoutOrNull(8_000) { shot.await() }
                    ?: throw VisionRequestException("抓取画面超时。")
                val visionRequestAt = SystemClock.elapsedRealtime()
                val result = router.route(jpeg, instruction, conversation.toList())
                markTiming("ark_reply", visionRequestAt)
                if (!isCurrent(current)) return@launch
                if (result.route != VisionRoute.OBJECT_QA) {
                    val visible = CompletableDeferred<Long>()
                    generationFrame = visible
                    vision = vision.copy(phase = VisionPhase.GENERATING, status = "正在生成…", partial = "")
                    val visibleAt = withTimeoutOrNull(1_300L) { visible.await() }
                        ?: SystemClock.elapsedRealtime()
                    delay(generationHoldMs(SystemClock.elapsedRealtime() - visibleAt))
                    markTiming("generation_visible", visibleAt)
                }
                if (isCurrent(current)) {
                    markTiming("result_shown", endpointAt)
                    showResult(current, instruction, result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: VisionNoSpeechException) {
                if (isCurrent(current)) {
                    markTiming("asr_empty", endpointAt)
                    resumeListening()
                }
            } catch (error: VisionRequestException) {
                if (isCurrent(current)) {
                    markTiming("vision_error", endpointAt)
                    showError(error.message ?: "识别暂时不可用。")
                }
            }
        }
    }

    private fun isCurrent(current: Int): Boolean =
        screen == Screen.VISION && attempt == current && vision.busy

    private fun showError(message: String, rearm: Boolean = true, delayMs: Long = 400) {
        if (rearm) {
            resumeListening(footer = message, delayMs = delayMs)
        } else {
            cancelAttempt()
            vision = VisionUiState(phase = VisionPhase.ERROR, footer = message)
        }
    }

    private fun resumeListening(footer: String? = null, delayMs: Long = 320) {
        cancelAttempt()
        vision = VisionUiState(phase = VisionPhase.LISTENING, footer = footer)
        autoListen = true
        val current = attempt
        listenRestart = lifecycleScope.launch {
            delay(delayMs)
            if (attempt == current && screen == Screen.VISION) {
                listenRestart = null
                startWhenReady()
            }
        }
    }

    private fun showResult(current: Int, instruction: String, result: VisionResult) {
        when (result.route) {
            VisionRoute.AGENT_CONTROLLER -> leaveVision(Screen.AGENT_CONTROLLER)
            VisionRoute.VIDEO_PLAYER -> leaveVision(Screen.VIDEO_PLAYER)
            VisionRoute.OBJECT_QA -> {
                if (conversation.size == 4) conversation.removeFirst()
                conversation.addLast(VisionTurn(instruction.take(320), result.answer.take(320)))
                vision = VisionUiState(
                    status = "正在准备播报…", busy = true, phase = VisionPhase.ANSWERING,
                )
                val replyAt = SystemClock.elapsedRealtime()
                voice.speak(
                    result.answer,
                    onStart = {
                        if (isCurrent(current)) {
                            markTiming("tts_started", replyAt)
                            vision = vision.copy(status = "正在播报…")
                        }
                    },
                    onDone = { error ->
                        if (isCurrent(current)) {
                            markTiming(if (error == null) "tts_done" else "tts_unavailable", replyAt)
                            resumeListening(footer = error)
                        }
                    },
                )
            }
        }
    }

    private fun markTiming(phase: String, since: Long) {
        if (BuildConfig.DEBUG) Log.d("MuseTiming", "$phase=${SystemClock.elapsedRealtime() - since}ms")
    }
}

internal fun ComponentActivity.hideMuseSystemBars() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}
