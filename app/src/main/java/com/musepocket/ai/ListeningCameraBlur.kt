package com.musepocket.ai

import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/** Applies backdrop blur to the camera's render node while its UI remains sharp. */
internal class ListeningCameraBlur(target: View) {
    private val renderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Renderer(target) else null

    fun update(phase: VisionPhase, active: Boolean) {
        val mode = if (active) {
            when (phase) {
                VisionPhase.LISTENING, VisionPhase.CHECKING, VisionPhase.ANSWERING -> Mode.PROGRESSIVE
                VisionPhase.GENERATING -> Mode.FULL
                else -> Mode.NONE
            }
        } else {
            Mode.NONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) renderer?.update(mode)
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) renderer?.close()
    }

    private enum class Mode { NONE, PROGRESSIVE, FULL }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Renderer(private val target: View) {
        private val needsRefresh = Build.VERSION.SDK_INT == Build.VERSION_CODES.S
        private var mode = Mode.NONE
        private var cache: SizedEffects? = null
        private var applied: RenderEffect? = null
        private var closed = false
        private var refreshPending = false
        private var useAlternate = false
        private val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyEffect() }
        private val refresh = Runnable {
            refreshPending = false
            useAlternate = !useAlternate
            applyEffect()
        }
        private val onAttach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = applyEffect()

            override fun onViewDetachedFromWindow(view: View) {
                stopRefresh()
                setEffect(null)
            }
        }

        init {
            target.addOnLayoutChangeListener(onLayout)
            if (needsRefresh) target.addOnAttachStateChangeListener(onAttach)
        }

        fun update(next: Mode) {
            if (closed || mode == next) return
            mode = next
            applyEffect()
        }

        private fun applyEffect() {
            if (closed) return
            val width = target.width
            val height = target.height
            if (mode == Mode.NONE || width <= 0 || height <= 0 ||
                (needsRefresh && !target.isAttachedToWindow)
            ) {
                stopRefresh()
                setEffect(null)
                return
            }

            val effects = cache?.takeIf { it.width == width && it.height == height }
                ?: SizedEffects(width, height).also { cache = it }
            setEffect(if (mode == Mode.FULL) effects.full(useAlternate) else effects.progressive(useAlternate))
            // Android 12's HWUI snapshot cache omits the layer's frame generation. Switching
            // between equivalent filters refreshes it without changing the camera surface.
            if (needsRefresh && !refreshPending) {
                refreshPending = target.postDelayed(refresh, REFRESH_INTERVAL_MS)
            }
        }

        private fun stopRefresh() {
            if (refreshPending) target.removeCallbacks(refresh)
            refreshPending = false
            useAlternate = false
        }

        private fun setEffect(effect: RenderEffect?) {
            if (applied === effect) return
            target.setRenderEffect(effect)
            applied = effect
        }

        fun close() {
            if (closed) return
            closed = true
            target.removeOnLayoutChangeListener(onLayout)
            if (needsRefresh) target.removeOnAttachStateChangeListener(onAttach)
            stopRefresh()
            setEffect(null)
            cache = null
        }

        private companion object {
            const val REFRESH_INTERVAL_MS = 34L
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class SizedEffects(val width: Int, val height: Int) {
        private val progressive by lazy(LazyThreadSafetyMode.NONE) { createProgressive() }
        private val alternateProgressive by lazy(LazyThreadSafetyMode.NONE) { createProgressive() }
        private val full by lazy(LazyThreadSafetyMode.NONE) { createFull() }
        private val alternateFull by lazy(LazyThreadSafetyMode.NONE) { createFull() }

        fun progressive(alternate: Boolean): RenderEffect = if (alternate) alternateProgressive else progressive

        fun full(alternate: Boolean): RenderEffect = if (alternate) alternateFull else full

        private fun createProgressive(): RenderEffect {
            // Each blur samples the original camera content, as in the browser's two bands.
            val original = RenderEffect.createOffsetEffect(0f, 0f)
            val shallow = blendOver(original, width * .012f, height * .016f, .50f, .82f)
            return blendOver(shallow, width * .036f, height * .048f, .63f, 1f)
        }

        private fun createFull(): RenderEffect {
            val sigma = width * (38f / 512f)
            return blur(sigma, sigma)
        }

        private fun blendOver(
            base: RenderEffect,
            sigmaX: Float,
            sigmaY: Float,
            start: Float,
            end: Float,
        ): RenderEffect {
            val gradient = LinearGradient(
                0f, height * start, 0f, height * end,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP,
            )
            val mask = RenderEffect.createShaderEffect(gradient)
            val band = RenderEffect.createBlendModeEffect(blur(sigmaX, sigmaY), mask, BlendMode.DST_IN)
            return RenderEffect.createBlendModeEffect(base, band, BlendMode.SRC_OVER)
        }

        private fun blur(sigmaX: Float, sigmaY: Float): RenderEffect = RenderEffect.createBlurEffect(
            radiusForSigma(sigmaX), radiusForSigma(sigmaY), Shader.TileMode.CLAMP,
        )

        // Android HWUI converts radius to sigma. CSS/SVG specify sigma directly.
        private fun radiusForSigma(sigma: Float): Float = ((sigma - .5f) / .57735f).coerceAtLeast(0f)
    }
}
