package com.musepocket.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Draws the shared motion above the native camera. The native screen owns all input and camera blur.
 */
@SuppressLint("SetJavaScriptEnabled") // The document and its scripts are fixed, bundled assets.
internal class ListeningMotionView(
    context: Context,
    private val onAvailability: (Boolean) -> Unit,
    private val onGenerationFrame: () -> Unit,
) : WebView(context) {
    private data class Presentation(val phase: String, val text: String, val showHint: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val bootstrapTimeout = Runnable { fail() }
    private val sendLevel = Runnable { flush() }
    private var presentation = Presentation("ready", "", false)
    private var sentPresentation: Presentation? = null
    private var level = 0
    private var sentLevel = -1
    private var lastLevelSentAt = 0L
    private var phaseEpoch = 0L
    private var generationRequested = -1L
    private var bootstrapping = false
    private var inFlight = false
    private var available = false
    private var failed = false
    private var rendererGone = false
    private var closed = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                if (closed || failed || bootstrapping) return
                bootstrapping = true
                evaluateJavascript("Boolean(window.museAndroidListening)") { result ->
                    if (closed || failed) return@evaluateJavascript
                    if (result != "true") {
                        fail()
                        return@evaluateJavascript
                    }
                    main.removeCallbacks(bootstrapTimeout)
                    available = true
                    onAvailability(true)
                    flush()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail()
            }

            @RequiresApi(26)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererGone = true
                fail()
                close()
                return true
            }
        }
        main.postDelayed(bootstrapTimeout, BOOTSTRAP_TIMEOUT_MS)
        loadDataWithBaseURL(ASSET_BASE, DOCUMENT, "text/html", "UTF-8", null)
    }

    fun render(phase: String, text: String, showHint: Boolean) {
        if (closed || failed) return
        val next = Presentation(phase, text, showHint)
        if (next == presentation) return
        if (phase != presentation.phase) {
            phaseEpoch++
            if (phase != "listening") level = 0
        }
        presentation = next
        flush()
    }

    fun updateLevel(level: Float) {
        if (closed || failed || presentation.phase != "listening") return
        val next = if (level.isFinite()) (level.coerceIn(0f, 1f) * 100).roundToInt() else 0
        if (next == this.level) return
        this.level = next
        flush()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    private fun flush() {
        if (closed || failed || !available || inFlight) return
        main.removeCallbacks(sendLevel)
        val current = presentation
        val currentLevel = level
        val levelChanged = currentLevel != sentLevel
        if (current == sentPresentation) {
            if (!levelChanged) return
            val wait = lastLevelSentAt + LEVEL_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait > 0) {
                main.postDelayed(sendLevel, wait)
                return
            }
        }

        val epoch = phaseEpoch
        val payload = JSONObject()
            .put("phase", current.phase)
            .put("text", current.text)
            .put("showHint", current.showHint)
            .put("level", currentLevel / 100.0)
        sentPresentation = current
        sentLevel = currentLevel
        if (levelChanged) lastLevelSentAt = SystemClock.uptimeMillis()
        inFlight = true
        evaluateJavascript("window.museAndroidListening.update($payload); true;") { result ->
            inFlight = false
            if (closed || failed) return@evaluateJavascript
            if (result != "true") {
                fail()
                return@evaluateJavascript
            }
            if (current.phase == GENERATING && isCurrentGeneration(epoch) && generationRequested != epoch) {
                generationRequested = epoch
                awaitGenerationFrame(epoch)
            }
            flush()
        }
    }

    private fun awaitGenerationFrame(epoch: Long) {
        postVisualStateCallback(epoch, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (!isCurrentGeneration(requestId)) return
                // Chromium's new state is ready to draw, then let the host present that frame.
                postOnAnimation {
                    postOnAnimation {
                        if (isCurrentGeneration(requestId)) onGenerationFrame()
                    }
                }
            }
        })
    }

    private fun isCurrentGeneration(epoch: Long): Boolean =
        available && !closed && !failed && phaseEpoch == epoch && presentation.phase == GENERATING

    private fun fail() {
        if (closed || failed) return
        available = false
        failed = true
        main.removeCallbacks(bootstrapTimeout)
        main.removeCallbacks(sendLevel)
        onAvailability(false)
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(bootstrapTimeout)
        main.removeCallbacks(sendLevel)
        if (!rendererGone) {
            stopLoading()
            onPause()
        }
        if (!isAttachedToWindow) {
            destroy()
            return
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                removeOnAttachStateChangeListener(this)
                main.post { destroy() }
            }
        })
    }

    private companion object {
        const val ASSET_BASE = "file:///android_asset/listening-ui/"
        const val BOOTSTRAP_TIMEOUT_MS = 2_500L
        const val LEVEL_INTERVAL_MS = 40L
        const val GENERATING = "generating"
        const val DOCUMENT = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <link rel="stylesheet" href="listening-ui.css">
  <style>
    html, body { margin: 0; overflow: hidden; background: transparent; }
    #muse-motion { position: absolute; inset: 0; }
    #muse-motion .muse-listening__stop { display: none; }
    #muse-motion .muse-listening__generation-fog { -webkit-backdrop-filter: none; backdrop-filter: none; }
    @supports not (width: 1cqw) {
      #muse-motion .muse-listening-ui {
        --lui-caption-size: 3.90625vw;
        --lui-stop-size: 3.125vw;
        --lui-wash-blur: 5.08vw;
        --lui-generation-blur: 7.42vw;
      }
    }
  </style>
</head>
<body>
  <div id="muse-motion"></div>
  <script src="listening-ui.js"></script>
  <script>
    if (window.ListeningUI) window.museAndroidListening =
      window.ListeningUI.mount(document.getElementById("muse-motion"));
  </script>
</body>
</html>"""
    }
}
