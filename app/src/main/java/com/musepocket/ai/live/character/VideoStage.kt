package com.musepocket.ai.live.character

import android.content.Context
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun VideoStage(
    state: CharacterState,
    playToken: Int,
    modifier: Modifier,
    onFinished: (CharacterState) -> Unit,
    onFirstFrame: (CharacterState, Int) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val players = remember(context) { arrayOf(createPlayer(context), createPlayer(context)) }
    val loadedStates = remember(players) { arrayOfNulls<CharacterState>(players.size) }
    val loadedTokens = remember(players) { IntArray(players.size) }
    val alpha = remember(players) { Array(players.size) { Animatable(PREPARING_ALPHA) } }
    var active by remember(players) { mutableIntStateOf(-1) }
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val latestToken by rememberUpdatedState(playToken)
    val finished by rememberUpdatedState(onFinished)
    val firstFrame by rememberUpdatedState(onFirstFrame)

    DisposableEffect(players, lifecycle) {
        val listeners = players.mapIndexed { index, player ->
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val played = loadedStates[index] ?: return
                    if (index == active && playbackState == Player.STATE_ENDED && started &&
                        loadedTokens[index] == latestToken && !played.loop
                    ) {
                        finished(played)
                    }
                }
            }.also(player::addListener)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> {
                    started = false
                    players.forEach(ExoPlayer::pause)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            players.forEachIndexed { index, player ->
                player.removeListener(listeners[index])
                player.release()
            }
        }
    }

    LaunchedEffect(players, state, playToken, started) {
        if (!started) return@LaunchedEffect
        val visible = active
        val target = if (visible == 0) 1 else 0
        val player = players[target]
        if (visible >= 0) {
            alpha[visible].snapTo(1f)
            players[visible].play()
        }
        alpha[target].snapTo(PREPARING_ALPHA)
        player.stop()
        loadedStates[target] = null
        if (visible >= 0 && loadedStates[visible] == state && loadedTokens[visible] == playToken) return@LaunchedEffect

        loadedStates[target] = state
        loadedTokens[target] = playToken
        player.setMediaItem(
            MediaItem.Builder()
                .setUri("asset:///${state.asset}")
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(state.startMs).build(),
                )
                .build(),
        )
        player.repeatMode = if (state.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        try {
            if (!player.prepareUntilFirstFrame()) return@LaunchedEffect
            active = target
            if (visible < 0) alpha[target].snapTo(1f)
            coroutineScope {
                if (visible >= 0) {
                    // Fade over the opaque outgoing frame. Fading both layers exposes the black card.
                    launch { alpha[target].animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
                }
                // Allow Compose and TextureView to present the newly active layer before timing it.
                withFrameNanos { }
                withFrameNanos { }
                firstFrame(state, playToken)
            }
            if (visible >= 0) {
                alpha[visible].snapTo(PREPARING_ALPHA)
                players[visible].stop()
                loadedStates[visible] = null
            }
        } finally {
            // A timeout or canceled request leaves the current clip visible.
            if (active != target) {
                player.stop()
                loadedStates[target] = null
            }
        }
    }

    Box(modifier) {
        players.forEachIndexed { index, player ->
            AndroidView(
                modifier = Modifier.fillMaxSize().zIndex(if (index == active) 1f else 0f)
                    .graphicsLayer { this.alpha = alpha[index].value },
                factory = { TextureView(it).also(player::setVideoTextureView) },
            )
        }
    }
}

private fun createPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context).build().apply { volume = 0f }

private suspend fun ExoPlayer.prepareUntilFirstFrame(): Boolean {
    val firstFrame = CompletableDeferred<Boolean>()
    val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            firstFrame.complete(true)
        }

        override fun onPlayerError(error: PlaybackException) {
            firstFrame.complete(false)
        }
    }
    addListener(listener)
    return try {
        prepare()
        play()
        withTimeoutOrNull(2_500) { firstFrame.await() } == true
    } finally {
        removeListener(listener)
    }
}

// Keep the texture drawing while its decoder starts. The active layer covers it entirely.
private const val PREPARING_ALPHA = 0.01f
