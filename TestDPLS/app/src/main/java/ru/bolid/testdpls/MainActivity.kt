package ru.bolid.testdpls

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ru.bolid.testdpls.ui.DplsScreen
import ru.bolid.testdpls.ui.MainViewModel
import ru.bolid.testdpls.ui.theme.TestDplsTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (requiredPermissions().all(::isPermissionGranted)) {
            viewModel.permissionsGranted()
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            TestDplsTheme {
                DplsScreen(
                    viewModel = viewModel,
                    onSaveCsv = { saveExport("csv", viewModel::eventLogCsv) },
                    onSaveTxt = { saveExport("txt", viewModel::eventLogTxt) },
                    onShareCsv = { shareExport("csv", "text/csv", viewModel.eventLogCsv()) },
                    onShareTxt = { shareExport("txt", "text/plain", viewModel.eventLogTxt()) },
                    onOpenAppSettings = ::openAppSettings,
                    onOpenBluetoothSettings = ::openBluetoothSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (requiredPermissions().all(::isPermissionGranted)) {
            viewModel.permissionsGranted()
        }
    }

    private fun saveExport(extension: String, content: () -> String) {
        pendingExport = content
        createDocumentLauncher.launch(viewModel.exportFileName(extension))
    }

    private fun shareExport(extension: String, mimeType: String, content: String) {
        val directory = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, viewModel.exportFileName(extension)).apply {
            writeText(content, Charsets.UTF_8)
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Поделиться журналом",
            ),
        )
    }

    private fun requestRequiredPermissions() {
        val permissions = requiredPermissions().filterNot(::isPermissionGranted)
        if (permissions.isEmpty()) {
            viewModel.permissionsGranted()
            ensureBluetoothEnabledAndConnect()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureBluetoothEnabledAndConnect() {
        if (!isBluetoothEnabled()) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            viewModel.resumeOrScan()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        return bluetoothManager?.adapter?.isEnabled == true
    }
}
