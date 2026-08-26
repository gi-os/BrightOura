package com.gios.brightoura

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.brightoura.ui.FramesScreen
import com.gios.brightoura.ui.RingViewModel
import com.gios.brightoura.ui.SetupScreen
import com.gios.brightoura.ui.TabBar
import com.gios.brightoura.ui.TodayScreen
import com.gios.brightoura.ui.theme.BrightOuraTheme
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay

/**
 * Oura ring data, read straight off the ring.
 *
 * ### Why this app exists
 *
 * The ring measures continuously and hands everything to Oura's phone app, which uploads it. On a
 * phone with no Oura app — this one — a ring is a piece of jewellery. But the ring's own BLE
 * protocol was reverse-engineered by the `open_oura` project and it turns out the ring gives up
 * almost everything on request: heartbeat intervals, HRV, skin temperature, blood oxygen, motion,
 * its own sleep staging, and step counts. No account, no network, no cloud.
 *
 * ### What this release is
 *
 * A proof, in the order the risk lives. Find a ring, look at it without changing anything, adopt
 * it, and drain its history into a log on the phone. Every frame is kept as raw bytes, named where
 * the protocol notes name it and kept anyway where they do not.
 *
 * There are deliberately no scores and no sleep summary on screen yet. Decoding an event stream
 * against somebody else's notes and then drawing a number from it is how an app tells you a
 * confident lie about your own night. The frames come first; the numbers come when they have been
 * checked against a ring.
 */
class MainActivity : ComponentActivity() {

    /** Wheel notches, for whichever list is up. */
    private val wheel = WheelBus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightReport.install(
            context = this,
            appName = "BrightOura",
            label = "oura",
            token = BuildConfig.REPORT_TOKEN,
        )
        setContent {
            BrightOuraTheme {
                Surface(Modifier.fillMaxSize()) {
                    val vm: RingViewModel = viewModel()
                    App(vm)
                    ReportOverlay()
                }
            }
        }
    }

    @Composable
    private fun App(vm: RingViewModel) {
        var tab by remember { mutableIntStateOf(0) }

        /**
         * Bluetooth's runtime permissions.
         *
         * `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` on Android 12 and up; on older builds the scan
         * needs a location permission instead, because a BLE scan is a location signal as far as
         * the platform is concerned. Asked on arrival rather than at the moment of scanning: a
         * dialog that appears on top of a scan is a scan the user cannot see the result of.
         */
        val ask = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }
        LaunchedEffect(Unit) {
            val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            val missing = wanted.any {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing) ask.launch(wanted)
        }

        // The companion picker is an `IntentSender`, which only an activity can start — and it is
        // the one pairing dialog this phone will actually draw. See ble/Companion.kt.
        val picker by vm.picker.collectAsStateWithLifecycle()
        val launchPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            // The result carries the address the system associated, and that address is the only
            // one the platform will let us bond without its own (broken) dialog — for ten minutes.
            // So it goes straight to the view model rather than being reported as a step done.
            if (result.resultCode == RESULT_OK) {
                vm.associated(result.data)
            } else {
                vm.say("The picker was closed without choosing anything.")
            }
        }
        LaunchedEffect(picker) {
            val sender = picker ?: return@LaunchedEffect
            vm.pickerShown()
            runCatching {
                launchPicker.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
            }.onFailure { vm.say("The phone would not open its own picker.") }
        }

        CompositionLocalProvider(LocalWheelBus provides wheel) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> TodayScreen(vm, onSetup = { tab = 1 })
                        1 -> SetupScreen(vm, onDone = { tab = 0 })
                        else -> FramesScreen(vm)
                    }
                }
                TabBar(tab, listOf("RING", "SET UP", "FRAMES")) { tab = it }
            }
        }
    }
}
