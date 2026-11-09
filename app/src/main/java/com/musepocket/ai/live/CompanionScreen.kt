package com.musepocket.ai.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musepocket.ai.live.character.CharacterState
import com.musepocket.ai.live.character.PokeSide
import com.musepocket.ai.live.character.VideoStage
import kotlin.math.PI
import kotlin.math.sin

private val subtitleGlass = Brush.verticalGradient(
    0f to Color(0x66BDC9FD),
    0.27f to Color(0x3C8597C3),
    0.64f to Color(0x298B91B2),
    1f to Color(0x6BBFC9F7),
)
private val subtitleText = Color.White
private val subtitleTopLight = Brush.verticalGradient(
    0f to Color(0x64E2DBFF),
    0.22f to Color(0x21E2DBFF),
    0.54f to Color.Transparent,
)

@Composable
internal fun CompanionScreen(
    viewModel: CompanionViewModel,
    onCharacterFirstFrame: (CharacterState, Int) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val unit = maxWidth / 256f
        val cardShape = RoundedCornerShape(unit * 32f)
        val density = LocalDensity.current

        val speechScale = if (ui.speaking) {
            val pulse = rememberInfiniteTransition(label = "talk").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(320, easing = LinearEasing), RepeatMode.Reverse),
                label = "talkPulse",
            )
            pulse
        } else {
            null
        }

        VideoStage(
            state = ui.state,
            playToken = ui.playToken,
            modifier = Modifier.align(Alignment.Center)
                .absoluteOffset(x = -unit * 3f)
                .size(unit * 169f)
                .graphicsLayer {
                    val scale = 1f + (speechScale?.value ?: 0f) * 0.012f
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(viewModel, haptics) {
                    detectTapGestures { tap ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.poke(if (tap.x < size.width / 2f) PokeSide.LEFT else PokeSide.RIGHT)
                    }
                },
            onFinished = viewModel::onVideoFinished,
            onFirstFrame = onCharacterFirstFrame,
        )

        BellySubtitle(
            text = if (ui.speaking) ui.replySubtitle else ui.subtitle,
            unit = unit,
            modifier = Modifier.align(Alignment.TopCenter).absoluteOffset(x = -unit * 3f, y = unit * 134f),
        )

        ui.voiceError?.let { error ->
            Text(
                error,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = unit * 6f)
                    .widthIn(max = unit * 214f)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(unit * 8f))
                    .padding(horizontal = unit * 8f, vertical = unit * 2f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = Color(0xFFF6EEE6),
                fontSize = with(density) { (unit * 8.5f).toSp() },
                lineHeight = with(density) { (unit * 11f).toSp() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Box(Modifier.fillMaxSize().border(unit * 0.8f, Color.White.copy(alpha = 0.24f), cardShape))
    }
}

@Composable
private fun BellySubtitle(text: String?, unit: Dp, modifier: Modifier) {
    var lastText by remember { mutableStateOf("") }
    if (text != null && text != lastText) SideEffect { lastText = text }

    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { it / 5 },
        exit = fadeOut(tween(120)),
    ) {
        val phase = rememberInfiniteTransition(label = "subtitle").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing)),
            label = "subtitleShimmer",
        )
        Box(
            modifier = Modifier.widthIn(min = unit * 38f, max = unit * 206f).height(unit * 28f)
                .clip(CircleShape).bellySubtitleGlass(phase)
                .padding(horizontal = unit * 8f),
            contentAlignment = Alignment.Center,
        ) {
            BellyTranscriptText(text ?: lastText, unit)
        }
    }
}

private fun Modifier.bellySubtitleGlass(phase: State<Float>): Modifier = drawWithCache {
    val width = size.width
    val height = size.height
    val cyanCenter = Offset(width * 0.17f, height * 1.02f)
    val ivoryCenter = Offset(width * 0.51f, height * 1.08f)
    val roseCenter = Offset(width * 0.87f, height * 0.95f)
    val cyanRadius = (width * 0.48f).coerceAtLeast(1f)
    val ivoryRadius = (width * 0.53f).coerceAtLeast(1f)
    val roseRadius = (width * 0.42f).coerceAtLeast(1f)
    val cyan = Brush.radialGradient(
        0f to Color(0xADA9D9FF), 0.45f to Color(0x6E7AB7F9), 1f to Color.Transparent,
        center = cyanCenter, radius = cyanRadius,
    )
    val ivory = Brush.radialGradient(
        0f to Color(0xF5FFFDF4), 0.50f to Color(0xA7D2F8F7), 1f to Color.Transparent,
        center = ivoryCenter, radius = ivoryRadius,
    )
    val rose = Brush.radialGradient(
        0f to Color(0xAFFFBCEA), 0.45f to Color(0x73D4A0FB), 1f to Color.Transparent,
        center = roseCenter, radius = roseRadius,
    )
    onDrawBehind {
        drawRect(subtitleGlass)
        drawRect(subtitleTopLight)
        val angle = phase.value
        translate(left = width * 0.09f * sin(angle)) {
            drawGlassLight(cyan, cyanCenter, cyanRadius, height * 0.59f)
            drawGlassLight(rose, roseCenter, roseRadius, height * 0.64f)
            drawGlassLight(ivory, ivoryCenter, ivoryRadius, height * (0.58f + 0.05f * sin(angle + 1f)))
        }
    }
}

private fun DrawScope.drawGlassLight(brush: Brush, center: Offset, radius: Float, spread: Float) {
    scale(scaleX = 1f, scaleY = spread / radius, pivot = center) {
        drawCircle(brush, radius = radius, center = center, alpha = 0.80f)
    }
}

@Composable
private fun BellyTranscriptText(text: String, unit: Dp) {
    var previous by remember { mutableStateOf("") }
    val change = remember(text) {
        // ASR may revise earlier characters. Retain only the unchanged prefix, without splitting a code point.
        maxOf(
            text.commonPrefixWith(previous).length,
            text.offsetByCodePoints(text.length, -minOf(12, text.codePointCount(0, text.length))),
        )
    }
    SideEffect { previous = text }
    val duration = 170 + 14 * (text.codePointCount(change, text.length) - 1).coerceAtLeast(0)
    val pop = remember(text) { Animatable(if (change == text.length) duration.toFloat() else 0f) }
    LaunchedEffect(pop) {
        pop.animateTo(duration.toFloat(), tween(duration, easing = LinearEasing))
    }
    val elapsed = pop.value
    val animatedText = remember(text, elapsed) {
        buildAnnotatedString {
            append(text)
            var index = change
            var delayMs = 0
            while (index < text.length && elapsed < duration) {
                val next = index + Character.charCount(text.codePointAt(index))
                val progress = ((elapsed - delayMs) / 170f).coerceIn(0f, 1f)
                if (progress < 1f) {
                    val lift = if (progress < 0.7f) {
                        -0.24f + 0.31f * FastOutSlowInEasing.transform(progress / 0.7f)
                    } else {
                        0.07f * (1f - progress) / 0.3f
                    }
                    addStyle(
                        SpanStyle(color = subtitleText.copy(alpha = 0.55f + 0.45f * progress),
                            baselineShift = BaselineShift(lift)),
                        index, next,
                    )
                }
                index = next
                delayMs += 14
            }
        }
    }
    val density = LocalDensity.current
    val fontSize = with(density) { (unit * 10f).toSp() }
    Text(
        text = animatedText,
        fontSize = fontSize,
        lineHeight = fontSize * 1.2f,
        fontWeight = FontWeight.Medium,
        color = subtitleText,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.42f), Offset(0f, 1f), 3f)),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.StartEllipsis,
    )
}
