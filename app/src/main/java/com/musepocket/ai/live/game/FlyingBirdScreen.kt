package com.musepocket.ai.live.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.random.Random

private const val WORLD_WIDTH = 640f
private const val WORLD_HEIGHT = 480f
private const val BIRD_X = 140f
private const val BIRD_WIDTH = 32f
private const val BIRD_HEIGHT = 28f
private const val GATE_WIDTH = 58f
private const val GAP_HEIGHT = 160f
private const val GATE_SPACING = 280f
private const val FRAME_NANOS = 16_666_667f

private val sky = Brush.verticalGradient(
    listOf(Color(0xFF123A71), Color(0xFF195E9C), Color(0xFF184C83)),
)
private val gateFill = Color(0xFF184876)
private val gateEdge = Color(0xFF83E2FF)
private val glass = Color(0xAD10375E)
private val controlBorder = Color(0x627AC3FC)
private val subtitle = Color(0xFFCDDEEF)

@Composable
internal fun FlyingBirdScreen(onClose: () -> Unit) {
    val game = remember { BirdGame() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(game, lifecycle, game.phase) {
        if (game.phase != FlightPhase.FLYING) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = withFrameNanos { it }
            while (game.phase == FlightPhase.FLYING) {
                val now = withFrameNanos { it }
                game.advance(((now - previous) / FRAME_NANOS).coerceIn(0f, 2f))
                previous = now
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(sky)) {
        val unit = maxWidth / 256f
        val density = LocalDensity.current
        val action = when (game.phase) {
            FlightPhase.READY -> "开始飞行"
            FlightPhase.FLYING -> "振翅"
            FlightPhase.CRASHED -> "重新开始"
        }
        Canvas(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = action,
                onClick = game::flap,
            ).semantics { contentDescription = "Flying Bird 游戏画面" },
        ) {
            scale(size.width / WORLD_WIDTH, size.height / WORLD_HEIGHT, pivot = Offset.Zero) {
                // Read only in draw so flight does not recompose the controls each frame.
                drawFlight(game, game.starShift)
            }
        }

        Box(
            Modifier.align(Alignment.TopStart).padding(unit * 14f)
                .background(glass, RoundedCornerShape(unit * 10f))
                .padding(horizontal = unit * 10f, vertical = unit * 4f),
        ) {
            Text(
                text = "分数 ${game.score}",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = with(density) { (unit * 10f).toSp() },
            )
        }

        if (game.phase != FlightPhase.FLYING) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(top = unit * 52f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (game.phase == FlightPhase.READY) "Flying Bird" else "飞行结束",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = with(density) { (unit * 18f).toSp() },
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (game.phase == FlightPhase.READY) "点击画面开始" else "点击画面重试",
                    color = subtitle,
                    fontSize = with(density) { (unit * 10f).toSp() },
                    textAlign = TextAlign.Center,
                )
            }
        }

        Canvas(
            Modifier.align(Alignment.TopEnd).padding(unit * 12f)
                .size(unit * 34f)
                .clip(CircleShape)
                .background(glass)
                .border(unit * 0.6f, controlBorder, CircleShape)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "关闭游戏" },
        ) {
            val half = size.minDimension * 0.15f
            val stroke = size.minDimension * 0.05f
            drawLine(Color.White, center - Offset(half, half), center + Offset(half, half), stroke, StrokeCap.Round)
            drawLine(Color.White, center + Offset(-half, half), center + Offset(half, -half), stroke, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawFlight(game: BirdGame, starShift: Float) {
    for (index in 0 until 14) {
        val x = (WORLD_WIDTH + (index * 127f % WORLD_WIDTH) - starShift) % WORLD_WIDTH
        val y = 23f + (index * 67f % (WORLD_HEIGHT - 46f))
        drawCircle(Color(0x527AC3FC), if (index % 3 == 0) 2.5f else 1.5f, Offset(x, y))
    }

    for (gate in game.gates) {
        drawGate(gate.x, 0f, gate.gapTop, gate.gapTop - 13f)
        val lowerY = gate.gapTop + GAP_HEIGHT
        drawGate(gate.x, lowerY, WORLD_HEIGHT - lowerY, lowerY)
    }

    translate(BIRD_X, game.birdY) {
        drawOval(Color(0xFF112F51), Offset(-2f, 1f), Size(BIRD_WIDTH + 4f, BIRD_HEIGHT + 2f))
        drawOval(Color(0xFFF6BD44), size = Size(BIRD_WIDTH, BIRD_HEIGHT))
        drawOval(Color(0xFFFFE39D), Offset(2f, 10f), Size(17f, 13f))
        drawCircle(Color.White, 6f, Offset(23f, 9f))
        drawCircle(Color(0xFF12375A), 3f, Offset(25f, 9f))
        drawRoundRect(Color(0xFFE78739), Offset(24f, 15f), Size(8f, 6f), CornerRadius(3f))
    }
}

private fun DrawScope.drawGate(x: Float, top: Float, height: Float, edgeTop: Float) {
    drawRect(gateFill, Offset(x, top), Size(GATE_WIDTH, height))
    drawRect(gateEdge.copy(alpha = 0.6f), Offset(x, top), Size(2f, height))
    drawRect(gateEdge.copy(alpha = 0.3f), Offset(x + GATE_WIDTH - 2f, top), Size(2f, height))
    drawRoundRect(gateEdge, Offset(x, edgeTop), Size(GATE_WIDTH, 13f), CornerRadius(3f))
}

private enum class FlightPhase { READY, FLYING, CRASHED }

private class Gate(var x: Float, var gapTop: Float) {
    var passed = false
}

private class BirdGame {
    val gates = arrayOf(Gate(WORLD_WIDTH, 135f), Gate(WORLD_WIDTH + GATE_SPACING, 160f))
    var birdY = 210f
        private set
    var starShift by mutableFloatStateOf(0f)
        private set
    var phase by mutableStateOf(FlightPhase.READY)
        private set
    var score by mutableIntStateOf(0)
        private set
    private var velocity = 0f

    fun flap() {
        if (phase == FlightPhase.CRASHED) {
            birdY = 210f
            starShift = 0f
            score = 0
            for ((index, gate) in gates.withIndex()) {
                gate.x = WORLD_WIDTH + index * GATE_SPACING
                gate.gapTop = if (index == 0) 135f else 160f
                gate.passed = false
            }
        }
        velocity = -5.5f
        phase = FlightPhase.FLYING
    }

    fun advance(frameFraction: Float) {
        velocity = minOf(7f, velocity + 0.28f * frameFraction)
        birdY += velocity * frameFraction
        var collided = birdY <= 0f || birdY + BIRD_HEIGHT >= WORLD_HEIGHT
        for ((index, gate) in gates.withIndex()) {
            gate.x -= 2.6f * frameFraction
            if (gate.x < BIRD_X + BIRD_WIDTH && gate.x + GATE_WIDTH > BIRD_X &&
                (birdY < gate.gapTop || birdY + BIRD_HEIGHT > gate.gapTop + GAP_HEIGHT)
            ) {
                collided = true
            }
            if (!gate.passed && gate.x + GATE_WIDTH < BIRD_X) {
                score++
                gate.passed = true
            }
            if (gate.x < -GATE_WIDTH) {
                gate.x = gates[1 - index].x + GATE_SPACING
                gate.gapTop = 85f + Random.nextInt(105)
                gate.passed = false
            }
        }
        starShift = (starShift + 0.55f * frameFraction) % WORLD_WIDTH
        if (collided) phase = FlightPhase.CRASHED
    }
}
