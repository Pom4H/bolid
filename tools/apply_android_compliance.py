#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


ble_path = Path("TestDPLS/app/src/main/java/ru/bolid/testdpls/ble/BleClient.kt")
ble = ble_path.read_text(encoding="utf-8")

ble = replace_once(
    ble,
    "import android.bluetooth.BluetoothStatusCodes\n",
    "",
    "remove api33 BluetoothStatusCodes import",
)
ble = replace_once(
    ble,
    "import android.content.IntentFilter\nimport android.os.Handler\n",
    "import android.content.IntentFilter\nimport android.os.Build\nimport android.os.Handler\n",
    "add Build import",
)

ble = replace_once(
    ble,
    "    private var pairingPollRunnable: Runnable? = null\n    private var e2eModeTarget: DplsMode? = null\n",
    """    private var pairingPollRunnable: Runnable? = null

    private val rssiReader = object : Runnable {
        @SuppressLint(\"MissingPermission\")
        override fun run() {
            val current = gatt ?: return
            current.readRemoteRssi()
            if (gatt === current) handler.postDelayed(this, RSSI_READ_INTERVAL_MS)
        }
    }

    private fun startRssiMonitor() {
        handler.removeCallbacks(rssiReader)
        handler.post(rssiReader)
    }

    private fun stopRssiMonitor() {
        handler.removeCallbacks(rssiReader)
    }

    private var e2eModeTarget: DplsMode? = null
""",
    "add RSSI monitor",
)

ble = replace_once(
    ble,
    """        appContext.registerReceiver(
            bluetoothReceiver,
            filter,
            // Bond and adapter state are emitted by Android's Bluetooth
            // process, not by this application.
            Context.RECEIVER_EXPORTED,
        )
    }

    @SuppressLint(\"MissingPermission\")
    fun startScan() {
""",
    """        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress(\"DEPRECATION\")
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress(\"DEPRECATION\")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @Suppress(\"DEPRECATION\")
    @SuppressLint(\"MissingPermission\")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value)
    } else {
        descriptor.value = value
        if (gatt.writeDescriptor(descriptor)) GATT_WRITE_SUCCESS else GATT_WRITE_FAILED
    }

    @Suppress(\"DEPRECATION\")
    @SuppressLint(\"MissingPermission\")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    } else {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = value
        if (gatt.writeCharacteristic(characteristic)) GATT_WRITE_SUCCESS else GATT_WRITE_FAILED
    }

    @SuppressLint(\"MissingPermission\")
    fun startScan() {
""",
    "add receiver and GATT write compatibility",
)

ble = replace_once(
    ble,
    "intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return",
    "intent.bluetoothDeviceExtra() ?: return",
    "replace parcelable api33 call",
)

ble = replace_once(
    ble,
    """            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                when (current.device.bondState) {
""",
    """            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                startRssiMonitor()
                when (current.device.bondState) {
""",
    "start RSSI monitor on connect",
)
ble = replace_once(
    ble,
    """            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val phase = _uiState.value.phase
""",
    """            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopRssiMonitor()
                val phase = _uiState.value.phase
""",
    "stop RSSI monitor on disconnect",
)

ble = replace_once(
    ble,
    """            val result = gatt.writeDescriptor(cccd, byteArrayOf(0x03, 0x00))
            if (result != BluetoothStatusCodes.SUCCESS) fail(\"Не удалось подписаться: $result\")
""",
    """            val result = writeDescriptorCompat(gatt, cccd, byteArrayOf(0x03, 0x00))
            if (result != GATT_WRITE_SUCCESS) fail(\"Не удалось подписаться: $result\")
""",
    "replace descriptor write",
)

ble = replace_once(
    ble,
    """        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt !== this@BleClient.gatt || characteristic.uuid != TX_UUID) return
            Log.i(TAG, \"RX indication bytes=${value.size}\")
            val frame = value.copyOf()
            if (_uiState.value.logProgress != null) {
                handler.post { handleFrame(frame) }
            } else {
                handleFrame(frame)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
""",
    """        @Suppress(\"DEPRECATION\")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicValue(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicValue(gatt, characteristic, value)
        }

        private fun handleCharacteristicValue(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@BleClient.gatt || characteristic.uuid != TX_UUID) return
            Log.i(TAG, \"RX indication bytes=${value.size}\")
            val frame = value.copyOf()
            if (_uiState.value.logProgress != null) {
                handler.post { handleFrame(frame) }
            } else {
                handleFrame(frame)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (gatt !== this@BleClient.gatt || status != BluetoothGatt.GATT_SUCCESS) return
            _uiState.update {
                it.copy(
                    connectionRssi = rssi,
                    connectionRssiUpdatedAtMillis = System.currentTimeMillis(),
                )
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
""",
    "add legacy indication and active RSSI callback",
)

ble = replace_once(
    ble,
    """        val result = current.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (result != BluetoothStatusCodes.SUCCESS) {
""",
    """        val result = writeCharacteristicCompat(current, characteristic, bytes)
        if (result != GATT_WRITE_SUCCESS) {
""",
    "replace characteristic write",
)

ble = replace_once(
    ble,
    """    private fun closeCurrentGatt() {
        val current = gatt
""",
    """    private fun closeCurrentGatt() {
        stopRssiMonitor()
        val current = gatt
""",
    "stop RSSI monitor while closing",
)

ble = replace_once(
    ble,
    "        private const val SCAN_DURATION_MS = 20_000L\n",
    "        private const val SCAN_DURATION_MS = 60_000L\n        private const val RSSI_READ_INTERVAL_MS = 2_000L\n",
    "extend scan and add RSSI interval",
)
ble = replace_once(
    ble,
    "        private const val MANUFACTURER_ID = 0x0B01\n",
    "        private const val MANUFACTURER_ID = 0x0B01\n        private const val GATT_WRITE_SUCCESS = 0\n        private const val GATT_WRITE_FAILED = -1\n",
    "add compatibility status constants",
)

ble_path.write_text(ble, encoding="utf-8")

screen_path = Path("TestDPLS/app/src/main/java/ru/bolid/testdpls/ui/DplsScreen.kt")
screen = screen_path.read_text(encoding="utf-8")
screen = replace_once(
    screen,
    "import androidx.compose.foundation.layout.Column\n",
    "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.ColumnScope\n",
    "add ColumnScope import",
)
screen = replace_once(
    screen,
    "content: @Composable Column.() -> Unit,",
    "content: @Composable ColumnScope.() -> Unit,",
    "fix Screen receiver type",
)
screen = replace_once(
    screen,
    "private fun SectionCard(content: @Composable Column.() -> Unit) {",
    "private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {",
    "fix card receiver type",
)
screen = replace_once(
    screen,
    "private fun ErrorCard(message: String, actions: @Composable (() -> Unit)? = null) {",
    "private fun ErrorCard(message: String, actions: (@Composable () -> Unit)? = null) {",
    "fix nullable composable type",
)
screen_path.write_text(screen, encoding="utf-8")

print("Android compliance migration applied")
