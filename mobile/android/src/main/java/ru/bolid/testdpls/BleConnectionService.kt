package ru.bolid.testdpls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState

class BleConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy { (application as DplsApplication).client }
    private var lastError: String? = null
    private var previousMode: DplsMode? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ServiceCompat.startForeground(
            this,
            CONNECTION_NOTIFICATION_ID,
            connectionNotification("Поддержание BLE-соединения"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        scope.launch {
            client.uiState.collectLatest(::handleState)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun handleState(state: DplsUiState) {
        NotificationManagerCompat.from(this).notify(
            CONNECTION_NOTIFICATION_ID,
            connectionNotification(state.statusText),
        )

        val error = state.error ?: state.statusText.takeIf { state.phase == ConnectionPhase.ERROR }
        if (error != null && error != lastError && state.phase == ConnectionPhase.ERROR) {
            postAlert(ERROR_NOTIFICATION_ID, "Ошибка Test-DPLS", error)
        }
        lastError = if (state.phase == ConnectionPhase.ERROR) error else null

        val mode = state.state?.mode
        if (previousMode?.dangerous == true && mode == DplsMode.NORMAL) {
            postAlert(
                NORMAL_NOTIFICATION_ID,
                "Test-DPLS: режим Норма",
                "Устройство вернулось в безопасный режим.",
            )
        }
        if (mode != null) previousMode = mode
    }

    private fun connectionNotification(status: String) =
        NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_ble)
            .setContentTitle("Test-DPLS")
            .setContentText(status)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun postAlert(id: Int, title: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_ble)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                "BLE-соединение",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Поддержание защищённого соединения Test-DPLS" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "События Test-DPLS",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Ошибки и возврат устройства в режим Норма" },
        )
    }

    companion object {
        private const val CONNECTION_CHANNEL_ID = "dpls_connection"
        private const val ALERT_CHANNEL_ID = "dpls_alerts"
        private const val CONNECTION_NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = 1002
        private const val NORMAL_NOTIFICATION_ID = 1003
    }
}
