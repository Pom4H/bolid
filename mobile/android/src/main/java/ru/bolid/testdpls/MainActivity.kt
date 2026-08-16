package ru.bolid.testdpls

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import ru.bolid.testdpls.core.app.DplsApp
import ru.bolid.testdpls.core.domain.UiTheme

class MainActivity : ComponentActivity() {

    private val client get() = (application as DplsApplication).client

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (BLE_PERMISSIONS.all(::isPermissionGranted)) {
            ensureBluetoothEnabledAndConnect()
        } else {
            Toast.makeText(this, "Нет разрешений Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (isBluetoothEnabled()) {
            resumeOrScan()
        } else {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingExportText: String? = null
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val content = pendingExportText
        pendingExportText = null
        if (uri != null && content != null) {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(content)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by client.uiState.collectAsState()
            val dark = when (state.uiTheme) {
                UiTheme.DARK -> true
                UiTheme.LIGHT -> false
                UiTheme.SYSTEM ->
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            }
            val transparent = android.graphics.Color.TRANSPARENT
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                )
            }
            DplsApp(
                controller = client,
                shareText = { title, text ->
                    pendingExportText = text
                    createDocumentLauncher.launch(title)
                },
            )
        }

        requestRequiredPermissions()
    }

    private fun resumeOrScan() {
        if (client.uiState.value.selectedDevice == null) client.startScan()
    }

    private fun requestRequiredPermissions() {
        val permissions = REQUESTED_PERMISSIONS.filterNot(::isPermissionGranted)
        if (permissions.isEmpty()) {
            ensureBluetoothEnabledAndConnect()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun ensureBluetoothEnabledAndConnect() {
        if (!isBluetoothEnabled()) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            resumeOrScan()
        }
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        return bluetoothManager?.adapter?.isEnabled == true
    }

    companion object {
        private val BLE_PERMISSIONS = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        private val REQUESTED_PERMISSIONS = BLE_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS
    }
}
