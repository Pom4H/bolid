package com.thebutton.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import android.content.pm.ApplicationInfo
import com.thebutton.ble.ui.DplsScreen
import com.thebutton.ble.ui.MainViewModel
import com.thebutton.ble.ui.theme.TheButtonTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (BLE_PERMISSIONS.all(::isPermissionGranted)) {
            ensureBluetoothEnabledAndConnect()
        } else {
            viewModel.permissionsDenied()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (isBluetoothEnabled()) {
            viewModel.resumeOrScan()
        } else {
            viewModel.bluetoothDisabled()
        }
    }

    private var pendingExport: (() -> String)? = null
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val content = pendingExport?.invoke()
        pendingExport = null
        if (uri != null && content != null) {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TheButtonTheme {
                DplsScreen(
                    viewModel = viewModel,
                    onExportCsv = {
                        pendingExport = viewModel::eventLogCsv
                        createDocumentLauncher.launch("test-dpls-events.csv")
                    },
                    onExportJson = {
                        pendingExport = viewModel::eventLogTxt
                        createDocumentLauncher.launch("test-dpls-events.txt")
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        requestRequiredPermissions()
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
            viewModel.resumeOrScan()
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
