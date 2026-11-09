package com.musepocket.ai

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LocalFrameUnit = compositionLocalOf { 1.dp }
private val LocalResultArt = compositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }
private const val CARD_WIDTH = 256f
private const val CARD_HEIGHT = 192f

@Composable
internal fun LandscapeShell(content: @Composable () -> Unit) {
    val assets = LocalContext.current.assets
    var resultArt by remember(assets) { mutableStateOf(emptyMap<String, ImageBitmap>()) }
    LaunchedEffect(assets) {
        resultArt = withContext(Dispatchers.IO) {
            listOf("controller.png", "player.png").associateWith { decodeLandscapeBitmap(assets, it, 1) }
        }
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val unit = minOf(maxWidth / 427f, maxHeight / 192f)
        CompositionLocalProvider(LocalFrameUnit provides unit, LocalResultArt provides resultArt) {
            Box(Modifier.size(unit * 427f, unit * 192f).clip(RoundedCornerShape(unit * 33.5f))) {
                val body = rememberLandscapeBitmap("camera-body.png", sampleSize = 2)
                CameraRing(body, y = 107f, cropX = -238.5975f, cropY = -142.86125f, alpha = 1f)
                CameraRing(body, y = 10.5f, cropX = -239.7225f, cropY = -18.30625f, alpha = 0.9f)
                Box(
                    Modifier.frameSlot(unit, 41f, 0f, CARD_WIDTH, CARD_HEIGHT)
                        .clip(RoundedCornerShape(unit * 32f)),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun LandscapeHome(notice: String?, onVision: () -> Unit, onLive: () -> Unit) {
    val unit = LocalFrameUnit.current
    val cardShape = RoundedCornerShape(unit * 32f)
    Box(Modifier.fillMaxSize().graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                // The exported SVG filter clips the remaining blur tail at reference y = 158.
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                        startY = (unit * 149f).toPx(),
                        endY = (unit * 158f).toPx(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        ) {
            ReferenceImage("home-glow-1.png", Modifier.frameSlot(unit, -48f, -29f, 349.2f, 195.13f))
            ReferenceImage("home-glow-2.png", Modifier.frameSlot(unit, -35.94f, -38.02f, 326.686f, 204.223f))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f))),
            ),
        )
        Box(Modifier.fillMaxSize().border(unit * 0.8f, Color.White.copy(alpha = 0.24f), cardShape))
        ReferenceImage(
            "home-clock.png",
            Modifier.frameSlot(unit, 66.865f, 34f, 121.93f, 64.494f)
                .semantics { contentDescription = "23:48" },
            blendMode = BlendMode.Plus,
        )
        FrameText(
            "9月10日 星期三 七月十九",
            Modifier.fillMaxWidth().offset(y = unit * 108.6f),
            14f,
            Color.White.copy(alpha = 0.4f),
        )
        SpringButton(
            Modifier.frameSlot(unit, 15f, 143f, 32f, 32f),
            "打开语音 Live",
            onClick = onLive,
        ) {
            ReferenceImage("home-left.png", Modifier.fillMaxSize())
        }
        SpringButton(
            Modifier.frameSlot(unit, 88f, 144f, 80f, 32f),
            "电量 75%",
        ) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.1f), CircleShape)) {
                ReferenceImage("home-battery.png", Modifier.frameSlot(unit, 12f, 4f, 24f, 24f))
                FrameText(
                    "75%",
                    Modifier.absoluteOffset(x = unit * 46f, y = unit * 9f),
                    11f,
                    Color.White,
                )
            }
        }
        SpringButton(
            Modifier.frameSlot(unit, 207f, 143f, 32f, 32f),
            "开启视觉",
            onClick = onVision,
        ) {
            ReferenceImage("home-grid.png", Modifier.fillMaxSize())
        }
        if (notice != null) {
            FrameText(
                notice,
                Modifier.fillMaxWidth().offset(y = unit * 127f),
                9f,
                Color(0xFFFFB9B9),
            )
        }
    }
}

@Composable
internal fun LandscapeResult(screen: Screen, onExit: () -> Unit) {
    val unit = LocalFrameUnit.current
    val controller = screen == Screen.AGENT_CONTROLLER
    val image = if (controller) "controller.png" else "player.png"
    val closeX = if (controller) 215f else 11f
    val closeY = if (controller) 11f else 19f
    Box(Modifier.fillMaxSize()) {
        ReferenceImage(
            image,
            Modifier.fillMaxSize().semantics {
                contentDescription = if (controller) {
                    "Agent 控制器，演示用量。本次会话 73%，所有模型 10%。"
                } else {
                    "视频播放器演示，时长 45 分钟。"
                }
            },
        )
        SpringButton(
            Modifier.frameSlot(unit, closeX, closeY, 28f, 28f),
            "关闭并返回主页",
            onClick = onExit,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.White.copy(alpha = if (controller) 0.01f else 0.1f), CircleShape)
                    .border(
                        unit * 0.6f,
                        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                ReferenceImage("close.png", Modifier.size(unit * 14f))
            }
        }
    }
}

@Composable
private fun SpringButton(
    modifier: Modifier,
    label: String,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 650f),
        label = "buttonScale",
    )
    Box(
        modifier.semantics { contentDescription = label }
            .clickable(interactions, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
        }) {
            content()
        }
    }
}

@Composable
private fun FrameText(text: String, modifier: Modifier, size: Float, color: Color) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(
            fontSize = (LocalFrameUnit.current.value * size).sp,
            fontWeight = FontWeight.Medium,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

@Composable
private fun CameraRing(body: ImageBitmap, y: Float, cropX: Float, cropY: Float, alpha: Float) {
    val unit = LocalFrameUnit.current
    Canvas(Modifier.frameSlot(unit, 329f, y, 75f, 72.5f)) {
        val clip = Path().apply { addOval(Rect(0f, 0f, size.width, size.height)) }
        clipPath(clip) {
            translate((unit * cropX).toPx(), (unit * cropY).toPx()) {
                drawImage(
                    image = body,
                    dstSize = IntSize((unit * 328.635f).roundToPx(), (unit * 230.463f).roundToPx()),
                    alpha = alpha,
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

@Composable
private fun ReferenceImage(name: String, modifier: Modifier, blendMode: BlendMode = BlendMode.SrcOver) {
    val bitmap = rememberLandscapeBitmap(name)
    Canvas(modifier) {
        drawImage(
            image = bitmap,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            blendMode = blendMode,
            filterQuality = FilterQuality.Medium,
        )
    }
}

@Composable
private fun rememberLandscapeBitmap(name: String, sampleSize: Int = 1): ImageBitmap {
    val assets = LocalContext.current.assets
    return LocalResultArt.current[name] ?: remember(assets, name, sampleSize) {
        decodeLandscapeBitmap(assets, name, sampleSize)
    }
}

private fun decodeLandscapeBitmap(assets: AssetManager, name: String, sampleSize: Int): ImageBitmap =
    assets.open("landscape/$name").use { input ->
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        checkNotNull(BitmapFactory.decodeStream(input, null, options)).asImageBitmap()
    }

private fun Modifier.frameSlot(unit: Dp, x: Float, y: Float, width: Float, height: Float): Modifier =
    absoluteOffset(unit * x, unit * y)
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .size(unit * width, unit * height)
