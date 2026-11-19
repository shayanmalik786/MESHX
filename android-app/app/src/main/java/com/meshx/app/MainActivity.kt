package com.meshx.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.meshx.app.core.Host
import com.meshx.app.core.Need
import com.meshx.app.core.SystemPanel
import com.meshx.app.core.Tab
import com.meshx.app.model.ThemeMode
import com.meshx.app.platform.Permissions
import com.meshx.app.qr.PortraitCaptureActivity
import com.meshx.app.ui.MeshXApp
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshXTheme
import kotlin.concurrent.thread

/**
 * The one screen of the app. It shows the Compose user interface and does the few things
 * only an activity can do: permission dialogs, the QR scanner, the file picker and sharing.
 */
class MainActivity : ComponentActivity(), Host {
    private val model: MeshViewModel by viewModels()
    private var askingFor: Need? = null

    private val permissionDialog = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val need = askingFor
        askingFor = null
        if (need != null) model.app.onPermission(need, Permissions.granted(this, need))
    }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        model.app.onQrScanned(result.contents)
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readFirmware(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.app.host = this
        setContent {
            val state by model.app.state.collectAsState()
            val dark = when (state.profile.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // dark icons in the status bar on a light screen, light icons on a dark one
            SideEffect {
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = !dark
                bars.isAppearanceLightNavigationBars = !dark
            }
            MeshXTheme(state.profile.theme) {
                // safeDrawingPadding keeps the content away from the status bar, the navigation bar and the keyboard
                Box(Modifier.fillMaxSize().background(Mesh.colors.bg).safeDrawingPadding()) {
                    MeshXApp(state, model.app)
                }
            }
            BackHandler(enabled = state.sheet != null || state.stack.size > 1 || state.tab != Tab.Chats) {
                model.app.back()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        model.app.saveNow()
    }

    override fun onDestroy() {
        if (model.app.host === this) model.app.host = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Host

    override fun requestPermission(need: Need) {
        if (Permissions.granted(this, need)) {
            model.app.onPermission(need, true)
        } else {
            askingFor = need
            permissionDialog.launch(Permissions.toRequest(need))
        }
    }

    override fun scanQr() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Point the camera at a MeshX code")
            .setBeepEnabled(false)
            .setOrientationLocked(true)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
        qrScanner.launch(options)
    }

    override fun pickFirmware() {
        try {
            filePicker.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            model.app.notice("No file manager is installed on this phone")
        }
    }

    /** Reads the chosen file away from the main thread, then hands it to the app logic. */
    private fun readFirmware(uri: Uri) {
        thread(name = "meshx-read-file") {
            var name = "firmware.bin"
            var bytes: ByteArray? = null
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    // a little more than the limit is read, so a file that is too large is noticed
                    val buffer = ByteArray(MAX_FILE_BYTES + 1)
                    var total = 0
                    while (total < buffer.size) {
                        val count = input.read(buffer, total, buffer.size - total)
                        if (count < 0) break
                        total += count
                    }
                    bytes = buffer.copyOf(total)
                }
            } catch (e: Exception) {
                bytes = null
            }
            val result = bytes
            runOnUiThread { model.app.onFirmwarePicked(name, result) }
        }
    }

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: ActivityNotFoundException) {
            model.app.notice("No app can share text on this phone")
        }
    }

    override fun openPanel(panel: SystemPanel) {
        val intent = when (panel) {
            SystemPanel.Bluetooth -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SystemPanel.Location -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SystemPanel.Wifi -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SystemPanel.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
    }
}
