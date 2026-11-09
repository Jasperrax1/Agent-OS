package com.musepocket.ai

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.coroutines.coroutineContext

internal data class VisionGlowFrame(
    val elapsedSeconds: Float = 0f,
    val wave: Float = 0f,
    val phaseWave: Float = 0f,
    val level: Float = 0f,
    val generation: Float = 0f,
)

@Composable
internal fun rememberVisionMotion(phase: VisionPhase, level: State<Float>): State<VisionGlowFrame> {
    val frame = remember { mutableStateOf(VisionGlowFrame()) }
    val activePhase = rememberUpdatedState(phase)
    val inputLevel = rememberUpdatedState(level)

    // Follow each refresh on the 60 Hz target. Faster displays can skip intervening
    // frames without adding an independent timer. Draw layers own these state reads.
    LaunchedEffect(Unit) {
        val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        val startedAt = withFrameNanos { it }
        var previous = startedAt
        var generation = 0f
        var envelope = 0f
        while (true) {
            val now = withFrameNanos { it }
            if (now - previous < 13_000_000L) continue
            val delta = ((now - previous) / 1_000_000_000f).coerceAtMost(0.25f)
            val elapsed = (now - startedAt) / 1_000_000_000f
            previous = now
            val rawLevel = if (activePhase.value == VisionPhase.LISTENING) inputLevel.value.value else 0f
            val target = if (rawLevel.isFinite()) rawLevel.coerceIn(0f, 1f) else 0f
            val timeConstant = if (target > envelope) 0.06f else 0.19f
            envelope += (target - envelope) * (1f - exp(-delta / timeConstant))
            val generating = if (activePhase.value == VisionPhase.GENERATING) 1f else 0f
            generation += (generating - generation) * (1f - exp(-delta / 0.10f))
            val motionTime = if (durationScale > 0f) elapsed / durationScale else 1.1f
            val angle = (motionTime % 3.8f) * (2.0 * PI / 3.8)
            frame.value = VisionGlowFrame(
                elapsedSeconds = motionTime,
                wave = if (durationScale > 0f) sin(angle).toFloat() else 0f,
                phaseWave = if (durationScale > 0f) cos(angle).toFloat() else 0f,
                level = envelope,
                generation = generation,
            )
        }
    }
    return frame
}

@Composable
internal fun VisionGlow(phase: VisionPhase, motion: State<VisionGlowFrame>) {
    val currentPhase = rememberUpdatedState(phase)
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawWithCache onDrawBehind {}
            val unit = w / 256f
            val baseTop = Offset(0f, h * 0.53f)
            val baseSize = Size(w, h - baseTop.y)
            val baseGlow = Brush.verticalGradient(
                0f to Color.Transparent,
                0.54f to Color(0xFF8DBDFF).copy(alpha = 0.15f),
                1f to Color(0xFF528FE8).copy(alpha = 0.58f),
                startY = baseTop.y, endY = h,
            )
            val bottom = Offset(w * 0.50f, h * 1.018f)
            val bottomLight = Brush.radialGradient(
                0f to Color(0xFFFFFFEC),
                0.28f to Color(0xFFD5FADE).copy(alpha = 0.92f),
                0.62f to Color(0xFF76D8F4).copy(alpha = 0.54f),
                1f to Color.Transparent,
                center = bottom, radius = w * 0.43f,
            )
            val sparkCenter = Offset(w * 0.50f, h * 0.995f)
            val spark = Brush.horizontalGradient(
                0.20f to Color.Transparent,
                0.40f to Color(0xFFFFE5B8).copy(alpha = 0.9f),
                0.50f to Color(0xFFFFFCF0),
                0.59f to Color(0xFFD2DAFF).copy(alpha = 0.9f),
                0.80f to Color.Transparent,
                startX = 0f, endX = w,
            )
            val washLeft = Offset(w * 0.22f, h * 0.99f)
            val washRight = Offset(w * 0.76f, h * 1.04f)
            val blueWash = Brush.radialGradient(
                0f to Color(0xFFCEFCFF).copy(alpha = 0.73f),
                0.52f to Color(0xFF63B8F7).copy(alpha = 0.42f),
                1f to Color.Transparent,
                center = washLeft, radius = w * 0.59f,
            )
            val warmWash = Brush.radialGradient(
                0f to Color(0xFFFFE7D2).copy(alpha = 0.60f),
                0.56f to Color(0xFFCDCAFF).copy(alpha = 0.34f),
                1f to Color.Transparent,
                center = washRight, radius = w * 0.53f,
            )
            val edge = Brush.sweepGradient(
                0f to Color(0xFF97E5EC),
                0.24f to Color(0xFFA8CCFF),
                0.50f to Color(0xFFFFC1BC),
                0.75f to Color(0xFFFFE6B7),
                1f to Color(0xFF97E5EC),
                center = Offset(w / 2f, h / 2f),
            )
            val generationCenter = Offset(w * 0.48f, h * 0.46f)
            val generationLight = Brush.radialGradient(
                0f to Color(0xFFE5FAFF).copy(alpha = 0.84f),
                0.35f to Color(0xFFB7D9FF).copy(alpha = 0.56f),
                0.68f to Color(0xFFD9CFFF).copy(alpha = 0.23f),
                1f to Color.Transparent,
                center = generationCenter, radius = w * 0.57f,
            )
            val rimSize = Size((w - unit * 2f).coerceAtLeast(0f), (h - unit * 2f).coerceAtLeast(0f))
            val rimRadius = CornerRadius(unit * 31f)
            val rimOffset = Offset(unit, unit)
            val rimStroke = Stroke(unit * 1.25f)
            val rimPath = Path().apply {
                addRoundRect(RoundRect(Rect(rimOffset, rimSize), rimRadius))
            }
            val rimMeasure = PathMeasure().apply { setPath(rimPath, forceClosed = true) }
            val rimHalo = Brush.radialGradient(
                0f to Color(0xFFF0FCFF).copy(alpha = 0.82f),
                0.20f to Color(0xFFCDEBFF).copy(alpha = 0.55f),
                0.58f to Color(0xFFBAA9F1).copy(alpha = 0.29f),
                1f to Color.Transparent,
                center = Offset.Zero, radius = w * 0.18f,
            )

            onDrawBehind {
                val frame = motion.value
                val elapsed = frame.elapsedSeconds
                val settled = rise(elapsed, 0.36f, 0.72f)
                val generating = if (currentPhase.value == VisionPhase.GENERATING) {
                    frame.generation.coerceAtLeast(0.12f)
                } else {
                    frame.generation
                }
                val listening = settled * (1f - generating)
                val level = frame.level
                val drift = frame.wave
                val breath = (1f + frame.phaseWave) * 0.5f

                if (listening > 0.01f) {
                    drawRect(baseGlow, topLeft = baseTop, size = baseSize, alpha = listening)
                    translate(left = drift * w * 0.16f) {
                        scale(0.82f + breath * 0.28f + level * 0.16f,
                            0.38f + breath * 0.24f + level * 0.20f, pivot = bottom) {
                            drawCircle(
                                bottomLight, w * 0.43f, bottom,
                                alpha = listening * (0.42f + breath * 0.27f + level * 0.30f),
                            )
                        }
                    }
                }

                val flash = pulse(elapsed, 0f, 0.028f, 0.082f, 0.18f)
                if (flash > 0f) {
                    scale(0.84f + elapsed * 2.6f, 1f, pivot = sparkCenter) {
                        drawRoundRect(
                            spark, topLeft = Offset(w * 0.17f, h * 0.964f),
                            size = Size(w * 0.66f, h * 0.035f), cornerRadius = CornerRadius(h * 0.023f),
                            alpha = flash * 0.94f,
                        )
                    }
                }

                val wash = pulse(elapsed, 0.065f, 0.19f, 0.33f, 0.49f)
                if (wash > 0f) {
                    val top = -h * rise(elapsed, 0.06f, 0.47f) * 1.12f
                    translate(top = top) {
                        scale(1.22f, 0.51f, pivot = washLeft) {
                            drawCircle(blueWash, w * 0.59f, washLeft, alpha = wash)
                        }
                        scale(1.25f, 0.47f, pivot = washRight) {
                            drawCircle(warmWash, w * 0.53f, washRight, alpha = wash * 0.82f)
                        }
                    }
                }

                val rim = pulse(elapsed, 0.42f, 0.62f, 0.84f, 1.10f)
                if (rim > 0f) {
                    drawRoundRect(edge, rimOffset, rimSize, rimRadius, alpha = rim * 0.82f, style = rimStroke)
                }
                if (generating > 0.005f) {
                    translate(left = w * 0.31f * drift, top = h * 0.23f * frame.phaseWave) {
                        scale(1f, 0.74f, pivot = generationCenter) {
                            drawCircle(generationLight, w * 0.57f, generationCenter, alpha = generating)
                        }
                    }
                    drawRoundRect(edge, rimOffset, rimSize, rimRadius, alpha = generating * 0.76f, style = rimStroke)
                    val point = rimMeasure.getPosition(rimMeasure.length * ((elapsed % 2.9f) / 2.9f))
                    if (point.isSpecified) {
                        translate(left = point.x, top = point.y) {
                            drawCircle(rimHalo, w * 0.18f, Offset.Zero, alpha = generating * 0.91f)
                        }
                    }
                }
            }
        },
    )
}

internal fun Modifier.visionCapsuleShimmer(motion: State<VisionGlowFrame>): Modifier = drawWithCache {
    val pillRadius = CornerRadius(size.height / 2f)
    val stroke = size.height / 28f
    val inset = stroke / 2f
    val shimmerOrigin = Offset(size.width / 2f, size.height * 0.95f)
    val shimmer = Brush.radialGradient(
        0f to Color(0xFFF4FFF3).copy(alpha = 0.90f),
        0.32f to Color(0xFF98EBD1).copy(alpha = 0.65f),
        1f to Color.Transparent,
        center = shimmerOrigin,
        radius = size.width * 0.42f,
    )
    val border = Brush.horizontalGradient(
        0f to Color(0xFF92CCFF).copy(alpha = 0.72f),
        0.45f to Color.White.copy(alpha = 0.64f),
        1f to Color(0xFFF7BAF3).copy(alpha = 0.77f),
    )
    val interior = Brush.verticalGradient(
        0f to Color(0xFFE0D5F5).copy(alpha = 0.31f),
        0.46f to Color.Transparent,
        1f to Color(0xFFD4D8FF).copy(alpha = 0.67f),
    )
    val borderSize = Size(size.width - stroke, size.height - stroke)
    val borderStroke = Stroke(stroke)

    onDrawBehind {
        val frame = motion.value
        drawRoundRect(interior, cornerRadius = pillRadius)
        drawRoundRect(border, Offset(inset, inset), borderSize, pillRadius, style = borderStroke)
        translate(left = size.width * 0.23f * frame.wave) {
            scale(1f, 0.22f + 0.16f * frame.level, pivot = shimmerOrigin) {
                drawCircle(shimmer, size.width * 0.42f, shimmerOrigin, alpha = 0.26f + 0.60f * frame.level)
            }
        }
    }
}

private fun rise(value: Float, start: Float, end: Float): Float {
    val fraction = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

private fun pulse(value: Float, start: Float, peak: Float, release: Float, end: Float): Float =
    rise(value, start, peak) * (1f - rise(value, release, end))
