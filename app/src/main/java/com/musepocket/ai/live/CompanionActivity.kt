package com.musepocket.ai.live

import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import com.musepocket.ai.BuildConfig
import com.musepocket.ai.LandscapeShell
import com.musepocket.ai.hideMuseSystemBars
import com.musepocket.ai.live.game.FlyingBirdScreen
import com.musepocket.ai.live.huiji.openHuiJiPanel

class CompanionActivity : ComponentActivity() {
    private val companion by lazy { ViewModelProvider(this)[CompanionViewModel::class.java] }
    private var permissionRequested = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) companion.startVoice()
        } else {
            companion.onMicPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideMuseSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by companion.ui.collectAsStateWithLifecycle()
                val page = ui.page
                BackHandler(enabled = page == CompanionPage.PREPARE_GAME || page == CompanionPage.GAME) {
                    closeGame()
                }
                LaunchedEffect(page) {
                    if (page == CompanionPage.OPEN_NOTES) {
                        openHuiJiPanel()
                        companion.onNotesOpened()
                    }
                }
                LandscapeShell {
                    if (page == CompanionPage.GAME) {
                        FlyingBirdScreen(onClose = ::closeGame)
                    } else {
                        CompanionScreen(companion, onCharacterFirstFrame = companion::onCharacterFirstFrame)
                    }
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        ensureLiveAudio()
    }

    private fun closeGame() {
        companion.closeGame()
        ensureLiveAudio()
    }

    private fun ensureLiveAudio() {
        if (companion.ui.value.page != CompanionPage.LIVE) return
        when {
            BuildConfig.DOUBAO_LIVE_APP_ID.isBlank() || BuildConfig.DOUBAO_LIVE_API_KEY.isBlank() -> companion.startVoice()
            checkSelfPermission(RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> companion.startVoice()
            !permissionRequested -> {
                permissionRequested = true
                microphonePermission.launch(RECORD_AUDIO)
            }
            else -> companion.onMicPermissionDenied()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideMuseSystemBars()
    }

    override fun onPause() {
        companion.onActivityPaused()
        super.onPause()
    }
}
