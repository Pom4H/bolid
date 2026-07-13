package com.thebutton.ble.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebutton.ble.ble.*
import kotlinx.coroutines.delay

private val Bg = Color(0xFF071923)
private val Panel = Color(0xFF0C202B)
private val Line = Color(0xFF263B46)
private val Blue = Color(0xFF2878E8)
private val Green = Color(0xFF66C53E)
private val Orange = Color(0xFFFF6A2A)
private val Muted = Color(0xFF91A2AC)

private enum class Page { MAIN, LOG, EXPORT, SETTINGS, NAME, PASSWORD, ABOUT }

private fun DplsMode.isActiveTest(): Boolean = dangerous

private fun identifyLedReady(state: DplsUiState): Boolean =
    state.identifyActive && state.identifyLedLive && state.error == null

@Composable
fun DplsScreen(viewModel: MainViewModel, onExportCsv: () -> Unit, onExportJson: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    App(
        state, viewModel::startScan, viewModel::selectDevice, viewModel::identify, viewModel::stopIdentify,
        viewModel::confirmIdentifiedDevice, viewModel::updateSetupName, viewModel::updateSetupPassword,
        viewModel::updateSetupRepeatPassword, viewModel::authenticate, viewModel::setup, viewModel::requestMode, viewModel::cancelMode,
        viewModel::confirmMode, viewModel::returnToNormal, viewModel::loadEventLog, viewModel::refreshState, viewModel::disconnect,
        onExportCsv, onExportJson, modifier,
    )
}

@Composable
private fun App(
    state: DplsUiState, scan: () -> Unit, select: (String) -> Unit, identify: (String) -> Unit,
    stopIdentify: () -> Unit, confirmDevice: () -> Unit,
    onSetupName: (String) -> Unit, onSetupPassword: (String) -> Unit, onSetupRepeat: (String) -> Unit,
    auth: (CharArray) -> Unit, setup: (String, CharArray) -> Unit,
    requestMode: (DplsMode) -> Unit, cancelMode: () -> Unit, confirmMode: () -> Unit,
    normal: () -> Unit, loadLog: () -> Unit, refreshState: () -> Unit, disconnect: () -> Unit, exportCsv: () -> Unit,
    exportTxt: () -> Unit, modifier: Modifier,
) {
    var page by remember { mutableStateOf(Page.MAIN) }
    var chosenMode by remember { mutableStateOf(DplsMode.SHORT_1) }
    var pickingTest by remember { mutableStateOf(false) }
    var identifying by remember { mutableStateOf<DiscoveredDevice?>(null) }
    val connected = state.selectedDevice != null
    val showTabs = state.authenticated && page in listOf(Page.MAIN, Page.LOG, Page.SETTINGS)
    val showConnecting = !state.authenticated && (!state.credentialsReady || !state.awaitingUserPassword)
    val showLogin = !state.authenticated && state.credentialsReady && state.awaitingUserPassword

    LaunchedEffect(state.state?.mode) {
        if (state.state?.mode?.isActiveTest() == true) pickingTest = false
    }

    LaunchedEffect(page, state.state?.revision, state.controlsEnabled) {
        if (page == Page.LOG && state.controlsEnabled && state.logProgress == null) {
            loadLog()
        }
    }

    LaunchedEffect(page, state.state?.mode?.dangerous, state.authenticated) {
        if (page == Page.MAIN && state.authenticated && state.state?.mode?.isActiveTest() == true) {
            refreshState()
        }
    }

    LaunchedEffect(state.authenticated) {
        if (state.authenticated) identifying = null
    }

    // Self-heal a stuck authenticated session: if the link drifts to a
    // non-READY phase (e.g. ERROR after a reconnect) the controls grey out with
    // no way back through the UI. Nudge a STATE_GET each second — a fresh
    // STATE_REPORT drives the phase back to READY. Cancels the moment controls
    // come back (the key flips).
    LaunchedEffect(state.authenticated, state.controlsEnabled) {
        if (state.authenticated && !state.controlsEnabled && state.state != null) {
            while (true) {
                delay(1500)
                refreshState()
            }
        }
    }

    Scaffold(modifier = modifier.background(Bg), containerColor = Bg, bottomBar = {
        if (showTabs) BottomNav(page) { page = it; pickingTest = false }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).background(Bg)) {
            when {
                identifying != null && !state.authenticated -> IdentifyScreen(
                    identifying!!,
                    state,
                    back = { stopIdentify(); disconnect(); identifying = null },
                    connect = { confirmDevice(); identifying = null },
                    startIdentify = identify,
                )
                !connected -> DevicesScreen(state, scan) { device ->
                    identifying = device
                }
                showConnecting -> ConnectingScreen(state, disconnect)
                showLogin -> LoginScreen(state, onSetupName, onSetupPassword, onSetupRepeat, auth, setup)
                pickingTest -> TestPicker(chosenMode, { chosenMode = it }, { pickingTest = false }) {
                    requestMode(chosenMode)
                    pickingTest = false
                }
                page == Page.MAIN -> OperationScreen(state, { pickingTest = true }, normal)
                page == Page.LOG -> LogScreen(state, loadLog) { page = Page.EXPORT }
                page == Page.EXPORT -> ExportScreen({ page = Page.LOG }, exportCsv, exportTxt)
                page == Page.SETTINGS -> SettingsScreen(state, { page = Page.NAME }, { page = Page.PASSWORD }, { page = Page.ABOUT }, disconnect)
                page == Page.NAME -> NameScreen(state) { page = Page.SETTINGS }
                page == Page.PASSWORD -> PasswordScreen { page = Page.SETTINGS }
                page == Page.ABOUT -> AboutScreen { page = Page.SETTINGS }
            }
        }
    }
    state.pendingMode?.let { mode -> ConfirmOverlay(mode, cancelMode) { confirmMode() } }
}

@Composable private fun ScreenTitle(title: String, back: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (back != null) Text("‹", fontSize = 40.sp, modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = back))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 42.dp))
    }
}

@Composable private fun BottomNav(page: Page, onPage: (Page) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(64.dp)
            .background(Color(0xFF091C26))
            .border(1.dp, Line),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTab("Испытание", page == Page.MAIN, icon = { TestNavIcon(it) }, onClick = { onPage(Page.MAIN) })
        NavTab("Журнал", page == Page.LOG, icon = { NavGlyphIcon("☷", it) }, onClick = { onPage(Page.LOG) })
        NavTab("Настройки", page == Page.SETTINGS, icon = { NavGlyphIcon("⚙", it) }, onClick = { onPage(Page.SETTINGS) })
    }
}

@Composable
private fun RowScope.NavTab(
    title: String,
    active: Boolean,
    icon: @Composable (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            icon(active)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            color = if (active) Blue else Muted,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable private fun NavGlyphIcon(glyph: String, active: Boolean) {
    Text(glyph, color = if (active) Blue else Muted, fontSize = 22.sp, lineHeight = 22.sp)
}

@Composable private fun TestNavIcon(active: Boolean) {
    val color = if (active) Blue else Muted
    Canvas(Modifier.size(24.dp)) {
        val strokeW = 2.4.dp.toPx()
        val cx = size.width / 2f
        val top = size.height * .08f
        val midY = size.height * .48f
        val bot = size.height * .92f
        val wing = size.width * .28f
        drawLine(color, Offset(cx, top), Offset(cx + wing, midY), strokeW, StrokeCap.Round)
        drawLine(color, Offset(cx + wing, midY), Offset(cx - wing * .15f, midY), strokeW, StrokeCap.Round)
        drawLine(color, Offset(cx - wing * .15f, midY), Offset(cx + wing * .55f, bot), strokeW, StrokeCap.Round)
    }
}

@Composable private fun DevicesScreen(state: DplsUiState, scan: () -> Unit, openDevice: (DiscoveredDevice) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Устройства рядом")
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.phase == ConnectionPhase.SCANNING) "Ищем устройства по BLE..." else "Доступные устройства", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (state.phase == ConnectionPhase.SCANNING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Blue)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 8.dp)) {
            items(state.devices, key = { it.address }) { d ->
                Row(Modifier.fillMaxWidth().heightIn(min = 67.dp).border(width = 0.5.dp, color = Line).clickable { openDevice(d) }.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▥", fontSize = 26.sp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(d.userName ?: d.advertisedName, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(d.address, color = Muted, fontSize = 11.sp, maxLines = 1) }
                    Text("${d.rssi} dBm", color = Green, fontSize = 12.sp)
                    Text("  ›", color = Muted, fontSize = 27.sp)
                }
            }
        }
        PrimaryButton(if (state.phase == ConnectionPhase.SCANNING) "Обновление..." else "↻  Обновить", scan, state.phase != ConnectionPhase.SCANNING)
    }
}

@Composable private fun IdentifyScreen(
    device: DiscoveredDevice,
    state: DplsUiState,
    back: () -> Unit,
    connect: () -> Unit,
    startIdentify: (String) -> Unit,
) {
    var seconds by remember(device.address) { mutableIntStateOf(60) }
    val ledReady = identifyLedReady(state)
    LaunchedEffect(device.address) {
        startIdentify(device.address)
    }
    LaunchedEffect(device.address, ledReady) {
        if (!ledReady) return@LaunchedEffect
        seconds = 60
        while (seconds > 0) {
            kotlinx.coroutines.delay(1_000)
            seconds--
        }
    }
    val statusText = when {
        state.error != null -> state.error
        state.phase == ConnectionPhase.PAIRING -> "Подтвердите сопряжение\nв системном диалоге Bluetooth"
        ledReady -> "Светодиод на устройстве\nмигает с частотой 1 Гц"
        state.identifyActive -> "Подключение к устройству…"
        else -> "Подключение к устройству…"
    }
    val showBluetoothIcon = state.phase == ConnectionPhase.PAIRING ||
        (state.identifyActive && !ledReady)
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Показать на объекте", back)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                if (showBluetoothIcon) BluetoothIcon() else BulbIcon()
                Text(statusText, textAlign = TextAlign.Center, fontSize = 17.sp, color = if (state.error != null) Orange else Color.White, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                when {
                    state.phase == ConnectionPhase.PAIRING -> Text("Окно открылось поверх приложения", color = Muted, textAlign = TextAlign.Center, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                    ledReady -> Text("%02d:%02d".format(seconds / 60, seconds % 60), fontSize = 28.sp)
                    state.identifyActive && state.error == null -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Blue)
                }
            }
        }
        val retryPairing = state.error != null
        val canConfirm = ledReady && !retryPairing
        PrimaryButton(
            when {
                retryPairing -> "Повторить сопряжение"
                state.phase == ConnectionPhase.PAIRING -> "Ожидаем подтверждения…"
                else -> "Это устройство"
            },
            if (retryPairing) ({ startIdentify(device.address) }) else connect,
            retryPairing || canConfirm,
        )
        SecondaryButton("Остановить", back)
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable private fun BluetoothIcon() {
    val color = Blue
    Canvas(Modifier.size(112.dp)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val top = size.height * .18f
        val mid = size.height * .50f
        val bot = size.height * .82f
        val wing = size.width * .24f
        drawLine(color, Offset(cx, top), Offset(cx, bot), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(cx, top), Offset(cx + wing, mid - size.height * .14f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(cx + wing, mid - size.height * .14f), Offset(cx - wing * .35f, mid), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(cx - wing * .35f, mid), Offset(cx + wing, mid + size.height * .14f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(cx + wing, mid + size.height * .14f), Offset(cx, bot), stroke.width, StrokeCap.Round)
    }
}

@Composable private fun BulbIcon() {
    val color = Green
    Canvas(Modifier.size(112.dp)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val center = Offset(size.width / 2, size.height * .42f)
        drawCircle(color, radius = size.width * .22f, center = center, style = stroke)
        drawLine(color, Offset(size.width * .43f, size.height * .62f), Offset(size.width * .43f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * .57f, size.height * .62f), Offset(size.width * .57f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * .43f, size.height * .76f), Offset(size.width * .57f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        listOf(-140f, -110f, -90f, -70f, -40f).forEach { angle ->
            val radians = Math.toRadians(angle.toDouble())
            val start = Offset(center.x + kotlin.math.cos(radians).toFloat() * size.width * .32f, center.y + kotlin.math.sin(radians).toFloat() * size.width * .32f)
            val end = Offset(center.x + kotlin.math.cos(radians).toFloat() * size.width * .42f, center.y + kotlin.math.sin(radians).toFloat() * size.width * .42f)
            drawLine(color, start, end, strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable private fun ConnectingScreen(state: DplsUiState, cancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Подключение", cancel)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                CircularProgressIndicator(Modifier.size(82.dp), strokeWidth = 6.dp, color = Blue)
                Spacer(Modifier.height(30.dp))
                Text(
                    "Подключение к\n${state.selectedDevice?.userName ?: state.selectedDevice?.advertisedName ?: "устройству"}...",
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(it, color = Orange, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp).fillMaxWidth()) }
            }
        }
        SecondaryButton("Отменить", cancel)
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable private fun LoginScreen(
    state: DplsUiState,
    onName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRepeat: (String) -> Unit,
    auth: (CharArray) -> Unit,
    setup: (String, CharArray) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(if (state.initialized) "Вход" else "Первичная настройка")
        Column(Modifier.weight(1f).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!state.initialized) DarkField("Имя устройства", state.setupName, onChange = onName)
            DarkField("Пароль", state.setupPassword, true, onPassword)
            if (!state.initialized) DarkField("Повторите пароль", state.setupRepeatPassword, true, onRepeat)
            state.error?.let { Text(it, color = Orange, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
        PrimaryButton(if (state.initialized) "Подключиться" else "Сохранить", {
            if (state.initialized) auth(state.setupPassword.toCharArray()) else setup(state.setupName, state.setupPassword.toCharArray())
        }, state.setupFormReady)
    }
}

@Composable private fun OperationScreen(state: DplsUiState, startTest: () -> Unit, returnNormal: () -> Unit) {
    val s = state.state
    val mode = s?.mode ?: DplsMode.NORMAL
    val testActive = mode.isActiveTest()
    val countdownSeconds = activeTestCountdownSeconds(s)
    val deviceName = state.selectedDevice?.userName ?: state.selectedDevice?.advertisedName ?: "Test-DPLS-001"
    val modeColor = if (testActive) Orange else Green
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Испытание", fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Text(deviceName, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CardBox(
            modifier = Modifier.padding(horizontal = 18.dp),
            spacing = 8.dp,
            padding = 12.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (testActive) "⚡" else "✓", color = modeColor, fontSize = 28.sp)
                Text(" ${mode.title}", color = modeColor, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = Line)
            CompactInfoRow("Напряжение ДПЛС", s?.voltageMv?.let { "%.1f В".format(it / 1000f) } ?: "—", Green)
            CompactInfoRow("Источник питания", s?.powerSource?.let { "От ${it.title}" } ?: "—", Green)
            if (testActive) {
                HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TimerCircle(countdownSeconds, size = 88.dp)
                    Text(
                        "Автовозврат\nв Норма",
                        color = Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.End,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (testActive) {
            PrimaryButton("Вернуть в Норму", returnNormal, state.controlsEnabled)
        } else {
            PrimaryButton("Провести испытание", startTest, state.controlsEnabled)
        }
    }
}

@Composable private fun TestPicker(selected: DplsMode, choose: (DplsMode) -> Unit, back: () -> Unit, apply: () -> Unit) {
    val modes = DplsMode.entries.filter { it.dangerous }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Выбор испытания", back)
        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            modes.forEach { mode ->
                Row(Modifier.fillMaxWidth().heightIn(min = 59.dp).border(.5.dp, Line).clickable { choose(mode) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mode.name.startsWith("SHORT")) "ϟ" else "╫", fontSize = 25.sp)
                    Text(mode.title, Modifier.weight(1f).padding(start = 18.dp), fontSize = 15.sp)
                    RadioButton(selected == mode, { choose(mode) }, colors = RadioButtonDefaults.colors(selectedColor = Blue))
                }
            }
        }
        PrimaryButton("Применить", apply)
    }
}

@Composable private fun ConfirmOverlay(mode: DplsMode, cancel: () -> Unit, confirm: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            ScreenTitle("Подтверждение", cancel)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("△", color = Orange, fontSize = 64.sp, lineHeight = 56.sp)
                Text("Внимание!", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Вы собираетесь выполнить\nиспытание ${mode.title}",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 17.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Это может нарушить работу\nучастка ДПЛС на время испытания.", color = Muted, textAlign = TextAlign.Center, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
            }
            PrimaryButton("Продолжить", confirm, color = Orange)
            SecondaryButton("Отмена", cancel)
        }
    }
}

@Composable private fun activeTestCountdownSeconds(state: DeviceState?): Int {
    if (state == null || !state.mode.isActiveTest()) return 0
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.revision, state.automaticReturnSeconds, state.receivedAtMillis) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val elapsed = (System.currentTimeMillis() - state.receivedAtMillis) / 1_000
    tick
    return (state.automaticReturnSeconds - elapsed).toInt().coerceAtLeast(0)
}

@Composable private fun TimerCircle(seconds: Int, size: androidx.compose.ui.unit.Dp = 120.dp) {
    val fontSize = if (size < 100.dp) 20.sp else 25.sp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            drawArc(Line, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Orange, -90f, (seconds.coerceIn(0, 300) / 300f) * 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = fontSize)
    }
}

@Composable private fun LogScreen(state: DplsUiState, load: () -> Unit, export: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Журнал")
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            if (state.eventLog.isEmpty()) {
                item {
                    Column(
                        Modifier.fillParentMaxHeight(.55f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        state.logProgress?.let {
                            CircularProgressIndicator(progress = { it.coerceAtLeast(0.05f) }, color = Blue)
                            Spacer(Modifier.height(16.dp))
                            Text("Загрузка журнала…", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                        } ?: run {
                            Text(
                                if (state.error != null) state.error else "Журнал пуст",
                                color = if (state.error != null) Orange else Muted,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                            if (state.error != null && state.controlsEnabled) {
                                Spacer(Modifier.height(16.dp))
                                SecondaryButton("Повторить", load)
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(state.eventLog, key = { index, _ -> index }) { _, e ->
                    LogRow(formatEventTime(e.timestampSeconds, state.deviceBootEpochSeconds), eventTitle(e.type, e.parameter))
                }
            }
        }
        PrimaryButton("Экспорт", export)
    }
}

@Composable private fun LogRow(time: String, title: String) { Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).border(.5.dp, Line).padding(9.dp), verticalAlignment = Alignment.CenterVertically) { Text(time, color = Muted, fontSize = 12.sp, maxLines = 1); Text(title, Modifier.weight(1f).padding(start = 16.dp), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Box(Modifier.size(9.dp).background(if (title.contains("КЗ")) Orange else if (title.contains("BLE")) Blue else Green, CircleShape)) } }

/** Device uptime mapped to phone local time (ТЗ 7.5.2 + sync with phone). */
private fun formatEventTime(uptimeSec: Long, bootEpochSec: Long?): String {
    val epochSec = (bootEpochSec ?: 0L) + uptimeSec
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochSec * 1000L }
    return "%02d:%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
        cal.get(java.util.Calendar.SECOND),
    )
}

private fun eventTitle(type: Int, parameter: Int): String = when (type) {
    1 -> "Запуск устройства"
    2 -> "BLE подключение"
    3 -> "BLE отключение"
    4 -> "Успешный вход"
    5 -> "Ошибка входа · попытка $parameter"
    6 -> "Вход заблокирован"
    7 -> "Режим: ${DplsMode.fromWire(parameter)?.title ?: "код $parameter"}"
    8 -> autoReturnTitle(parameter)
    9 -> "Идентификация начата"
    10 -> "Идентификация остановлена"
    11 -> "Пароль установлен"
    else -> "Событие $type · $parameter"
}

private fun autoReturnTitle(reason: Int): String = when (reason) {
    0 -> "Автовозврат в Норма (команда оператора)"
    1 -> "Автовозврат в Норма (таймер)"
    2 -> "Автовозврат в Норма (таймаут сессии)"
    3 -> "Автовозврат в Норма (отключение BLE)"
    4 -> "Автовозврат в Норма (низкий резерв)"
    5 -> "Автовозврат в Норма (ошибка)"
    6 -> "Автовозврат в Норма (перезапуск)"
    else -> "Автовозврат в Норма"
}

@Composable private fun ExportScreen(back: () -> Unit, csv: () -> Unit, txt: () -> Unit) {
    var csvSelected by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Экспорт журнала", back)
        Column(Modifier.weight(1f).padding(18.dp)) {
            Text("Выберите формат файла", color = Muted, fontSize = 13.sp); Spacer(Modifier.height(14.dp))
            FormatRow("CSV", csvSelected) { csvSelected = true }; Spacer(Modifier.height(14.dp)); FormatRow("TXT", !csvSelected) { csvSelected = false }
        }
        PrimaryButton("Экспортировать", if (csvSelected) csv else txt)
    }
}

@Composable private fun FormatRow(title: String, selected: Boolean, click: () -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 92.dp).border(1.dp, Line, RoundedCornerShape(5.dp)).clickable(onClick = click).padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Text("▤", fontSize = 38.sp); Text(title, Modifier.weight(1f).padding(start = 22.dp), fontSize = 17.sp, maxLines = 1); RadioButton(selected, click, colors = RadioButtonDefaults.colors(selectedColor = Blue)) } }

@Composable private fun SettingsScreen(state: DplsUiState, name: () -> Unit, password: () -> Unit, about: () -> Unit, disconnect: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Настройки")
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingRow("Имя устройства", state.selectedDevice?.userName ?: "Test-DPLS-001", Blue, name)
            SettingRow("Пароль", "••••••••", Muted, password)
            SettingRow("Автовозврат в Норма", "5 минут", Green) {}
            SettingRow("Информация об устройстве", "", Muted, about)
            SettingRow("Отключиться", "", Orange, disconnect)
        }
    }
}

@Composable private fun SettingRow(label: String, value: String, color: Color, click: () -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).border(1.dp, Line, RoundedCornerShape(4.dp)).clickable(onClick = click).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(value, color = color, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 132.dp)); Text("  ›", color = Muted, fontSize = 25.sp) } }

@Composable private fun NameScreen(state: DplsUiState, back: () -> Unit) { var value by remember { mutableStateOf(state.selectedDevice?.userName ?: "Test-DPLS-001") }; EditPage("Изменение имени", back, { DarkField("Имя устройства", value) { value = it } }) }
@Composable private fun PasswordScreen(back: () -> Unit) { var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }; EditPage("Изменение пароля", back, { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { DarkField("Текущий пароль", a, true) { a=it }; DarkField("Новый пароль", b, true) { b=it }; DarkField("Повторите пароль", c, true) { c=it } } }, b.length >= 8 && b == c) }
@Composable private fun EditPage(title: String, back: () -> Unit, content: @Composable () -> Unit, enabled: Boolean = true) { Column(Modifier.fillMaxSize()) { ScreenTitle(title, back); Box(Modifier.weight(1f).padding(18.dp)) { content() }; PrimaryButton("Сохранить", back, enabled) } }

@Composable private fun AboutScreen(back: () -> Unit) { Column(Modifier.fillMaxSize()) { ScreenTitle("О устройстве", back); Column(Modifier.padding(18.dp)) { listOf("Модель" to "Тест ДПЛС", "Версия прошивки" to "1.0.0", "Аппаратная версия" to "1.0", "Серийный номер" to "DPLS00100001").forEach { (a,b) -> InfoRow(a,b); HorizontalDivider(color=Line) } } } }

@Composable private fun DarkField(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "dpls_field:$label" },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Line),
    )
}
@Composable private fun CardBox(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 10.dp,
    padding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(6.dp)).background(Panel, RoundedCornerShape(6.dp)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable private fun CompactInfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Muted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 14.sp)
    }
}
@Composable private fun InfoRow(label: String, value: String, valueColor: Color = Color.White) { Row(Modifier.fillMaxWidth().heightIn(min=43.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), color = Muted, fontSize = 13.sp); Text(value, color = valueColor, fontSize = 14.sp) } }
@Composable private fun PrimaryButton(text: String, click: () -> Unit, enabled: Boolean = true, color: Color = Blue, outerPadding: androidx.compose.ui.unit.Dp = 18.dp) { Button(click, Modifier.fillMaxWidth().padding(horizontal = outerPadding, vertical = 8.dp).heightIn(min = 52.dp), enabled, shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha=.35f)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text(text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) } }
@Composable private fun SecondaryButton(text: String, click: () -> Unit) { OutlinedButton(click, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp).heightIn(min = 52.dp), shape=RoundedCornerShape(4.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White), border=androidx.compose.foundation.BorderStroke(1.dp, Line), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) } }
