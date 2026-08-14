package ru.bolid.testdpls.core.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.bolid.testdpls.core.domain.*

private val Bg = Color(0xFF071923)
private val Panel = Color(0xFF0C202B)
private val Blue = Color(0xFF2878E8)
private val Green = Color(0xFF66C53E)
private val Orange = Color(0xFFFF6A2A)
private val Muted = Color(0xFF91A2AC)
private enum class Page { TEST, LOG, SETTINGS }

@Composable
fun DplsApp(
    controller: DplsController,
    shareText: (title: String, text: String) -> Unit = { _, _ -> },
) {
    val state by controller.uiState.collectAsState()
    var page by remember { mutableStateOf(Page.TEST) }
    var identify by remember { mutableStateOf<DiscoveredDevice?>(null) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = Panel)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                if (state.authenticated) NavigationBar(containerColor = Panel) {
                    NavigationBarItem(page == Page.TEST, { page = Page.TEST }, { Text("Тест") })
                    NavigationBarItem(page == Page.LOG, { page = Page.LOG }, { Text("Журнал") })
                    NavigationBarItem(page == Page.SETTINGS, { page = Page.SETTINGS }, { Text("Настройки") })
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(Bg)) {
                when {
                    identify != null && !state.authenticated -> Identify(state, identify!!, controller) { identify = null }
                    state.selectedDevice == null -> Devices(state, controller) { identify = it }
                    !state.credentialsReady || (!state.authenticated && !state.awaitingUserPassword) -> Connecting(state, controller)
                    !state.authenticated -> Login(state, controller)
                    page == Page.TEST -> TestPage(state, controller)
                    page == Page.LOG -> LogPage(state, controller, shareText)
                    else -> SettingsPage(state, controller)
                }
            }
        }
    }
}

@Composable private fun Header(text: String) {
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(20.dp))
}

@Composable private fun Devices(state: DplsUiState, c: DplsController, open: (DiscoveredDevice) -> Unit) {
    LaunchedEffect(Unit) { c.startScan() }
    Column(Modifier.fillMaxSize()) {
        Header("Устройства рядом")
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(state.devices, key = { it.address }) { d ->
                Row(Modifier.fillMaxWidth().clickable { open(d) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.userName ?: d.advertisedName, color = Color.White, fontSize = 16.sp)
                        Text(d.address, color = Muted, fontSize = 11.sp)
                    }
                    Text("${d.rssi} dBm", color = Green)
                }
                HorizontalDivider(color = Color(0xFF263B46))
            }
        }
        Button({ c.startScan() }, Modifier.fillMaxWidth().padding(16.dp), enabled = state.phase != ConnectionPhase.SCANNING) {
            Text(if (state.phase == ConnectionPhase.SCANNING) "Поиск…" else "Обновить")
        }
    }
}

@Composable private fun Identify(state: DplsUiState, device: DiscoveredDevice, c: DplsController, close: () -> Unit) {
    var seconds by remember(device.address) { mutableIntStateOf(60) }
    LaunchedEffect(device.address) { c.identify(device.address) }
    LaunchedEffect(state.identifyLedLive) {
        if (!state.identifyLedLive) return@LaunchedEffect
        while (seconds > 0) { delay(1000); seconds-- }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Header("Показать на объекте")
        Spacer(Modifier.weight(1f))
        Text(device.userName ?: device.advertisedName, color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        if (state.identifyLedLive) {
            Text("Светодиод мигает · $seconds с", color = Green)
            Spacer(Modifier.height(18.dp))
            Button({ c.confirmIdentifiedDevice(); close() }) { Text("Это устройство") }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(state.statusText, color = Muted)
        }
        state.error?.let { Text(it, color = Orange, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.weight(1f))
        TextButton({ c.stopIdentify(); c.disconnect(); close() }) { Text("Назад") }
    }
}

@Composable private fun Connecting(state: DplsUiState, c: DplsController) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text(state.statusText, color = Color.White, modifier = Modifier.padding(16.dp))
        TextButton(c::disconnect) { Text("Отмена") }
    }
}

@Composable private fun Login(state: DplsUiState, c: DplsController) {
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(state.setupName) }
    var repeat by remember { mutableStateOf("") }
    val firstSetup = !state.initialized
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Header(if (firstSetup) "Первичная настройка" else "Вход")
        if (firstSetup) OutlinedTextField(name, { name = it; c.updateSetupName(it) }, label = { Text("Имя устройства") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it; c.updateSetupPassword(it) }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        if (firstSetup) OutlinedTextField(repeat, { repeat = it; c.updateSetupRepeatPassword(it) }, label = { Text("Повторите пароль") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        state.error?.let { Text(it, color = Orange, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (firstSetup) c.setup(name, password) else c.authenticate(password) },
            enabled = password.length >= 8 && (!firstSetup || (name.isNotBlank() && password == repeat)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (firstSetup) "Сохранить и войти" else "Войти") }
        TextButton(c::disconnect, Modifier.fillMaxWidth()) { Text("Отключиться") }
    }
}

@Composable private fun TestPage(state: DplsUiState, c: DplsController) {
    val device = state.state
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Header("Испытание")
        Text(state.selectedDevice?.userName ?: state.selectedDevice?.advertisedName ?: "Test-DPLS", color = Muted)
        Spacer(Modifier.height(18.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(device?.mode?.title ?: "—", color = if (device?.mode?.dangerous == true) Orange else Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${device?.voltageMv ?: 0} mV · ${device?.powerSource?.title ?: "—"}", color = Color.White)
                if (device?.reserveLow == true) Text("Низкий резерв", color = Orange)
                if (device?.realShort == true) Text("Обнаружено реальное КЗ", color = Orange)
            }
        }
        Spacer(Modifier.height(18.dp))
        DplsMode.entries.filter { it.dangerous }.forEach { mode ->
            OutlinedButton({ c.requestMode(mode) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), enabled = state.controlsEnabled) { Text(mode.title) }
        }
        Button(c::returnToNormal, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = state.controlsEnabled, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Норма") }
        TextButton(c::refreshState, Modifier.fillMaxWidth()) { Text("Обновить состояние") }
    }
    state.pendingMode?.let { mode ->
        AlertDialog(
            onDismissRequest = c::cancelMode,
            title = { Text("Подтвердить: ${mode.title}") },
            text = { Text(mode.controllerEffect.ifBlank { mode.portHint }) },
            confirmButton = { Button(c::confirmMode) { Text("Применить") } },
            dismissButton = { TextButton(c::cancelMode) { Text("Отмена") } },
        )
    }
}

@Composable private fun LogPage(state: DplsUiState, c: DplsController, share: (String, String) -> Unit) {
    LaunchedEffect(Unit) { c.loadEventLog() }
    Column(Modifier.fillMaxSize()) {
        Header("Журнал")
        state.logProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(state.eventLog, key = { it.sequence }) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(eventTitle(e), color = Color.White)
                    Text("#${e.sequence} · +${formatUptime(e.timestampSeconds)}", color = Muted, fontSize = 11.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedButton({ share("Test-DPLS journal.csv", c.eventLogCsv()) }, Modifier.weight(1f)) { Text("CSV") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ share("Test-DPLS journal.txt", c.eventLogTxt()) }, Modifier.weight(1f)) { Text("TXT") }
        }
    }
}

@Composable private fun SettingsPage(state: DplsUiState, c: DplsController) {
    var name by remember { mutableStateOf(state.deviceInfo?.userName ?: "") }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Header("Настройки")
        OutlinedTextField(name, { name = it }, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth())
        Button({ c.setDeviceName(name) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Сохранить имя") }
        OutlinedTextField(current, { current = it }, label = { Text("Текущий пароль") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        OutlinedTextField(next, { next = it }, label = { Text("Новый пароль") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button({ c.changePassword(current, next) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = current.length >= 8 && next.length >= 8) { Text("Сменить пароль") }
        state.settingsError?.let { Text(it, color = Orange, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        state.deviceInfo?.let { info ->
            Text(info.shortId, color = Color.White)
            Text("FW ${info.firmwareVersion} · protocol ${info.protocolVersion} · HW ${info.hardwareRevision}", color = Muted)
        }
        TextButton(c::requestDeviceInfo, Modifier.fillMaxWidth()) { Text("Обновить информацию") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(c::disconnect, Modifier.fillMaxWidth()) { Text("Отключиться") }
    }
}

private fun eventTitle(e: EventRecord): String = when (e.type) {
    1 -> "Запуск устройства"; 2 -> "BLE подключение"; 3 -> "BLE отключение"; 4 -> "Успешный вход"
    5 -> "Ошибка входа · попытка ${e.parameter}"; 6 -> "Вход заблокирован"
    7 -> "Режим: ${DplsMode.fromWire(e.parameter)?.title ?: e.parameter}"; 9 -> "Идентификация начата"
    10 -> "Идентификация остановлена"; 11 -> "Пароль установлен"; else -> "Событие ${e.type} · ${e.parameter}"
}
private fun formatUptime(value: Long): String = "%02d:%02d:%02d".format(value / 3600, (value % 3600) / 60, value % 60)
