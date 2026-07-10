package com.thebutton.ble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebutton.ble.ble.ConnectionPhase
import com.thebutton.ble.ble.DiscoveredDevice
import com.thebutton.ble.ble.DplsMode
import com.thebutton.ble.ble.DplsUiState

@Composable
fun DplsScreen(
    viewModel: MainViewModel,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DplsScreenContent(
        state = state,
        onScan = viewModel::startScan,
        onSelect = viewModel::selectDevice,
        onAuthenticate = viewModel::authenticate,
        onSetup = viewModel::setup,
        onIdentify = viewModel::identify,
        onRequestMode = viewModel::requestMode,
        onCancelMode = viewModel::cancelMode,
        onConfirmMode = viewModel::confirmMode,
        onNormal = viewModel::returnToNormal,
        onLoadLog = viewModel::loadEventLog,
        onExportCsv = onExportCsv,
        onExportJson = onExportJson,
        onDisconnect = viewModel::disconnect,
        modifier = modifier,
    )
}

@Composable
private fun DplsScreenContent(
    state: DplsUiState,
    onScan: () -> Unit,
    onSelect: (String) -> Unit,
    onAuthenticate: (CharArray) -> Unit,
    onSetup: (String, CharArray) -> Unit,
    onIdentify: (String) -> Unit,
    onRequestMode: (DplsMode) -> Unit,
    onCancelMode: () -> Unit,
    onConfirmMode: () -> Unit,
    onNormal: () -> Unit,
    onLoadLog: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    val connected = state.selectedDevice != null
    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (state.authenticated) NavigationBar {
                listOf("Управление", "Журнал").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = {}, label = { Text(title) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Header(state, onDisconnect)
            Spacer(Modifier.height(12.dp))
            when {
                !connected -> SearchScreen(state, onScan, onSelect, onIdentify)
                !state.authenticated && !state.credentialsReady -> ConnectionProgressScreen(state)
                !state.authenticated -> AuthenticationScreen(state, onAuthenticate, onSetup)
                tab == 0 -> ControlScreen(state, onRequestMode, onNormal)
                else -> LogScreen(state, onLoadLog, onExportCsv, onExportJson)
            }
        }
    }
    state.pendingMode?.let { mode -> ConfirmModeDialog(mode, onCancelMode, onConfirmMode) }
}

@Composable
private fun Header(state: DplsUiState, onDisconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Тест-ДПЛС", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(state.statusText, color = if (state.phase == ConnectionPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.selectedDevice != null) TextButton(onClick = onDisconnect) { Text("Отключить") }
    }
}

@Composable
private fun ConnectionProgressScreen(state: DplsUiState) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        if (state.phase == ConnectionPhase.ERROR) {
            Text(state.error ?: "Не удалось подключиться", color = MaterialTheme.colorScheme.error)
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun SearchScreen(state: DplsUiState, onScan: () -> Unit, onSelect: (String) -> Unit, onIdentify: (String) -> Unit) {
    Button(onClick = onScan, enabled = state.phase != ConnectionPhase.SCANNING, modifier = Modifier.fillMaxWidth()) { Text("Найти устройства") }
    if (state.phase == ConnectionPhase.SCANNING) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
        items(state.devices, key = { it.address }) { device -> DeviceCard(device, { onSelect(device.address) }, { onIdentify(device.address) }) }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onConnect: () -> Unit, onIdentify: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(device.userName ?: device.advertisedName, fontWeight = FontWeight.Bold)
            Text("ID: ${device.deviceId?.let { "%08X".format(it) } ?: "—"}  •  RSSI: ${device.rssi} dBm")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onIdentify, modifier = Modifier.weight(1f)) { Text("Показать") }
                Button(onClick = onConnect, modifier = Modifier.weight(1f)) { Text("Подключить") }
            }
        }
    }
}

@Composable
private fun AuthenticationScreen(state: DplsUiState, onAuthenticate: (CharArray) -> Unit, onSetup: (String, CharArray) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.initialized) "Защищённое подключение" else "Первичная настройка", style = MaterialTheme.typography.titleLarge)
        if (!state.initialized) OutlinedTextField(name, { name = it }, enabled = state.credentialsReady, label = { Text("Имя устройства") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, enabled = state.credentialsReady, label = { Text("Пароль (не менее 8 символов)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (!state.initialized) OutlinedTextField(repeat, { repeat = it }, enabled = state.credentialsReady, label = { Text("Повторите пароль") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                val chars = password.toCharArray()
                if (state.initialized) onAuthenticate(chars) else if (password == repeat) onSetup(name, chars)
                password = ""; repeat = ""
            },
            enabled = state.credentialsReady && password.length >= 8 &&
                (state.initialized || (name.isNotBlank() && password == repeat)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.initialized) "Войти" else "Сохранить и войти") }
        Text("Команды управления заблокированы до шифрования BLE и успешной аутентификации.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ControlScreen(state: DplsUiState, onRequestMode: (DplsMode) -> Unit, onNormal: () -> Unit) {
    val device = state.selectedDevice
    val actual = state.state
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(device?.userName ?: device?.advertisedName ?: "Устройство", fontWeight = FontWeight.Bold)
                    Text("Режим: ${actual?.mode?.title ?: "—"}", style = MaterialTheme.typography.headlineSmall)
                    Text("Напряжение ДПЛС: ${actual?.voltageMv?.let { "%.1f В".format(it / 1000f) } ?: "—"}")
                    Text("Питание: ${actual?.powerSource?.title ?: "—"}")
                    if (actual?.automaticReturnSeconds?.let { it > 0 } == true) Text("Автовозврат через %02d:%02d".format(actual.automaticReturnSeconds / 60, actual.automaticReturnSeconds % 60), color = MaterialTheme.colorScheme.error)
                    if (state.staleState) Text("Состояние может быть устаревшим: связь восстанавливается", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item { Button(onClick = onNormal, enabled = state.controlsEnabled && actual?.mode != DplsMode.NORMAL, modifier = Modifier.fillMaxWidth()) { Text("Вернуть в Норму") } }
        item { Text("Испытательные режимы", style = MaterialTheme.typography.titleMedium) }
        items(DplsMode.entries.filter { it.dangerous }.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { mode -> OutlinedButton(onClick = { onRequestMode(mode) }, enabled = state.controlsEnabled, modifier = Modifier.weight(1f)) { Text(mode.title) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LogScreen(state: DplsUiState, onLoad: () -> Unit, onCsv: () -> Unit, onJson: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onLoad, enabled = state.controlsEnabled, modifier = Modifier.weight(1f)) { Text("Загрузить") }
        OutlinedButton(onClick = onCsv, enabled = state.eventLog.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("CSV") }
        OutlinedButton(onClick = onJson, enabled = state.eventLog.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("JSON") }
    }
    state.logProgress?.let { Text("Загрузка: ${(it * 100).toInt()} %", modifier = Modifier.padding(vertical = 12.dp)) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        items(state.eventLog, key = { it.sequence }) { event ->
            Card(Modifier.fillMaxWidth()) { Text("#${event.sequence}  t=${event.timestampSeconds} с  событие ${event.type}  параметр ${event.parameter}", modifier = Modifier.padding(12.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmModeDialog(mode: DplsMode, onCancel: () -> Unit, onConfirm: () -> Unit) {
    var checked by remember(mode) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (mode == DplsMode.NORMAL) "Вернуть в Норму?" else "Запустить ${mode.title}?") },
        text = {
            Column {
                if (mode.dangerous) {
                    Text("Режим сформирует событие на КДЛ и может временно нарушить работу участка ДПЛС. Устройство автоматически вернётся в Норму не позднее чем через 5 минут.")
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { checked = it }); Text("Я понимаю последствия") }
                } else Text("Будет выполнен безопасный возврат аппаратных выходов.")
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = checked || !mode.dangerous) { Text("Выполнить") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
