package com.slowpixels.guitartuner.ui

import android.Manifest
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import com.slowpixels.guitartuner.Hub
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The tuner: the meter and the six strings. The mic runs only while this screen is in front, and the screen stays on
 * while there's something to hear.
 */
@InitialScreen
class HomeScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    /**
     * Only while on screen. If the keep-on flag is set when a window comes back from the background, Android treats it
     * as the window's own and it can't be cleared, so the phone would stay awake on a list after the tuner had gone.
     */
    private val awake = mutableStateOf(false)
    private val shown = mutableIntStateOf(0)

    init { Hub.attach(DefaultLightAudio(sealed), lightContext) }

    override fun willShow() {
        Hub.closeLists = false
        shown.intValue++
        awake.value = true
        ask()
    }

    override fun willHide() { leave() }
    override fun onAppPause() { leave() }

    private fun leave() {
        awake.value = false
        Hub.hush()
    }

    /** Asks LightOS whether the mic is allowed, and opens it if so. The ask comes back after the permission screen too. */
    private fun ask() {
        Hub.scope.launch {
            val ok = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrDefault(false)
            Hub.micOk.value = ok
            if (ok && awake.value) Hub.listen()
        }
    }

    @Composable
    override fun Content() {
        val s by Hub.settings.collectAsState()
        val dial by Hub.ear.dial.collectAsState()
        val failed by Hub.ear.failed.collectAsState()
        val held by Hub.held.collectAsState()
        val micOk by Hub.micOk.collectAsState()
        val launcher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val mic = when {
            micOk == false -> Mic.Denied
            failed -> Mic.Failed
            micOk == null -> Mic.Asking
            else -> Mic.Allowed
        }

        // Hands are full while tuning, so the phone stays awake until nothing's been heard for a while.
        var rested by remember { mutableStateOf(false) }
        LaunchedEffect(shown.intValue) {
            while (true) {
                rested = SystemClock.uptimeMillis() - maxOf(Hub.ear.lastSound, Hub.listenedAt) > Hub.AWAKE_MS
                delay(5000)
            }
        }

        Themed {
            Box(Modifier.fillMaxSize().then(if (awake.value && !rested) Modifier.keepScreenOn() else Modifier)) {
                Column(Modifier.fillMaxSize()) {
                    LightTopBar(
                        center = LightTopBarCenter.Text(s.tuningNow.name, onClick = { navigateTo(::TuningsScreen) }),
                        rightButton = LightBarButton.Text("MENU", onClick = { navigateTo(::MenuScreen) }),
                    )
                    TunerFace(
                        dial, s.tuningNow, s.electric, held, s.a4, mic,
                        Modifier.weight(1f).fillMaxWidth(),
                        onTap = Hub::choose,
                        onHold = Hub::hear,
                        onPlate = { if (mic == Mic.Denied) launcher?.launch() },
                    )
                    LightBottomBar(
                        if (mic == Mic.Denied) listOf(LightBarButton.Text("ALLOW MICROPHONE", onClick = { launcher?.launch() }))
                        else listOf(
                            LightBarButton.Text(
                                if (s.electric) "ELECTRIC" else "ACOUSTIC",
                                contentDescription = if (s.electric) "Electric guitar. Switch to acoustic" else "Acoustic guitar. Switch to electric",
                                onClick = { Hub.setGuitar(!s.electric) },
                            ),
                            LightBarButton.Text("TUNINGS", onClick = { navigateTo(::TuningsScreen) }),
                        ),
                    )
                }
            }
        }
    }
}
