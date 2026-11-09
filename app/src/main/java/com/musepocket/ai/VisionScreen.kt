package com.musepocket.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay

internal enum class VisionPhase { READY, ENTERING, LISTENING, CHECKING, GENERATING, ANSWERING, ERROR }

internal data class VisionUiState(
    val status: String = "对准目标，然后点击麦克风说一句话。",
    val instruction: String = "",
    val busy: Boolean = false,
    val phase: VisionPhase = VisionPhase.READY,
    val partial: String = "",
    val footer: String? = null,
)

internal fun interface VisionCamera {
    fun capture(callback: (Result<ByteArray>) -> Unit)
}

@Composable
internal fun VisionScreen(
    state: VisionUiState,
    speechLevel: State<Float>,
    onCaptureReady: (VisionCamera?) -> Unit,
    onPreviewCovered: (Boolean) -> Unit,
    onMicrophone: () -> Unit,
    onFinish: () -> Unit,
    onGenerationFrame: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val publishCamera by rememberUpdatedState(onCaptureReady)
    val publishPreviewCovered by rememberUpdatedState(onPreviewCovered)
    val generationFrame by rememberUpdatedState(onGenerationFrame)
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var showDebugInput by remember { mutableStateOf(false) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            alpha = WAITING_PREVIEW_ALPHA
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val captureExecutor = Dispatchers.Default.asExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
        var provider: ProcessCameraProvider? = null
        var cameraStates: LiveData<CameraState>? = null
        var disposed = false
        var capturing = false

        val camera = VisionCamera { callback ->
            if (disposed || !cameraReady || capturing) {
                callback(Result.failure(IllegalStateException("相机暂时无法拍照，请重试。")))
            } else {
                capturing = true
                fun complete(result: Result<ByteArray>) {
                    mainExecutor.execute {
                        capturing = false
                        if (!disposed) callback(result)
                    }
                }

                try {
                    previewView.display?.let { capture.targetRotation = it.rotation }
                    capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val result = try {
                                Result.success(image.toVisionJpeg())
                            } catch (exception: Exception) {
                                Result.failure(exception)
                            } finally {
                                image.close()
                            }
                            complete(result)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            complete(Result.failure(exception))
                        }
                    })
                } catch (exception: RuntimeException) {
                    complete(Result.failure(exception))
                }
            }
        }

        fun updateReadiness() {
            val available = cameraError == null &&
                previewView.previewStreamState.value == PreviewView.StreamState.STREAMING
            if (available != cameraReady) {
                cameraReady = available
                publishCamera(if (available) camera else null)
            }
        }

        val streamObserver = Observer<PreviewView.StreamState> { updateReadiness() }
        val cameraObserver = Observer<CameraState> { cameraState ->
            cameraError = cameraState.error?.let { error ->
                when (error.code) {
                    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                        "后置摄像头正被占用。关闭其他相机应用后重试。"
                    CameraState.ERROR_CAMERA_DISABLED -> "设备禁用了摄像头。请检查系统设置。"
                    else -> "无法开启后置摄像头，请重试。"
                }
            }
            updateReadiness()
        }

        cameraReady = false
        cameraError = null
        providerFuture.addListener({
            if (!disposed) {
                previewView.doOnLayout {
                    if (!disposed) {
                        try {
                            val cameraProvider = providerFuture.get()
                            provider = cameraProvider
                            previewView.display?.let { capture.targetRotation = it.rotation }
                            val group = UseCaseGroup.Builder()
                                .addUseCase(preview)
                                .addUseCase(capture)
                                .apply { previewView.viewPort?.let { setViewPort(it) } }
                                .build()
                            val boundCamera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                group,
                            )
                            cameraStates = boundCamera.cameraInfo.cameraState
                            cameraStates?.observe(lifecycleOwner, cameraObserver)
                            previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
                        } catch (_: Exception) {
                            cameraError = "无法开启后置摄像头，请重试。"
                            updateReadiness()
                        }
                    }
                }
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            publishCamera(null)
            previewView.animate().cancel()
            publishPreviewCovered(false)
            previewView.previewStreamState.removeObserver(streamObserver)
            cameraStates?.removeObserver(cameraObserver)
            provider?.unbind(preview, capture)
        }
    }

    LaunchedEffect(previewView, cameraReady) {
        previewView.animate().cancel()
        if (cameraReady) {
            previewView.animate().alpha(1f).setDuration(240).withEndAction {
                if (cameraReady) publishPreviewCovered(true)
            }.start()
        } else {
            previewView.alpha = WAITING_PREVIEW_ALPHA
            publishPreviewCovered(false)
        }
    }
    val phase = state.phase
    val caption = when (phase) {
        VisionPhase.LISTENING -> state.partial
        VisionPhase.CHECKING -> state.partial.ifBlank { state.instruction }
        else -> ""
    }
    var webAvailable by remember { mutableStateOf<Boolean?>(null) }
    val motionView = remember(context) {
        runCatching {
            ListeningMotionView(
                context,
                onAvailability = { webAvailable = it },
                onGenerationFrame = { generationFrame() },
            )
        }.getOrNull()
    }
    val cameraLayer = remember(previewView, motionView) {
        FrameLayout(context).apply {
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            if (motionView != null) addView(motionView, FrameLayout.LayoutParams(-1, -1))
        }
    }
    val backdrop = remember(previewView) { ListeningCameraBlur(previewView) }
    DisposableEffect(motionView, backdrop) {
        onDispose {
            motionView?.close()
            backdrop.close()
        }
    }
    LaunchedEffect(motionView) {
        if (motionView != null) {
            delay(1_200L)
            if (webAvailable == null) webAvailable = false
        }
    }

    val webReady = webAvailable == true
    val nativeFallback = motionView == null || webAvailable == false
    var entryDone by remember { mutableStateOf(false) }
    LaunchedEffect(webReady) {
        if (webReady && !entryDone) {
            delay(VISION_ENTRY_MS)
            entryDone = true
        }
    }
    // The animation holds only the empty invitation. Camera, microphone and real ASR stay live.
    val holdingIntro = !nativeFallback && !entryDone &&
        (phase == VisionPhase.ENTERING || phase == VisionPhase.LISTENING) &&
        caption.isBlank() && state.footer == null && cameraError == null
    val motionPhase = when {
        holdingIntro || phase == VisionPhase.ENTERING -> "entering"
        phase == VisionPhase.LISTENING -> "listening"
        phase == VisionPhase.CHECKING -> "checking"
        phase == VisionPhase.GENERATING -> "generating"
        phase == VisionPhase.ANSWERING -> "complete"
        else -> "ready"
    }
    SideEffect {
        motionView?.render(
            motionPhase,
            caption,
            showHint = cameraReady && state.footer == null && cameraError == null,
        )
        val blurPhase = if (holdingIntro || phase == VisionPhase.ANSWERING) VisionPhase.READY else phase
        backdrop.update(blurPhase, active = webReady && cameraReady)
    }
    LaunchedEffect(motionView, webReady, motionPhase) {
        if (motionView == null || !webReady) return@LaunchedEffect
        if (motionPhase == "listening") {
            snapshotFlow { speechLevel.value }.collect(motionView::updateLevel)
        } else {
            motionView.updateLevel(0f)
        }
    }
    if (phase == VisionPhase.GENERATING && nativeFallback) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            generationFrame()
        }
    }
    val nativeMotion = if (nativeFallback) rememberVisionMotion(phase, speechLevel) else null

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = maxWidth / 256
        val density = LocalDensity.current
        val mainText = with(density) { (unit * 10).toSp() }
        val helperText = with(density) { (unit * 8).toSp() }
        AndroidView(factory = { cameraLayer }, modifier = Modifier.fillMaxSize())
        if (nativeFallback && nativeMotion != null) VisionGlow(phase, nativeMotion)

        if (nativeFallback && nativeMotion != null && caption.isNotBlank()) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).offset(y = unit * 124)
                    .size(width = unit * 156, height = unit * 28)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .visionCapsuleShimmer(nativeMotion),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = caption, color = Color.White, fontSize = mainText,
                    fontWeight = FontWeight.Medium, lineHeight = mainText * 1.2f,
                    modifier = Modifier.padding(horizontal = unit * 8),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (webReady && caption.isNotBlank()) {
            Box(Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = caption
            })
        }
        val footer = cameraError ?: state.footer ?: "点击结束收音".takeIf { phase == VisionPhase.LISTENING && !holdingIntro }
        val canStartSpeech = phase == VisionPhase.LISTENING || phase == VisionPhase.READY || phase == VisionPhase.ERROR
        val footerAction = when {
            cameraError != null -> null
            phase == VisionPhase.LISTENING && state.busy -> onFinish
            cameraReady && !state.busy && canStartSpeech -> onMicrophone
            else -> null
        }
        val debugAction: (() -> Unit)? = if (BuildConfig.DEBUG && cameraReady &&
            phase in setOf(VisionPhase.LISTENING, VisionPhase.READY, VisionPhase.ERROR)
        ) {
            { showDebugInput = true }
        } else {
            null
        }
        if (footer != null || phase == VisionPhase.READY && cameraReady) {
            VisionFooter(
                text = footer, unit = unit, fontSize = helperText, onClick = footerAction, onDebug = debugAction,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = unit * 154),
            )
        }
        if (phase == VisionPhase.CHECKING || phase == VisionPhase.GENERATING || phase == VisionPhase.ANSWERING) {
            Box(Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = state.status
            })
        }


    }

    if (BuildConfig.DEBUG && showDebugInput) {
        VisionDebugInput(
            onDismiss = { showDebugInput = false },
            onSubmit = { showDebugInput = false; onSubmit(it) },
        )
    }
}

@Composable
private fun VisionFooter(
    text: String?,
    unit: Dp,
    fontSize: TextUnit,
    onClick: (() -> Unit)?,
    onDebug: (() -> Unit)?,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.width(unit * 222).heightIn(min = unit * 26)
            .clip(RoundedCornerShape(unit * 14))
            .then(
                if (onClick == null && onDebug == null) Modifier else Modifier.combinedClickable(
                    onClick = { onClick?.invoke() }, onLongClick = onDebug,
                    onLongClickLabel = "文字调试".takeIf { onDebug != null }, role = Role.Button,
                ),
            )
            .padding(horizontal = unit * 6, vertical = unit * 3),
        contentAlignment = Alignment.Center,
    ) {
        if (text == null) {
            Icon(painterResource(R.drawable.ic_mic), "开始收音", Modifier.size(unit * 14), tint = Color.White)
        } else {
            Text(
                text, color = Color.White, fontSize = fontSize, lineHeight = fontSize * 1.2f,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.65f), blurRadius = 3f)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// Keep TextureView drawing while CameraX waits for its first presented frame.
private const val WAITING_PREVIEW_ALPHA = 0.01f
private const val VISION_ENTRY_MS = 2_060L

@Composable
private fun VisionDebugInput(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank()) onSubmit(input.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文字调试") },
        text = {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(500) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("输入指令") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
        },
        confirmButton = { TextButton(enabled = input.isNotBlank(), onClick = { submit() }) { Text("提交文字") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun ImageProxy.toVisionJpeg(): ByteArray {
    val source = toBitmap()
    try {
        val crop = Rect(cropRect)
        check(crop.intersect(0, 0, source.width, source.height)) { "相机图片的裁剪区域无效。" }
        val scale = minOf(1f, 1280f / maxOf(crop.width(), crop.height()))
        val transform = Matrix().apply {
            postScale(scale, scale)
            postRotate(imageInfo.rotationDegrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(
            source, crop.left, crop.top, crop.width(), crop.height(), transform, true,
        )
        try {
            return ByteArrayOutputStream().use { buffer ->
                check(oriented.compress(Bitmap.CompressFormat.JPEG, 84, buffer)) { "无法压缩相机图片。" }
                buffer.toByteArray()
            }
        } finally {
            if (oriented !== source) oriented.recycle()
        }
    } finally {
        source.recycle()
    }
}
