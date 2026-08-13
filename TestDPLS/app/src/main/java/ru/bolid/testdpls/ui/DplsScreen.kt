package ru.bolid.testdpls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.bolid.testdpls.BuildConfig
import ru.bolid.testdpls.ble.AndroidComplianceRules
import ru.bolid.testdpls.ble.ConnectionPhase
import ru.bolid.testdpls.ble.DeviceInfo
import ru.bolid.testdpls.ble.DiscoveredDevice
import ru.bolid.testdpls.ble.DplsMode
import ru.bolid.testdpls.ble.DplsUiState
import ru.bolid.testdpls.ble.RssiQuality
import ru.bolid.testdpls.ble.SettingsOp
import ru.bolid.testdpls.ble.dplsEventTime
import ru.bolid.testdpls.ble.dplsEventTitle
import ru.bolid.testdpls.ble.isValidDplsPassword
import ru.bolid.testdpls.ble.rssiQuality
import ru.bolid.testdpls.protocol.DplsProtocol

private object Route {
    const val MAIN = "main"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val NAME = "name"
    const val PASSWORD = "password"
    const val ABOUT = "about"
}

@Composable
fun DplsScreen(
    viewModel: MainViewModel,
    onSaveCsv: () -> Unit,
    onSaveTxt: () -> Unit,
    onShareCsv: () -> Unit,
    onShareTxt: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var identifyingAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val identifyingDevice = state.devices.firstOrNull { it.address == identifyingAddress }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            identifyingDevice != null && !state.authenticated -> IdentifyScreen(
                device = identifyingDevice,
                state = state,
                onStop = {
                    viewModel.stopIdentify()
                    viewModel.disconnect()
                    identifyingAddress = null
                },
                onConfirm = {
                    viewModel.confirmIdentifiedDevice()
                    identifyingAddress = null
                },
            )

            state.selectedDevice == null -> DevicesScreen(
                state = state,
                onStartScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onIdentify = { device ->
                    identifyingAddress = device.address
                    viewModel.identify(device.address)
                },
                onConnect = { viewModel.selectDevice(it.address) },
                onOpenAppSettings = onOpenAppSettings,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )

            !state.credentialsReady -> ConnectingScreen(state, viewModel::disconnect)

            !state.authenticated -> CredentialsScreen(
                state = state,
                onNameChanged = viewModel::updateSetupName,
                onPasswordChanged = viewModel::updateSetupPassword,
                onRepeatChanged = viewModel::updateSetupRepeatPassword,
                onAuthenticate = viewModel::authenticate,
                onSetup = viewModel::setup,
                onDisconnect = viewModel::disconnect,
            )

            else -> AuthenticatedApp(
                state = state,
                viewModel = viewModel,
                onSaveCsv = onSaveCsv,
                onSaveTxt = onSaveTxt,
                onShareCsv = onShareCsv,
                onShareTxt = onShareTxt,
            )
        }
    }

    state.pendingMode?.let { mode ->
        ConfirmModeDialog(
            mode = mode,
            onCancel = viewModel::cancelMode,
            onConfirm = viewModel::confirmMode,
        )
    }
}

@Composable
private fun DevicesScreen(
    state: DplsUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onIdentify: (DiscoveredDevice) -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    Screen(title = "Устройства Test-DPLS", scroll = false) {
        Text(state.statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (state.phase == ConnectionPhase.SCANNING) onStopScan else onStartScan,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.phase == ConnectionPhase.SCANNING) "Остановить поиск" else "Начать поиск")
            }
            OutlinedButton(onClick = onOpenBluetoothSettings) {
                Text("Bluetooth")
            }
        }
        if (state.permissionRecoveryRequired) {
            Spacer(Modifier.height(8.dp))
            ErrorCard("Для поиска нужны разрешения Bluetooth и уведомлений.") {
                TextButton(onClick = onOpenAppSettings) { Text("Открыть настройки приложения") }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.phase == ConnectionPhase.SCANNING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Поиск может продолжаться до 60 секунд")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (state.devices.isEmpty()) {
            EmptyState("Устройства пока не найдены")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.devices, key = { it.address }) { device ->
                    DeviceCard(device, onIdentify, onConnect)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    onIdentify: (DiscoveredDevice) -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
) {
    SectionCard {
        Text(device.userName ?: device.advertisedName, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(
            device.deviceId?.let { "Test-DPLS-%08X · ID %08X".format(it, it) } ?: "ID не передан",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(device.address, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RssiBars(device.rssi)
            Spacer(Modifier.width(10.dp))
            Text("${device.rssi} dBm · ${rssiQuality(device.rssi).title}")
        }
        Spacer(Modifier.height(8.dp))
        when (device.initialized) {
            false -> StatusPill("Не инициализировано", MaterialTheme.colorScheme.error)
            true -> StatusPill("Настроено", MaterialTheme.colorScheme.primary)
            null -> Text(
                "Статус настройки будет прочитан после подключения",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIdentify(device) }, modifier = Modifier.weight(1f)) {
                Text("Показать на объекте", textAlign = TextAlign.Center)
            }
            Button(onClick = { onConnect(device) }, modifier = Modifier.weight(1f)) {
                Text("Подключиться")
            }
        }
    }
}

@Composable
private fun IdentifyScreen(
    device: DiscoveredDevice,
    state: DplsUiState,
    onStop: () -> Unit,
    onConfirm: () -> Unit,
) {
    Screen(title = "Показать на объекте", back = onStop) {
        SectionCard {
            Text(device.userName ?: device.advertisedName, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Text(device.deviceId?.let { "ID %08X".format(it) } ?: device.address)
            Spacer(Modifier.height(18.dp))
            if (state.identifyLedLive) {
                Text("Идентификация активна", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(
                    "Осталось ${state.identifyRemainingSeconds.coerceAtLeast(0)} с",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("Устройство само остановит индикацию не позднее чем через 60 секунд.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(state.statusText)
                }
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onConfirm,
            enabled = state.identifyLedLive,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Это нужное устройство")
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Остановить и вернуться")
        }
    }
}

@Composable
private fun ConnectingScreen(state: DplsUiState, onDisconnect: () -> Unit) {
    Screen(title = "Подключение") {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.7f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(state.statusText, textAlign = TextAlign.Center)
            Text(
                "Тайм-аут подключения — 10 секунд",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                ErrorCard(it)
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onDisconnect) { Text("Отменить") }
        }
    }
}

@Composable
private fun CredentialsScreen(
    state: DplsUiState,
    onNameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onRepeatChanged: (String) -> Unit,
    onAuthenticate: (CharArray) -> Unit,
    onSetup: (String, CharArray) -> Unit,
    onDisconnect: () -> Unit,
) {
    var name by remember(state.selectedDevice?.address) { mutableStateOf(state.setupName) }
    var password by remember(state.selectedDevice?.address) { mutableStateOf("") }
    var repeat by remember(state.selectedDevice?.address) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val setup = !state.initialized
    val valid = isValidDplsPassword(password) && (!setup || (name.isNotBlank() && repeat == password))

    Screen(title = if (setup) "Первичная настройка" else "Вход", back = onDisconnect) {
        if (setup) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onNameChanged(it)
                },
                label = { Text("Имя устройства") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                onPasswordChanged(it)
            },
            label = if (setup) "Новый пароль" else "Пароль",
            showPassword = showPassword,
            enabled = state.passwordInputEnabled,
        )
        if (setup) {
            Spacer(Modifier.height(8.dp))
            PasswordField(
                value = repeat,
                onValueChange = {
                    repeat = it
                    onRepeatChanged(it)
                },
                label = "Повторите пароль",
                showPassword = showPassword,
                enabled = state.passwordInputEnabled,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showPassword, onCheckedChange = { showPassword = it })
            Spacer(Modifier.width(8.dp))
            Text("Показать пароль")
        }
        Text(
            "Не менее 8 символов, только латинские буквы и цифры",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        if (state.authLockoutSeconds > 0) {
            ErrorCard("Ввод заблокирован. Осталось ${state.authLockoutSeconds} с")
        }
        (state.localValidationError ?: state.error)?.let { ErrorCard(it) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (setup) onSetup(name, password.toCharArray()) else onAuthenticate(password.toCharArray())
                password = ""
                repeat = ""
            },
            enabled = valid && state.passwordInputEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (setup) "Сохранить настройку" else "Войти")
        }
    }
}

@Composable
private fun AuthenticatedApp(
    state: DplsUiState,
    viewModel: MainViewModel,
    onSaveCsv: () -> Unit,
    onSaveTxt: () -> Unit,
    onShareCsv: () -> Unit,
    onShareTxt: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Route.MAIN
    val bottomRoutes = setOf(Route.MAIN, Route.LOG, Route.SETTINGS)

    Scaffold(
        bottomBar = {
            if (route in bottomRoutes) {
                NavigationBar {
                    BottomItem("Испытание", "●", route == Route.MAIN) { navController.navigate(Route.MAIN) { launchSingleTop = true } }
                    BottomItem("Журнал", "≡", route == Route.LOG) { navController.navigate(Route.LOG) { launchSingleTop = true } }
                    BottomItem("Настройки", "⚙", route == Route.SETTINGS) { navController.navigate(Route.SETTINGS) { launchSingleTop = true } }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.MAIN,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.MAIN) {
                OperationScreen(state, viewModel::requestMode, viewModel::returnToNormal, viewModel::refreshState)
            }
            composable(Route.LOG) {
                JournalScreen(state, viewModel::loadEventLog, onSaveCsv, onSaveTxt, onShareCsv, onShareTxt)
            }
            composable(Route.SETTINGS) {
                SettingsScreen(
                    state = state,
                    onName = { navController.navigate(Route.NAME) },
                    onPassword = { navController.navigate(Route.PASSWORD) },
                    onAbout = { navController.navigate(Route.ABOUT) },
                    onDisconnect = viewModel::disconnect,
                )
            }
            composable(Route.NAME) {
                NameScreen(state, viewModel::setDeviceName, viewModel::clearSettingsOp) { navController.popBackStack() }
            }
            composable(Route.PASSWORD) {
                PasswordScreen(state, viewModel::changePassword, viewModel::clearSettingsOp) { navController.popBackStack() }
            }
            composable(Route.ABOUT) {
                AboutScreen(state, viewModel::requestDeviceInfo) { navController.popBackStack() }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomItem(
    label: String,
    symbol: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Text(symbol, fontSize = 20.sp) },
        label = { Text(label) },
    )
}

@Composable
private fun OperationScreen(
    state: DplsUiState,
    onRequestMode: (DplsMode) -> Unit,
    onNormal: () -> Unit,
    onRefresh: () -> Unit,
) {
    val device = state.state
    Screen(title = state.deviceInfo?.userName ?: state.selectedDevice?.advertisedName ?: "Test-DPLS") {
        SectionCard {
            Text("Текущий режим", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(device?.mode?.title ?: "Состояние не получено", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (state.staleState) Text("Данные устарели: связь восстанавливается", color = MaterialTheme.colorScheme.error)
            Text("Напряжения · обновление каждую секунду", color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoRow("Клемма +1", formatVoltage(device?.port1VoltageMv, device?.port1VoltageValid == true))
            InfoRow("Клемма +2", formatVoltage(device?.port2VoltageMv, device?.port2VoltageValid == true))
            InfoRow("Клемма +Т", formatVoltage(device?.portTVoltageMv, device?.portTVoltageValid == true))
            InfoRow("Резерв", formatVoltage(device?.reserveVoltageMv, device?.reserveVoltageValid == true))
            InfoRow("Питание", if (device?.powerValid == true) device.powerSource.title else "Нет данных")
            InfoRow("Состояние резерва", if (device?.reserveValid == true) if (device.reserveLow) "Низкий заряд" else "Норма" else "Нет данных")
            InfoRow("Автовозврат", device?.automaticReturnSeconds?.let(::formatDuration) ?: "—")
            InfoRow(
                "Связь",
                state.connectionRssi?.let { "$it dBm · ${rssiQuality(it).title}" } ?: "Нет данных",
            )
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Обновить состояние") }
        }
        SafetyCard()
        Spacer(Modifier.height(8.dp))
        Text("Режим испытания", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        DplsMode.entries.filter { it.dangerous }.forEach { mode ->
            OutlinedButton(
                onClick = { onRequestMode(mode) },
                enabled = state.controlsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(mode.title, fontWeight = FontWeight.SemiBold)
                    Text(mode.portHint, fontSize = 12.sp)
                    Text(mode.controllerEffect, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (device?.mode?.dangerous == true) {
            Button(
                onClick = onNormal,
                enabled = state.controlsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Вернуть в «Норма»")
            }
        }
        state.error?.let { ErrorCard(it) }
    }
}

@Composable
private fun JournalScreen(
    state: DplsUiState,
    onLoad: () -> Unit,
    onSaveCsv: () -> Unit,
    onSaveTxt: () -> Unit,
    onShareCsv: () -> Unit,
    onShareTxt: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (state.eventLog.isEmpty() && state.logProgress == null) onLoad()
    }
    Screen(title = "Журнал событий", scroll = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onLoad, enabled = state.logProgress == null, modifier = Modifier.weight(1f)) {
                Text("Загрузить")
            }
            OutlinedButton(onClick = onSaveCsv, enabled = state.eventLog.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Сохранить CSV")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSaveTxt, enabled = state.eventLog.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Сохранить TXT")
            }
            OutlinedButton(onClick = onShareCsv, enabled = state.eventLog.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Поделиться CSV")
            }
            OutlinedButton(onClick = onShareTxt, enabled = state.eventLog.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Поделиться TXT")
            }
        }
        state.logProgress?.let {
            Spacer(Modifier.height(8.dp))
            Text("Загрузка: ${(it * 100).toInt()} %")
        }
        Spacer(Modifier.height(8.dp))
        val firstSeq = state.eventLog.filter { it.type == 1 }.maxOfOrNull { it.sequence } ?: 0L
        if (state.eventLog.isEmpty()) {
            EmptyState("Журнал ещё не загружен")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.eventLog, key = { it.sequence }) { event ->
                    val time = dplsEventTime(event, firstSeq, state.deviceBootEpochSeconds)
                    SectionCard {
                        Text("#${event.sequence}", fontWeight = FontWeight.Bold)
                        Text(time.full, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(dplsEventTitle(event.type, event.parameter), fontWeight = FontWeight.SemiBold)
                        Text("Тип: ${event.type}")
                        Text("Параметр: ${event.parameter}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: DplsUiState,
    onName: () -> Unit,
    onPassword: () -> Unit,
    onAbout: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Screen(title = "Настройки") {
        SettingsButton("Имя устройства", state.deviceInfo?.userName ?: "—", onName)
        SettingsButton("Пароль", "Изменить", onPassword)
        SettingsButton("Об устройстве и приложении", state.deviceInfo?.shortId ?: "Открыть", onAbout)
        SectionCard {
            Text("Аппаратный сброс", fontWeight = FontWeight.SemiBold)
            Text(
                "Сброс пароля через приложение невозможен. Он выполняется только физической кнопкой FACTORY_RESET на устройстве.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Отключиться") }
    }
}

@Composable
private fun NameScreen(
    state: DplsUiState,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(state.deviceInfo?.userName ?: state.selectedDevice?.advertisedName.orEmpty()) }
    LaunchedEffect(Unit) { onClear() }
    Screen(title = "Имя устройства", back = onBack) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(name) },
            enabled = name.isNotBlank() && state.settingsOp != SettingsOp.IN_PROGRESS,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить") }
        SettingsResult(state)
    }
}

@Composable
private fun PasswordScreen(
    state: DplsUiState,
    onSave: (CharArray, CharArray) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onClear() }
    Screen(title = "Изменить пароль", back = onBack) {
        PasswordField(current, { current = it }, "Текущий пароль", show)
        PasswordField(next, { next = it }, "Новый пароль", show)
        PasswordField(repeat, { repeat = it }, "Повторите новый пароль", show)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = show, onCheckedChange = { show = it })
            Spacer(Modifier.width(8.dp))
            Text("Показать пароль")
        }
        Text("Новый пароль: не менее 8 символов, только латинские буквы и цифры", fontSize = 12.sp)
        Button(
            onClick = {
                onSave(current.toCharArray(), next.toCharArray())
                current = ""
                next = ""
                repeat = ""
            },
            enabled = current.isNotBlank() && next == repeat && isValidDplsPassword(next) && state.settingsOp != SettingsOp.IN_PROGRESS,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить пароль") }
        SettingsResult(state)
    }
}

@Composable
private fun AboutScreen(state: DplsUiState, onRequestInfo: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { onRequestInfo() }
    Screen(title = "Об устройстве и приложении", back = onBack) {
        SectionCard {
            Text("Приложение", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            InfoRow("Версия", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("BLE-протокол", DplsProtocol.VERSION.toString())
            Text("Открытые компоненты: AndroidX Core, Lifecycle, Activity Compose, Navigation Compose, Jetpack Compose, Kotlin. Лицензии: Apache-2.0; Kotlin — Apache-2.0.")
        }
        DeviceAbout(state.deviceInfo)
    }
}

@Composable
private fun DeviceAbout(info: DeviceInfo?) {
    SectionCard {
        Text("Устройство", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (info == null) {
            Text("Информация запрашивается…")
        } else {
            InfoRow("Имя", info.userName)
            InfoRow("ID", "%08X".format(info.deviceId))
            InfoRow("Прошивка", info.firmwareVersion)
            InfoRow("Аппаратная ревизия", info.hardwareRevision.toString())
            InfoRow("Версия протокола", info.protocolVersion.toString())
            InfoRow("ADC", if (info.adcPresent) "Есть" else "Нет")
            InfoRow("Калибровка ADC", if (info.adcCalibrated) "Выполнена" else "Не выполнена")
            InfoRow("Телеметрия +1/+2/+Т/резерв", if (info.multiVoltageReport) "Поддерживается" else "Нет")
            InfoRow("Обратная связь выходов", if (info.hardwareReadback) "Есть" else "Нет")
        }
    }
}

@Composable
private fun ConfirmModeDialog(mode: DplsMode, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Подтвердите испытание") },
        text = {
            Column {
                Text(mode.title, fontWeight = FontWeight.Bold)
                Text(mode.portHint)
                Text(mode.controllerEffect)
                Spacer(Modifier.height(10.dp))
                Text("Режим изменяет электрическое состояние ДПЛС. Убедитесь, что персонал предупреждён.")
                Text("Устройство вернётся в «Норма» через 5 минут или при потере сессии более чем на 10 секунд.")
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Включить") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

@Composable
private fun SafetyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Безопасный возврат", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Любой тестовый режим ограничен 5 минутами. При потере BLE-сессии более чем на ${AndroidComplianceRules.SESSION_LOSS_RETURN_SECONDS} секунд устройство автоматически возвращается в «Норма».",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun Screen(
    title: String,
    back: (() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (back != null) {
                TextButton(onClick = back) { Text("Назад") }
            } else {
                Spacer(Modifier.width(64.dp))
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
            )
            Spacer(Modifier.width(64.dp))
        }
        HorizontalDivider()
        val body = Modifier.fillMaxSize().padding(16.dp)
        if (scroll) {
            Column(body.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        } else {
            Column(body, verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}

@Composable
private fun ErrorCard(message: String, actions: (@Composable () -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            actions?.invoke()
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showPassword: Boolean,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsButton(title: String, value: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsResult(state: DplsUiState) {
    when (state.settingsOp) {
        SettingsOp.IN_PROGRESS -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Сохранение…")
        }
        SettingsOp.DONE -> Text("Сохранено", color = MaterialTheme.colorScheme.primary)
        SettingsOp.FAILED -> ErrorCard(state.settingsError ?: state.localValidationError ?: "Не удалось сохранить")
        SettingsOp.NONE -> state.localValidationError?.let { ErrorCard(it) }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f))) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp)
    }
}

@Composable
private fun RssiBars(rssi: Int) {
    val active = when (rssiQuality(rssi)) {
        RssiQuality.GOOD -> 4
        RssiQuality.MEDIUM -> 3
        RssiQuality.WEAK -> 1
        RssiQuality.UNKNOWN -> 0
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { index ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((6 + index * 4).dp)
                    .background(
                        if (index < active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    ),
            )
        }
    }
}

private fun formatVoltage(millivolts: Int?, valid: Boolean): String =
    if (valid && millivolts != null) "%.2f В".format(millivolts / 1000.0) else "Нет данных"

private fun formatDuration(seconds: Int): String =
    "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
