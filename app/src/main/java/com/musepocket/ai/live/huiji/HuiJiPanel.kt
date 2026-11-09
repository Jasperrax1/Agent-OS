package com.musepocket.ai.live.huiji

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager.LayoutParams
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnAttach
import cn.xiaoyi.huiji.HuiJiActivity
import kotlin.math.roundToInt

internal fun Activity.openHuiJiPanel() {
    startActivity(Intent(this, HuiJiActivity::class.java))
}

class HuiJiPanelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(HuiJiPanelLayout)
    }
}

private object HuiJiPanelLayout : Application.ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: Activity) {
        if (activity !is HuiJiActivity) return
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.hidePanelSystemBars()

        // SDK 1.0.0 installs one WebView in its final Activity. Preserve that view and its client.
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val panel = content.getChildAt(0) as? WebView ?: return
        val rings = activity.assets.open("landscape/camera-body.png").use { input ->
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeStream(input, null, options)
        } ?: return
        content.removeView(panel)
        content.addView(
            HuiJiShell(activity, panel, rings),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is HuiJiActivity || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Match ComponentActivity.enableEdgeToEdge() on the Muse role screen.
        activity.window.attributes = activity.window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is HuiJiActivity) {
            activity.window.decorView.post { activity.hidePanelSystemBars() }
        }
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

private class HuiJiShell(
    private val activity: Activity,
    private val panel: WebView,
    private val rings: Bitmap,
) : ViewGroup(activity) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringClip = Path()
    private val imageBounds = RectF()
    private var unit = 1f
    private var frameLeft = 0f
    private var frameTop = 0f

    init {
        setBackgroundColor(Color.BLACK)
        panel.setBackgroundColor(Color.BLACK)
        panel.clipToOutline = true
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width / 8f)
            }
        }
        addView(panel)
        panel.doOnAttach { panel.fitSdkCard(attempts = 40) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        unit = minOf(width / 427f, height / 192f)
        frameLeft = (width - 427f * unit) / 2f
        frameTop = (height - 192f * unit) / 2f
        panel.measure(
            MeasureSpec.makeMeasureSpec((256f * unit).roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((192f * unit).roundToInt(), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cardLeft = (frameLeft + 41f * unit).roundToInt()
        val cardTop = frameTop.roundToInt()
        panel.layout(cardLeft, cardTop, cardLeft + panel.measuredWidth, cardTop + panel.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = canvas.save()
        canvas.translate(frameLeft, frameTop)
        canvas.scale(unit, unit)
        // Use the same asset and crop as LandscapeShell inside the SDK's plain Activity.
        canvas.drawRing(y = 107f, cropX = -238.5975f, cropY = -142.86125f, opacity = 255)
        canvas.drawRing(y = 10.5f, cropX = -239.7225f, cropY = -18.30625f, opacity = 230)
        canvas.restoreToCount(frame)
    }

    private fun Canvas.drawRing(y: Float, cropX: Float, cropY: Float, opacity: Int) {
        val saved = save()
        translate(329f, y)
        ringClip.rewind()
        ringClip.addOval(0f, 0f, 75f, 72.5f, Path.Direction.CW)
        clipPath(ringClip)
        imageBounds.set(cropX, cropY, cropX + 328.635f, cropY + 230.463f)
        ringPaint.alpha = opacity
        drawBitmap(rings, null, imageBounds, ringPaint)
        restoreToCount(saved)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) activity.hidePanelSystemBars()
    }
}

private fun Activity.hidePanelSystemBars() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

private fun WebView.fitSdkCard(attempts: Int) {
    if (!isAttachedToWindow) return
    evaluateJavascript(FIT_SDK_CARD) { result ->
        if (isAttachedToWindow && result != "true" && attempts > 0) {
            postDelayed({ fitSdkCard(attempts - 1) }, 75)
        }
    }
}

private val FIT_SDK_CARD = """
    (() => {
        if (location.href !== "https://appassets.androidplatform.net/panel/index.html" ||
            document.readyState === "loading") return false;
        const card = document.getElementById("panel");
        if (!card) return false;
        const fit = () => {
            const scale = Math.min(innerWidth / 256, innerHeight / 192);
            card.style.setProperty("transform", "scale(" + scale + ")", "important");
        };
        document.body.style.backgroundColor = "black";
        window.addEventListener("resize", fit);
        fit();
        return true;
    })()
""".trimIndent()
