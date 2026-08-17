package ru.bolid.testdpls.core.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DeviceState
import ru.bolid.testdpls.core.domain.DiscoveredDevice
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.SettingsOp
import ru.bolid.testdpls.core.domain.UiTheme

private enum class Page { TEST, LOG, SETTINGS }

@Composable
fun DplsApp(
    controller: DplsController,
    shareText: (title: String, text: String) -> Unit = { _, _ -> },
) {
    val state by controller.uiState.collectAsState()
    var page by remember { mutableStateOf(Page.TEST) }
    var identify by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var seenLogSequence by remember { mutableLongStateOf(0L) }
    var highlightAfterSequence by remember { mutableLongStateOf(0L) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.settingsOp, state.settingsNotice) {
        if (state.settingsOp != SettingsOp.DONE) return@LaunchedEffect
        val notice = state.settingsNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Long)
    }

    val identifyDevice = identify ?: state.selectedDevice
    val showIdentify = !state.authenticated && identifyDevice != null &&
        (identify != null || state.identifyActive || state.identifyLedLive)
    val browsing = state.browsingDevices && state.authenticated
    val inWorkspace = state.authenticated && !browsing && state.phase == ConnectionPhase.READY
    val journalHead = state.eventLog.maxOfOrNull { it.sequence } ?: 0L
    LaunchedEffect(state.authenticated) {
        if (state.authenticated) return@LaunchedEffect
        seenLogSequence = 0L
        highlightAfterSequence = 0L
    }
    LaunchedEffect(journalHead) {
        if (seenLogSequence == 0L && journalHead > 0L) seenLogSequence = journalHead
    }
    LaunchedEffect(inWorkspace, state.authenticated) {
        if (!inWorkspace || !state.authenticated) return@LaunchedEffect
        controller.loadEventLog()
    }
    LaunchedEffect(journalHead, page) {
        if (page != Page.LOG || journalHead <= seenLogSequence) return@LaunchedEffect
        highlightAfterSequence = seenLogSequence
        seenLogSequence = journalHead
    }
    val newLogCount = if (page != Page.LOG && journalHead > seenLogSequence) {
        (journalHead - seenLogSequence).toInt().coerceAtMost(99)
    } else {
        0
    }
    fun openPage(next: Page) {
        if (next == Page.LOG) {
            highlightAfterSequence = seenLogSequence
            seenLogSequence = journalHead
        }
        page = next
    }
    BolidTheme(state.uiTheme) {
        val colors = LocalBolidColors.current
        val layout = rememberBolidLayout()
        CompositionLocalProvider(LocalBolidLayout provides layout) {
        PlatformSessionEffects(state)
        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets.safeDrawing.only(
                if (inWorkspace) WindowInsetsSides.Horizontal
                else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            ),
            snackbarHost = {
                SnackbarHost(
                    snackbarHostState,
                    modifier = Modifier.navigationBarsPadding(),
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = colors.ok,
                        contentColor = colors.onOk,
                        shape = Bolid.ControlShape,
                    )
                }
            },
            topBar = {
                when {
                    showIdentify -> BrandHeader(
                        onBack = {
                            controller.stopIdentify()
                            controller.disconnect()
                            identify = null
                        },
                        rssi = state.linkRssi ?: identifyDevice.rssi,
                    )
                    browsing -> BrandHeader(
                        deviceLabel(state),
                        onBack = controller::resumeSession,
                        onDisconnect = controller::disconnect,
                    )
                    state.authenticated -> BrandHeader(
                        deviceLabel(state),
                        onBack = controller::startScan,
                        onDisconnect = controller::disconnect,
                    )
                    state.selectedDevice != null -> BrandHeader(onBack = controller::disconnect)
                    else -> ListHeader("Устройства рядом")
                }
            },
            bottomBar = {
                if (inWorkspace) {
                    WorkspaceDock(page, newLogCount, colors, ::openPage)
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .background(colors.glow)
                    .padding(padding),
            ) {
                when {
                    showIdentify -> Identify(state, requireNotNull(identifyDevice), controller) { identify = null }
                    browsing || (!state.authenticated && state.selectedDevice == null) ->
                        Devices(state, controller) { device ->
                            if (state.authenticated && device.address == state.selectedDevice?.address) {
                                controller.resumeSession()
                                identify = null
                            } else {
                                identify = device
                                controller.identify(device.address)
                            }
                        }
                    !state.authenticated && (state.credentialsReady || state.awaitingUserPassword) ->
                        Login(state, controller)
                    state.phase.showsConnecting -> Connecting(state, controller)
                    !state.authenticated -> Devices(state, controller) { identify = it; controller.identify(it.address) }
                    else -> AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(110))
                        },
                        label = "workspace",
                    ) { current ->
                        when (current) {
                            Page.TEST -> TestPage(state, controller)
                            Page.LOG -> LogPage(state, controller, shareText, highlightAfterSequence)
                            Page.SETTINGS -> SettingsPage(state, controller)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun WorkspaceDock(
    page: Page,
    logBadge: Int,
    colors: BolidColors,
    onSelect: (Page) -> Unit,
) {
    val gutter = LocalBolidLayout.current.gutter
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
            .padding(horizontal = gutter, vertical = 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(22.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockTab(page == Page.TEST, BolidNavTest, "Тест", 0, colors) { onSelect(Page.TEST) }
        DockTab(page == Page.LOG, BolidNavLog, "Журнал", logBadge, colors) { onSelect(Page.LOG) }
        DockTab(page == Page.SETTINGS, BolidNavSettings, "Настройки", 0, colors) { onSelect(Page.SETTINGS) }
    }
}

@Composable
private fun RowScope.DockTab(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    badge: Int,
    colors: BolidColors,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (selected) Bolid.Blue else Color.Transparent
    val content = if (selected) Color.White else colors.muted
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .bolidFeel(pressed, enabled = true)
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White.copy(alpha = 0.22f)),
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, null, Modifier.size(20.dp), tint = content)
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-5).dp)
                        .heightIn(min = 16.dp)
                        .clip(CircleShape)
                        .background(colors.fault)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 11.sp,
                    )
                }
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun BrandHeader(
    deviceName: String? = null,
    onBack: () -> Unit,
    onDisconnect: (() -> Unit)? = null,
    rssi: Int? = null,
) {
    val colors = LocalBolidColors.current
    val layout = LocalBolidLayout.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(start = 4.dp, end = layout.gutter, top = 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(108.dp), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier
                    .bolidFeel(pressed, hovered)
                    .clip(Bolid.ControlShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(color = Bolid.Cyan.copy(alpha = 0.28f)),
                        onClick = onBack,
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BolidBack, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("Назад", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (deviceName != null) {
            Text(
                deviceName,
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            Box(Modifier.width(108.dp), contentAlignment = Alignment.CenterEnd) {
                if (onDisconnect != null) {
                    HeaderDisconnect(onDisconnect)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            if (rssi != null) HeaderRssi(rssi)
        }
    }
}

@Composable
private fun HeaderDisconnect(onClick: () -> Unit) {
    val colors = LocalBolidColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .bolidFeel(pressed, hovered)
            .clip(Bolid.ControlShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = colors.warn.copy(alpha = 0.28f)),
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Откл.", color = colors.warn, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Icon(BolidDisconnect, contentDescription = "Отключиться", tint = colors.warn, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ListHeader(title: String) {
    val colors = LocalBolidColors.current
    val layout = LocalBolidLayout.current
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = layout.gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = colors.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun deviceLabel(state: DplsUiState, fallback: DiscoveredDevice? = null): String {
    val chosen = fallback ?: state.selectedDevice
    val named = state.deviceInfo?.userName?.takeIf { it.isNotBlank() }
    return named ?: chosen?.userName ?: chosen?.advertisedName ?: "Тест-ДПЛС"
}

@Composable
private fun Devices(state: DplsUiState, c: DplsController, open: (DiscoveredDevice) -> Unit) {
    val colors = LocalBolidColors.current
    val layout = LocalBolidLayout.current
    val gutter = layout.gutter
    LaunchedEffect(Unit) { c.startScan() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.devices, key = { it.address }) { d ->
                val interaction = remember(d.address) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val hovered by interaction.collectIsHoveredAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .bolidFeel(pressed, hovered)
                        .bolidCard()
                        .clickable(
                            interactionSource = interaction,
                            indication = ripple(color = Bolid.Cyan.copy(alpha = 0.28f)),
                            onClick = { open(d) },
                        )
                        .padding(layout.cardPad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrizTDeviceIcon(
                        if (layout.compact) 40.dp else 48.dp,
                        lit = d.realShort,
                        ledColor = if (d.realShort) colors.warn else Bolid.IdentifyLed,
                    )
                    Spacer(Modifier.width(if (layout.compact) 8.dp else 12.dp))
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            d.userName ?: d.advertisedName,
                            color = colors.text,
                            fontSize = if (layout.compact) 15.sp else 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val live = state.authenticated && d.address == state.selectedDevice?.address
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                when {
                                    live -> "Подключено"
                                    else -> deviceListCaption(d)
                                },
                                color = when {
                                    live -> colors.ok
                                    d.realShort -> colors.warn
                                    d.hasLineFault -> colors.warn
                                    else -> colors.muted
                                },
                                fontSize = 12.sp,
                                fontWeight = if (live || d.hasLineFault) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (d.realShort) {
                                FaultChip("КЗ", colors.warn)
                            } else if (d.reserveLow) {
                                FaultChip("Резерв", colors.warn)
                            }
                        }
                    }
                    HeaderRssi(d.rssi, modifier = Modifier.wrapContentWidth())
                }
            }
        }
        val scanInteraction = remember { MutableInteractionSource() }
        val scanPressed by scanInteraction.collectIsPressedAsState()
        val scanHovered by scanInteraction.collectIsHoveredAsState()
        Button(
            onClick = {
                if (state.scanning && state.devices.isNotEmpty()) c.stopScan()
                else c.startScan()
            },
            interactionSource = scanInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(gutter)
                .bolidFeel(scanPressed, scanHovered)
                .heightIn(min = 56.dp),
            shape = Bolid.ControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = Bolid.Blue, contentColor = Color.White),
        ) {
            if (state.scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when {
                    state.scanning -> "Идёт поиск"
                    state.devices.isEmpty() -> "Искать устройства"
                    else -> "Обновить"
                },
                modifier = Modifier.padding(vertical = 4.dp),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private val IdentifyCaptionHeight = 96.dp
private val IdentifyActionsHeight = 88.dp

@Composable
private fun Identify(state: DplsUiState, device: DiscoveredDevice, c: DplsController, close: () -> Unit) {
    val colors = LocalBolidColors.current
    var seconds by remember(device.address) { mutableIntStateOf(60) }
    LaunchedEffect(device.address) {
        val live = c.uiState.value
        if (!live.identifyActive && !live.identifyLedLive) c.identify(device.address)
    }
    LaunchedEffect(state.identifyLedLive) {
        if (!state.identifyLedLive) return@LaunchedEffect
        seconds = 60
        while (seconds > 0) {
            delay(1_000)
            seconds--
        }
        c.stopIdentify()
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        IdentifyLedPreview(live = state.identifyLedLive, phaseOffsetMs = state.identifyLedPhaseOffsetMs)
        Spacer(Modifier.height(22.dp))
        Text(
            device.userName ?: device.advertisedName,
            color = colors.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(IdentifyCaptionHeight).padding(horizontal = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                state.identifyLedLive -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(rssiHint(state.linkRssi ?: device.rssi), color = colors.muted, fontWeight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Text("Мигает ещё $seconds с", color = colors.ok, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
                !state.error.isNullOrEmpty() -> {
                    Text(
                        state.error.orEmpty(),
                        color = colors.warn,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        minLines = 3,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                seconds == 0 -> {
                    Text("Мигание закончилось", color = colors.muted, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Bolid.Cyan)
                        Spacer(Modifier.height(12.dp))
                        Text(state.statusText, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(IdentifyActionsHeight),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    state.identifyLedLive -> IdentifyActionButton("Это устройство") {
                        c.confirmIdentifiedDevice()
                        close()
                    }
                    !state.error.isNullOrEmpty() && state.staleBond && c.canOpenBluetoothSettings() -> {
                        IdentifyActionButton("Настройки Bluetooth") { c.openBluetoothSettings() }
                        TextButton({ seconds = 60; c.identify(device.address) }) {
                            Text("Повторить", color = Bolid.Blue)
                        }
                    }
                    !state.error.isNullOrEmpty() || seconds == 0 -> IdentifyActionButton("Повторить") {
                        seconds = 60
                        c.identify(device.address)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun IdentifyActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick,
        shape = Bolid.ControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = Bolid.Blue),
    ) { Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
}

@Composable
private fun HeaderRssi(rssi: Int, modifier: Modifier = Modifier) {
    val colors = LocalBolidColors.current
    val compact = LocalBolidLayout.current.compact
    val shown by animateIntAsState(rssi, tween(180), label = "headerRssi")
    val color = rssiColor(rssi, colors)
    val bars = rssiBars(rssi)
    val barWidth = if (compact) 4.dp else 5.dp
    val gap = if (compact) 1.dp else 2.dp
    Row(
        modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(5) { index ->
                Box(
                    Modifier
                        .width(barWidth)
                        .height((6 + index * 3).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (index < bars) color else colors.line),
                )
            }
        }
        Text(
            "$shown",
            color = color,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun rssiBars(rssi: Int): Int = when {
    rssi >= -50 -> 5
    rssi >= -60 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    rssi >= -90 -> 1
    else -> 0
}

private fun rssiHint(rssi: Int): String = when {
    rssi >= -55 -> "Рядом с прибором"
    rssi >= -70 -> "Близко — продолжайте"
    rssi >= -85 -> "Идите на усиление сигнала"
    else -> "Слабый сигнал — ищите путь"
}

@Composable
private fun IdentifyLedPreview(live: Boolean, phaseOffsetMs: Long) {
    var elapsedMs by remember(live) { mutableLongStateOf(0L) }
    LaunchedEffect(live) {
        if (!live) {
            elapsedMs = 0
            return@LaunchedEffect
        }
        val startNs = withFrameNanos { it }
        while (true) {
            withFrameNanos { nowNs ->
                elapsedMs = (nowNs - startNs) / 1_000_000L
            }
        }
    }
    val on = live && DplsIdentifyLed.on(elapsedMs + phaseOffsetMs)
    val device = if (LocalBolidLayout.current.compact) 220.dp else 260.dp
    BrizTDeviceIcon(
        device,
        framed = false,
        lit = on,
        ledColor = Bolid.IdentifyLed,
    )
}

@Composable
private fun Connecting(state: DplsUiState, c: DplsController) {
    val colors = LocalBolidColors.current
    val breathe = rememberInfiniteTransition(label = "connectBreathe")
    val markScale by breathe.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "markScale",
    )
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(LocalBolidLayout.current.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.graphicsLayer { scaleX = markScale; scaleY = markScale }) {
                BolidMark(72.dp)
            }
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Bolid.Cyan)
            Text(state.statusText, color = colors.text, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Login(state: DplsUiState, c: DplsController) {
    val colors = LocalBolidColors.current
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(state.setupName) }
    var repeat by remember { mutableStateOf("") }
    val firstSetup = !state.initialized
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = LocalBolidLayout.current.gutter, vertical = 8.dp)) {
        Text(
            if (firstSetup) "Первичная настройка" else "Вход",
            color = colors.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        if (firstSetup) {
            BolidField(name, { name = it; c.updateSetupName(it) }, "Имя устройства")
        }
        BolidField(
            password,
            { password = it; c.updateSetupPassword(it) },
            "Пароль",
            password = true,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (firstSetup) {
            BolidField(
                repeat,
                { repeat = it; c.updateSetupRepeatPassword(it) },
                "Повторите пароль",
                password = true,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.error?.let { Text(it, color = colors.warn, modifier = Modifier.padding(top = 12.dp)) }
        if (password.isNotEmpty() && password.length < 8) {
            Text("Не менее 8 символов", color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(20.dp))
        val loginInteraction = remember { MutableInteractionSource() }
        val loginPressed by loginInteraction.collectIsPressedAsState()
        val loginHovered by loginInteraction.collectIsHoveredAsState()
        Button(
            onClick = { if (firstSetup) c.setup(name, password) else c.authenticate(password) },
            interactionSource = loginInteraction,
            modifier = Modifier.fillMaxWidth().bolidFeel(loginPressed, loginHovered).heightIn(min = 56.dp),
            shape = Bolid.ControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = Bolid.Blue, contentColor = Color.White),
        ) { Text(if (firstSetup) "Сохранить и войти" else "Войти", modifier = Modifier.padding(vertical = 4.dp)) }
        if (password.length < 8) {
            Text("Чтобы войти, введите пароль", color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        }
    }
}

@Composable
private fun TestPage(state: DplsUiState, c: DplsController) {
    val colors = LocalBolidColors.current
    val layout = LocalBolidLayout.current
    val device = state.state
    val adcUnsupported = state.deviceInfo?.adcPresent == false
    val dangerous = device?.mode?.dangerous == true
    val shortMode = device?.mode == DplsMode.SHORT_1 ||
        device?.mode == DplsMode.SHORT_2 ||
        device?.mode == DplsMode.SHORT_T
    val pulse = rememberInfiniteTransition(label = "testPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "padGlow",
    )
    val isolated = device?.autoIsoValid == true && device.realShort
    val reserveLow = device?.reserveValid == true && device.reserveLow
    val haptic = LocalHapticFeedback.current
    var previousAlert by remember { mutableStateOf<String?>(null) }
    val alertKey = "${device?.mode?.name}|$isolated|$reserveLow"
    LaunchedEffect(alertKey) {
        val previous = previousAlert
        previousAlert = alertKey
        if (!state.hapticsEnabled || previous == null) return@LaunchedEffect
        if (isolated || dangerous || reserveLow) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val modeColor = when {
        isolated -> colors.warn
        dangerous && shortMode -> colors.fault
        dangerous -> colors.warn
        else -> colors.ok
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = layout.gutter)
            .padding(bottom = 20.dp),
    ) {
        if (isolated) {
            ModeHero(
                title = "Автоизоляция",
                hint = "Реальное КЗ на линии. Тесты недоступны, пока замыкание не снято.",
                color = modeColor,
                autoReturnSeconds = 0,
                caption = "СОСТОЯНИЕ ЛИНИИ",
            )
        } else {
            ModeHero(
                title = device?.mode?.title ?: "Нет данных",
                hint = device?.mode?.portHint.orEmpty(),
                color = modeColor,
                autoReturnSeconds = if (dangerous) device.automaticReturnSeconds else 0,
            )
        }
        if (device?.reserveValid == true && device.reserveLow) {
            Spacer(Modifier.height(10.dp))
            StatusBanner(
                title = "Низкий резерв",
                detail = if (device.powerValid) {
                    "Питание от ${device.powerSource.title} · ${formatVolts(device.reserveVoltageMv)}"
                } else {
                    "Проверьте резервное питание"
                },
                color = colors.warn,
            )
        }
        if (state.staleState) {
            Spacer(Modifier.height(10.dp))
            StatusBanner("Нет свежих измерений", "Последний отчёт устарел", colors.warn)
        }
        if (!adcUnsupported) {
            Spacer(Modifier.height(12.dp))
            VoltagePanel(state)
        }
        ModeSection("Обрыв")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Bolid.PadGap)) {
            ModeButton(DplsMode.OPEN_T, state, c, glow, Modifier.weight(1f))
            ModeButton(DplsMode.OPEN_MAIN, state, c, glow, Modifier.weight(1f))
        }
        ModeSection("Короткое замыкание")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Bolid.PadGap)) {
            ModeButton(DplsMode.SHORT_1, state, c, glow, Modifier.weight(1f))
            ModeButton(DplsMode.SHORT_2, state, c, glow, Modifier.weight(1f))
            ModeButton(DplsMode.SHORT_T, state, c, glow, Modifier.weight(1f))
        }
        if (dangerous) {
            Spacer(Modifier.height(16.dp))
            PadCell(
                label = "Вернуть в норму",
                modifier = Modifier.fillMaxWidth(),
                enabled = state.controlsEnabled,
                active = true,
                dimmed = false,
                fill = Bolid.Blue,
                accent = Bolid.Cyan,
                content = Color.White,
                glow = glow,
                onClick = c::returnToNormal,
            )
        }
    }
    state.pendingMode?.let { mode ->
        AlertDialog(
            onDismissRequest = c::cancelMode,
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = { Text(if (mode.dangerous) "Включить ${mode.title}?" else mode.title) },
            text = {
                Text(
                    listOf(mode.portHint, mode.controllerEffect)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                )
            },
            confirmButton = {
                Button(
                    c::confirmMode,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (mode == DplsMode.SHORT_1 || mode == DplsMode.SHORT_2 || mode == DplsMode.SHORT_T) {
                            colors.fault
                        } else {
                            colors.warn
                        },
                    ),
                    shape = Bolid.ControlShape,
                ) { Text("Включить режим") }
            },
            dismissButton = { TextButton(c::cancelMode) { Text("Отмена", color = colors.muted) } },
        )
    }
}

@Composable
private fun ModeHero(
    title: String,
    hint: String,
    color: Color,
    autoReturnSeconds: Int,
    caption: String = "ТЕКУЩИЙ РЕЖИМ",
) {
    val colors = LocalBolidColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Bolid.CardShape)
            .background(color.copy(alpha = if (colors.isDark) 0.16f else 0.12f))
            .border(1.dp, color.copy(alpha = 0.38f), Bolid.CardShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                caption,
                color = color.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(title, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(hint, color = colors.text, fontSize = 14.sp)
            }
        }
        if (autoReturnSeconds > 0) {
            Column(
                Modifier
                    .clip(Bolid.ControlShape)
                    .background(colors.surface.copy(alpha = 0.72f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("АВТО", color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Text("${autoReturnSeconds} с", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusBanner(title: String, detail: String, color: Color) {
    val colors = LocalBolidColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Bolid.ControlShape)
            .background(color.copy(alpha = if (colors.isDark) 0.16f else 0.12f))
            .border(1.dp, color.copy(alpha = 0.28f), Bolid.ControlShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(detail, color = colors.text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ModeSection(title: String) {
    val colors = LocalBolidColors.current
    Text(
        title.uppercase(),
        color = colors.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun VoltagePanel(state: DplsUiState) {
    val colors = LocalBolidColors.current
    val device = state.state
    val compact = LocalBolidLayout.current.compact
    Column(
        Modifier
            .fillMaxWidth()
            .bolidCard()
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("НАПРЯЖЕНИЯ", color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (device?.powerValid == true) "питание · ${device.powerSource.title}" else "питание · не определён",
                color = if (device?.powerValid == true) colors.muted else colors.warn,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (state.deviceInfo?.multiVoltageReport != false && device != null) {
            val tiles: @Composable RowScope.() -> Unit = {
                VoltageTile("+1", device.port1VoltageMv, device.port1VoltageValid, lights(device.mode, 1), Modifier.weight(1f))
                VoltageTile("+2", device.port2VoltageMv, device.port2VoltageValid, lights(device.mode, 2), Modifier.weight(1f))
                VoltageTile("+Т", device.portTVoltageMv, device.portTVoltageValid, lights(device.mode, 3), Modifier.weight(1f))
                VoltageTile(
                    "Рез.",
                    device.reserveVoltageMv,
                    device.reserveVoltageValid,
                    device.reserveLow,
                    Modifier.weight(1f),
                    warn = device.reserveLow,
                )
            }
            if (compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Bolid.PadGap)) {
                    VoltageTile("+1", device.port1VoltageMv, device.port1VoltageValid, lights(device.mode, 1), Modifier.weight(1f))
                    VoltageTile("+2", device.port2VoltageMv, device.port2VoltageValid, lights(device.mode, 2), Modifier.weight(1f))
                }
                Spacer(Modifier.height(Bolid.PadGap))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Bolid.PadGap)) {
                    VoltageTile("+Т", device.portTVoltageMv, device.portTVoltageValid, lights(device.mode, 3), Modifier.weight(1f))
                    VoltageTile(
                        "Рез.",
                        device.reserveVoltageMv,
                        device.reserveVoltageValid,
                        device.reserveLow,
                        Modifier.weight(1f),
                        warn = device.reserveLow,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Bolid.PadGap), content = tiles)
            }
        } else {
            VoltageTile("Линия", device?.voltageMv ?: 0, device?.lineVoltageValid == true, false, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ModeButton(mode: DplsMode, state: DplsUiState, c: DplsController, pulse: Float, modifier: Modifier = Modifier) {
    val colors = LocalBolidColors.current
    val active = state.state?.mode == mode
    val short = mode == DplsMode.SHORT_1 || mode == DplsMode.SHORT_2 || mode == DplsMode.SHORT_T
    val dangerous = state.state?.mode?.dangerous == true
    val live = state.state
    val isolated = live?.autoIsoValid == true && live.realShort
    PadCell(
        label = mode.title,
        modifier = modifier,
        enabled = state.controlsEnabled && !isolated,
        active = active,
        dimmed = (dangerous && !active) || isolated,
        fill = when {
            active && short -> colors.fault
            active -> colors.warn
            else -> Color.Transparent
        },
        accent = when {
            active && short -> colors.fault
            active -> colors.warn
            else -> colors.line
        },
        content = if (active) Color.White else colors.text,
        glow = if (active) pulse else 0f,
        onClick = { c.requestMode(mode) },
    )
}

@Composable
private fun PadCell(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    active: Boolean,
    dimmed: Boolean,
    fill: Color,
    accent: Color,
    content: Color,
    glow: Float,
    onClick: () -> Unit,
) {
    val colors = LocalBolidColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fillAnim by animateColorAsState(
        targetValue = when {
            fill.alpha > 0.01f -> fill
            hovered -> colors.surfaceRaised
            else -> colors.surface
        },
        animationSpec = tween(160),
        label = "padFill",
    )
    val borderAnim by animateColorAsState(
        targetValue = when {
            active -> accent
            hovered -> if (colors.isDark) Bolid.Cyan.copy(alpha = 0.7f) else Bolid.Blue.copy(alpha = 0.55f)
            else -> accent
        },
        animationSpec = tween(160),
        label = "padBorder",
    )
    val borderWidth by animateDpAsState(if (active || hovered) 1.5.dp else 1.dp, tween(160), label = "padStroke")
    val textAnim by animateColorAsState(content, tween(160), label = "padText")
    Box(
        modifier
            .height(LocalBolidLayout.current.padHeight)
            .bolidFeel(pressed, hovered, enabled, dimmed)
            .drawBehind {
                if (glow > 0.02f) {
                    drawRoundRect(
                        color = accent.copy(alpha = glow * 0.4f),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(width = 10.dp.toPx()),
                    )
                }
            }
            .clip(Bolid.ControlShape)
            .background(fillAnim)
            .border(borderWidth, borderAnim, Bolid.ControlShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Bolid.Cyan.copy(alpha = 0.32f)),
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textAnim,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VoltageTile(
    label: String,
    millivolts: Int,
    valid: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val colors = LocalBolidColors.current
    val shown by animateIntAsState(if (valid) millivolts else 0, tween(280, easing = FastOutSlowInEasing), label = "volts")
    val accent = when {
        warn -> colors.warn
        highlighted -> if (colors.isDark) Bolid.Cyan else Bolid.Blue
        else -> Color.Transparent
    }
    val border by animateColorAsState(
        if (highlighted || warn) accent.copy(alpha = 0.7f) else colors.line.copy(alpha = 0f),
        tween(180),
        label = "voltBorder",
    )
    Column(
        modifier
            .clip(Bolid.ControlShape)
            .background(
                when {
                    warn -> colors.warn.copy(alpha = 0.10f)
                    highlighted -> accent.copy(alpha = 0.10f)
                    else -> colors.surfaceRaised
                },
            )
            .border(1.dp, if (highlighted || warn) border else colors.line.copy(alpha = 0.7f), Bolid.ControlShape)
            .padding(bottom = 12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(if (highlighted || warn) accent else Color.Transparent),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            color = if (warn) colors.warn else colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (valid) formatVoltsNumber(shown) else "—",
                color = when {
                    !valid -> colors.muted
                    warn -> colors.warn
                    highlighted -> if (colors.isDark) Bolid.Cyan else Bolid.Blue
                    else -> colors.text
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
            )
            if (valid) {
                Text(
                    " В",
                    color = colors.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

private fun lights(mode: DplsMode?, port: Int): Boolean = when (mode) {
    DplsMode.OPEN_T, DplsMode.SHORT_T -> port == 3
    DplsMode.OPEN_MAIN -> port == 1 || port == 2
    DplsMode.SHORT_1 -> port == 1
    DplsMode.SHORT_2 -> port == 2
    else -> false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogPage(state: DplsUiState, c: DplsController, share: (String, String) -> Unit, newAfterSequence: Long = 0L) {
    val colors = LocalBolidColors.current
    val gutter = LocalBolidLayout.current.gutter
    val listState = rememberLazyListState()
    var pendingShare by remember { mutableStateOf<String?>(null) }
    var userRefresh by remember { mutableStateOf(false) }
    fun emitShare(kind: String) {
        if (kind == "csv") share("Test-DPLS journal.csv", c.eventLogCsv())
        else share("Test-DPLS journal.txt", c.eventLogTxt())
    }
    fun requestShare(kind: String) {
        if (state.logHasMore || state.logProgress != null) {
            pendingShare = kind
            if (state.logHasMore) c.loadRemainingEventLog()
        } else if (state.eventLog.isNotEmpty()) {
            emitShare(kind)
        }
    }
    LaunchedEffect(Unit) { c.loadEventLog() }
    LaunchedEffect(state.logHasMore, state.logProgress) {
        if (state.logHasMore && state.logProgress == null) c.loadRemainingEventLog()
    }
    LaunchedEffect(state.logProgress) {
        if (state.logProgress == null) userRefresh = false
    }
    LaunchedEffect(state.logHasMore, state.logProgress, pendingShare, state.eventLog.size) {
        val kind = pendingShare ?: return@LaunchedEffect
        if (!state.logHasMore && state.logProgress == null && state.eventLog.isNotEmpty()) {
            emitShare(kind)
            pendingShare = null
        }
    }
    val records = state.eventLog
    val sessions = remember(records, state.deviceBootEpochSeconds, state.journalTimeAnchors) {
        journalBootSessions(
            records,
            journalBootFirstSequences(records).lastOrNull(),
            state.deviceBootEpochSeconds,
            state.journalTimeAnchors,
        )
    }
    val timeline = remember(records, sessions) { buildJournalTimeline(records, sessions) }
    var bucketOverride by remember { mutableLongStateOf(0L) }
    val strip = remember(records, timeline, bucketOverride) {
        buildJournalStrip(records, timeline, bucketSeconds = bucketOverride.takeIf { it > 0L })
    }
    var window by remember { mutableStateOf(0f..1f) }
    var scrubbing by remember { mutableStateOf(false) }
    val scrubbingLatest = rememberUpdatedState(scrubbing)
    val stripLatest = rememberUpdatedState(strip)
    val timelineLatest = rememberUpdatedState(timeline)
    fun scrollToExactIndex(exact: Float) {
        val last = (records.size - 1).coerceAtLeast(0)
        val clamped = exact.coerceIn(0f, last.toFloat())
        val index = clamped.toInt().coerceIn(0, last)
        val frac = (clamped - index).coerceIn(0f, 0.999f)
        val itemSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size
            ?: listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size
            ?: 1
        listState.requestScrollToItem(index, (frac * itemSize).toInt())
    }
    fun scrubTo(fraction: Float) {
        val chart = strip ?: return
        val playhead = fraction.coerceIn(0f, 1f)
        val minSpan = (1f / chart.barCount.coerceAtLeast(1)).coerceAtMost(1f)
        val span = (window.endInclusive - window.start).let { width ->
            if (width < minSpan / 2f) minSpan else width
        }.coerceIn(minSpan, 1f)
        val from = playhead.coerceIn(0f, (1f - span).coerceAtLeast(0f))
        window = from..(from + span).coerceAtMost(1f)
        scrollToExactIndex(journalListIndexAtTime(timeline.seconds, chart.timeAtFraction(playhead)).toFloat())
    }
    LaunchedEffect(records.size, timeline) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val start = layout.viewportStartOffset
            val end = layout.viewportEndOffset
            val visible = layout.visibleItemsInfo.filter { info ->
                info.index in records.indices && info.offset + info.size > start && info.offset < end
            }
            if (visible.isEmpty()) {
                null
            } else {
                val first = visible.first()
                val last = visible.last()
                val firstExact = first.index + (start - first.offset).toFloat() / first.size.coerceAtLeast(1)
                val lastExact = last.index + (end - last.offset).toFloat() / last.size.coerceAtLeast(1)
                firstExact to lastExact
            }
        }.collect { range ->
            val chart = stripLatest.value ?: return@collect
            if (scrubbingLatest.value) return@collect
            val times = timelineLatest.value
            window = if (range == null) {
                chart.windowForTimes(times.newest, times.newest)
            } else {
                chart.windowForTimes(times.at(range.first), times.at(range.second))
            }
        }
    }
    fun zoomPeriod(coarser: Boolean) {
        val chart = strip ?: return
        val next = nextLogBucketSeconds(chart.bucketSeconds, chart.spanSeconds, coarser)
        if (next != chart.bucketSeconds) bucketOverride = next
    }
    val loaded = records.size
    val total = state.logTotal
    val loading = state.logHasMore || state.logProgress != null
    val canExport = records.isNotEmpty() || state.logHasMore
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = gutter, end = gutter - 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    loading && total > 0 -> "Загрузка $loaded из $total"
                    loaded > 0 -> "$loaded записей"
                    else -> "Журнал"
                },
                color = colors.muted,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            LogExportLink("CSV", pendingShare == "csv", canExport && pendingShare != "txt") { requestShare("csv") }
            LogExportLink("TXT", pendingShare == "txt", canExport && pendingShare != "csv") { requestShare("txt") }
        }
        if (strip != null && strip.counts.isNotEmpty()) {
            JournalStripChart(
                strip = strip,
                windowFrom = window.start,
                windowTo = window.endInclusive,
                periodCaption = logPeriodCaption(strip.bucketSeconds),
                startLabel = records.firstOrNull()?.let { "#${it.sequence}" } ?: "",
                endLabel = records.lastOrNull()?.let { "#${it.sequence}" } ?: "",
                onScrub = ::scrubTo,
                onScrubbing = { scrubbing = it },
                onZoomPeriod = ::zoomPeriod,
                colors = colors,
                modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp),
            )
        }
        state.logProgress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
                color = Bolid.Blue,
                trackColor = colors.line,
            )
        }
        when {
            state.logProgress != null && records.isEmpty() -> {
                Text("Загрузка журнала…", color = colors.muted, modifier = Modifier.padding(gutter))
            }
            state.error != null && records.isEmpty() -> {
                Text(state.error, color = colors.warn, modifier = Modifier.padding(gutter))
                TextButton(c::loadEventLog, Modifier.padding(horizontal = 8.dp)) { Text("Повторить", color = Bolid.Blue) }
            }
            records.isEmpty() -> {
                Text("Журнал пуст", color = colors.muted, modifier = Modifier.padding(gutter))
            }
        }
        PullToRefreshBox(
            isRefreshing = userRefresh,
            onRefresh = {
                userRefresh = true
                c.refreshEventLog()
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(records, key = { _, e -> e.sequence }) { index, e ->
                    if (index > 0) {
                        val newer = journalSessionFor(records[index - 1].sequence, sessions)
                        val older = journalSessionFor(e.sequence, sessions)
                        val crossedBoot = newer != null && older != null && newer.firstSequence != older.firstSequence
                        val uptimeReboot = older == null && e.timestampSeconds > records[index - 1].timestampSeconds
                        if (crossedBoot || uptimeReboot) {
                            JournalBootDivider(
                                colors,
                                downtimeSeconds = if (older != null && newer != null) {
                                    journalDowntimeSeconds(older, newer)
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    EventLogCard(e, c.formatEventTime(e), colors, isNew = newAfterSequence > 0L && e.sequence > newAfterSequence)
                }
                if (loading && records.isNotEmpty()) {
                    item("log-more") {
                        Text(
                            "Загрузка записей…",
                            color = colors.muted,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                    }
                }
            }
            val layout = listState.layoutInfo
            val viewportStart = layout.viewportStartOffset
            val viewportEnd = layout.viewportEndOffset
            val visible = layout.visibleItemsInfo.filter { info ->
                info.index in records.indices && info.offset + info.size > viewportStart && info.offset < viewportEnd
            }
            val scrollbar = if (visible.isEmpty()) {
                journalScrollbarRange(0f, 1f, records.size)
            } else {
                val first = visible.first()
                val last = visible.last()
                val firstExact = first.index + (viewportStart - first.offset).toFloat() / first.size.coerceAtLeast(1)
                val lastExact = last.index + (viewportEnd - last.offset).toFloat() / last.size.coerceAtLeast(1)
                journalScrollbarRange(firstExact, lastExact, records.size)
            }
            if (records.size > 1) {
                JournalListScrollbar(
                    start = scrollbar.start,
                    end = scrollbar.endInclusive,
                    enabled = scrollbar.endInclusive - scrollbar.start < 0.98f,
                    onScrub = { start ->
                        scrollToExactIndex(journalIndexForScrollbar(start, records.size))
                    },
                    colors = colors,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun JournalBootDivider(colors: BolidColors, downtimeSeconds: Long? = null) {
    val caption = if (downtimeSeconds != null) {
        "простой ${journalDurationCaption(downtimeSeconds)}"
    } else {
        "предыдущее включение"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.line))
        Text(caption, color = colors.muted, fontSize = 11.sp, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(colors.line))
    }
}

@Composable
private fun JournalListScrollbar(
    start: Float,
    end: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    colors: BolidColors,
    modifier: Modifier = Modifier,
) {
    val from = minOf(start, end).coerceIn(0f, 1f)
    val to = maxOf(start, end).coerceIn(0f, 1f)
    val fromLatest = rememberUpdatedState(from)
    val toLatest = rememberUpdatedState(to)
    val onScrubLatest = rememberUpdatedState(onScrub)
    var dragging by remember { mutableStateOf(false) }
    Canvas(
        modifier
            .width(24.dp)
            .fillMaxHeight()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun fractionAt(y: Float): Float {
                    if (size.height <= 0f) return 0f
                    return (y / size.height.toFloat()).coerceIn(0f, 1f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    val from0 = fromLatest.value
                    val to0 = toLatest.value
                    val span = (to0 - from0).coerceIn(0.04f, 1f)
                    val downFrac = fractionAt(down.position.y)
                    val grab = if (downFrac in from0..to0) downFrac - from0 else span / 2f
                    fun emit(y: Float) {
                        val next = (fractionAt(y) - grab).coerceIn(0f, (1f - span).coerceAtLeast(0f))
                        onScrubLatest.value(next)
                    }
                    emit(down.position.y)
                    drag(down.id) { change ->
                        emit(change.position.y)
                        change.consume()
                    }
                    dragging = false
                }
            },
    ) {
        if (!enabled) return@Canvas
        val trackW = 3.dp.toPx()
        val thumbW = if (dragging) 5.dp.toPx() else 4.dp.toPx()
        val x = size.width - 7.dp.toPx()
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        drawRoundRect(
            color = colors.line.copy(alpha = 0.85f),
            topLeft = Offset(x - trackW / 2f, 0f),
            size = Size(trackW, size.height),
            cornerRadius = radius,
        )
        val y0 = from * size.height
        val height = (to * size.height - y0).coerceAtLeast(18.dp.toPx())
        drawRoundRect(
            color = Bolid.Cyan.copy(alpha = if (dragging) 1f else 0.88f),
            topLeft = Offset(x - thumbW / 2f, y0.coerceAtMost(size.height - height)),
            size = Size(thumbW, height),
            cornerRadius = radius,
        )
    }
}

@Composable
private fun JournalStripChart(
    strip: JournalStrip,
    windowFrom: Float,
    windowTo: Float,
    periodCaption: String,
    startLabel: String,
    endLabel: String,
    onScrub: (Float) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onZoomPeriod: (Boolean) -> Unit,
    colors: BolidColors,
    modifier: Modifier = Modifier,
) {
    val counts = strip.counts
    val alerts = strip.alerts
    val from = minOf(windowFrom, windowTo).coerceIn(0f, 1f)
    val to = maxOf(windowFrom, windowTo).coerceIn(0f, 1f)
    val allTotal = counts.sum()
    val alertTotal = alerts.sum()
    val onScrubLatest = rememberUpdatedState(onScrub)
    val onScrubbingLatest = rememberUpdatedState(onScrubbing)
    val onZoomPeriodLatest = rememberUpdatedState(onZoomPeriod)
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            HistogramLegendDot(Bolid.BlueDeep, "Все", "$allTotal")
            HistogramLegendDot(Bolid.Warn, "Тревоги", "$alertTotal")
            Spacer(Modifier.weight(1f))
            Text(periodCaption, color = colors.muted, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .pointerInput(Unit) {
                    fun fractionAt(x: Float): Float {
                        if (size.width <= 0f) return 0f
                        return (x / size.width.toFloat()).coerceIn(0f, 1f)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onScrubbingLatest.value(true)
                        var axis = 0
                        var accumulatedY = 0f
                        val slop = viewConfiguration.touchSlop
                        val zoomStep = 36.dp.toPx()
                        drag(down.id) { change ->
                            val totalX = change.position.x - down.position.x
                            val totalY = change.position.y - down.position.y
                            if (axis == 0 && (kotlin.math.abs(totalX) >= slop || kotlin.math.abs(totalY) >= slop)) {
                                axis = if (kotlin.math.abs(totalX) > kotlin.math.abs(totalY)) 1 else 2
                                if (axis == 1) {
                                    onScrubLatest.value(fractionAt(change.position.x))
                                } else {
                                    onZoomPeriodLatest.value(totalY < 0f)
                                    accumulatedY = 0f
                                }
                            }
                            when (axis) {
                                1 -> {
                                    onScrubLatest.value(fractionAt(change.position.x))
                                    change.consume()
                                }
                                2 -> {
                                    accumulatedY += change.position.y - change.previousPosition.y
                                    while (accumulatedY <= -zoomStep) {
                                        accumulatedY += zoomStep
                                        onZoomPeriodLatest.value(true)
                                    }
                                    while (accumulatedY >= zoomStep) {
                                        accumulatedY -= zoomStep
                                        onZoomPeriodLatest.value(false)
                                    }
                                    change.consume()
                                }
                            }
                        }
                        if (axis == 0) onScrubLatest.value(fractionAt(down.position.x))
                        onScrubbingLatest.value(false)
                    }
                },
        ) {
            if (counts.isEmpty()) return@Canvas
            val plotTop = 2.dp.toPx()
            val plotBottom = size.height - 2.dp.toPx()
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val n = counts.size
            val slot = size.width / n
            val gap = 1.5.dp.toPx().coerceAtMost(slot * 0.28f)
            val barWidth = (slot - gap).coerceAtLeast(1.dp.toPx())
            val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
            val minBar = 3.dp.toPx()
            val radius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
            val x0 = from * size.width
            val x1 = to * size.width
            drawRect(
                color = Bolid.Cyan.copy(alpha = 0.12f),
                topLeft = Offset(x0, plotTop),
                size = Size((x1 - x0).coerceAtLeast(2.dp.toPx()), plotHeight),
            )
            counts.forEachIndexed { index, count ->
                if (count <= 0) return@forEachIndexed
                val alert = alerts.getOrElse(index) { 0 }
                val x = index * slot + (slot - barWidth) / 2f
                val barLeft = index / n.toFloat()
                val barRight = (index + 1) / n.toFloat()
                val selected = barRight > from && barLeft < to
                val height = (plotHeight * count / maxCount).coerceAtLeast(minBar)
                val color = when {
                    selected && alert > 0 -> Bolid.Warn
                    selected -> Bolid.BlueDeep
                    alert > 0 -> Bolid.Warn.copy(alpha = 0.55f)
                    else -> Bolid.BlueDeep.copy(alpha = 0.38f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, plotBottom - height),
                    size = Size(barWidth, height),
                    cornerRadius = radius,
                )
                if (alert > 0 && count > alert) {
                    val alertHeight = (plotHeight * alert / maxCount).coerceAtLeast(minBar).coerceAtMost(height)
                    drawRoundRect(
                        color = if (selected) Bolid.Warn else Bolid.Warn.copy(alpha = 0.85f),
                        topLeft = Offset(x, plotBottom - alertHeight),
                        size = Size(barWidth, alertHeight),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                startLabel.ifEmpty { "—" },
                color = colors.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                endLabel.ifEmpty { "—" },
                color = colors.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HistogramLegendDot(color: Color, label: String, value: String) {
    val colors = LocalBolidColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = colors.muted, fontSize = 13.sp)
        Text(value, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LogExportLink(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalBolidColors.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            if (busy) "…" else label,
            color = if (enabled) Bolid.Blue else colors.muted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SettingsPage(state: DplsUiState, c: DplsController) {
    val colors = LocalBolidColors.current
    val layout = LocalBolidLayout.current
    var name by remember { mutableStateOf(state.deviceInfo?.userName ?: "") }
    var nameOpen by remember { mutableStateOf(false) }
    var passwordOpen by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val saving = state.settingsOp == SettingsOp.IN_PROGRESS
    val nameNotice = state.settingsNotice?.takeIf { it.startsWith("Имя") }
    val passwordNotice = state.settingsNotice?.takeIf { it.startsWith("Пароль") }
    val storedName = state.deviceInfo?.userName.orEmpty()
    val passwordReady = current.length >= 8 && next.length >= 8 && next == repeat
    val passwordHint = when {
        next.isNotEmpty() && repeat.isNotEmpty() && next != repeat -> "Новый пароль и повтор не совпадают" to colors.warn
        (current.isNotEmpty() && current.length < 8) || (next.isNotEmpty() && next.length < 8) ->
            "Не менее 8 символов" to colors.muted
        else -> null
    }
    LaunchedEffect(passwordNotice) {
        if (passwordNotice == null) return@LaunchedEffect
        passwordOpen = false
        current = ""
        next = ""
        repeat = ""
    }
    LaunchedEffect(state.deviceInfo?.userName, nameNotice) {
        val stored = state.deviceInfo?.userName ?: return@LaunchedEffect
        if (nameNotice != null) {
            name = stored
            nameOpen = false
        } else if (!nameOpen) {
            name = stored
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
    ) {
        Column(
            Modifier.padding(horizontal = layout.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard("О приборе") {
                DeviceAbout(state)
            }
            Button(
                {
                    name = storedName
                    nameOpen = true
                },
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !saving,
                shape = Bolid.ControlShape,
                colors = ButtonDefaults.buttonColors(containerColor = Bolid.Blue),
            ) { Text("Изменить имя") }
            Button(
                { passwordOpen = true },
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !saving,
                shape = Bolid.ControlShape,
                colors = ButtonDefaults.buttonColors(containerColor = Bolid.Blue),
            ) { Text("Сменить пароль") }
            SettingsCard("Приложение") {
                SettingsToggle(
                    title = "Не гасить экран",
                    subtitle = "Пока приложение открыто",
                    checked = state.keepScreenOn,
                    onToggle = c::setKeepScreenOn,
                )
                SettingsToggle(
                    title = "Вибрация",
                    subtitle = "При КЗ, обрыве и низком резерве",
                    checked = state.hapticsEnabled,
                    onToggle = c::setHapticsEnabled,
                )
                if (state.savedCredentials) {
                    TextButton(
                        c::forgetSavedPassword,
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text("Забыть сохранённый пароль", color = colors.warn, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        "Пароль не сохранён на этом телефоне",
                        color = colors.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (c.canOpenBluetoothSettings()) {
                    TextButton(c::openBluetoothSettings, Modifier.fillMaxWidth()) {
                        Text("Настройки Bluetooth", color = Bolid.Blue, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            SettingsCard("Тема") {
                ThemePicker(state.uiTheme, c::setUiTheme)
            }
        }
    }
    if (nameOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!saving) {
                    nameOpen = false
                    name = storedName
                    c.clearSettingsOp()
                }
            },
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = { Text("Изменить имя") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BolidField(
                        name,
                        {
                            name = it
                            if (nameNotice != null || state.settingsError != null) c.clearSettingsOp()
                        },
                        "Имя",
                    )
                    state.settingsError?.takeIf { nameOpen }?.let { Text(it, color = colors.warn, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(
                    { c.setDeviceName(name) },
                    enabled = !saving && name.trim().isNotEmpty() && name.trim() != storedName.trim(),
                    shape = Bolid.ControlShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Bolid.Blue),
                ) { Text(if (saving) "…" else "Сохранить") }
            },
            dismissButton = {
                TextButton(
                    {
                        if (!saving) {
                            nameOpen = false
                            name = storedName
                            c.clearSettingsOp()
                        }
                    },
                ) { Text("Отмена", color = colors.muted) }
            },
        )
    }
    if (passwordOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!saving) {
                    passwordOpen = false
                    c.clearSettingsOp()
                }
            },
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = { Text("Сменить пароль") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BolidField(current, { current = it; if (state.settingsError != null) c.clearSettingsOp() }, "Текущий", password = true)
                    BolidField(next, { next = it }, "Новый", password = true)
                    BolidField(repeat, { repeat = it }, "Повтор", password = true)
                    passwordHint?.let { (text, color) ->
                        Text(text, color = color, fontSize = 12.sp)
                    }
                    if (passwordOpen) {
                        state.settingsError?.let { Text(it, color = colors.warn, fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = {
                Button(
                    { c.changePassword(current, next) },
                    enabled = !saving && passwordReady,
                    shape = Bolid.ControlShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Bolid.Blue),
                ) { Text(if (saving) "…" else "Сменить") }
            },
            dismissButton = {
                TextButton(
                    {
                        if (!saving) {
                            passwordOpen = false
                            c.clearSettingsOp()
                        }
                    },
                ) { Text("Отмена", color = colors.muted) }
            },
        )
    }
}

@Composable
private fun DeviceAbout(state: DplsUiState) {
    val colors = LocalBolidColors.current
    val layout = LocalBolidLayout.current
    val info = state.deviceInfo
    val live = state.state
    val photo = if (layout.compact) 96.dp else 120.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrizTDeviceIcon(
            photo,
            framed = false,
            lit = true,
            ledColor = deviceLedColor(live, colors),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                deviceLabel(state),
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            info?.let {
                Text(
                    it.shortId,
                    color = colors.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } ?: Text("Нет данных", color = colors.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    Spacer(Modifier.height(10.dp))
    if (info != null) {
        SettingsFact("Прошивка", info.firmwareVersion)
        SettingsFact("Источник", if (info.hostSim) "хост-стенд" else "плата")
        SettingsFact("Протокол", "${info.protocolVersion}")
        SettingsFact("Ревизия", "HW ${info.hardwareRevision}")
        SettingsFact(
            "АЦП",
            when {
                !info.adcPresent -> "нет"
                info.adcCalibrated -> "калиброван"
                else -> "не калиброван"
            },
            valueColor = if (info.adcPresent && !info.adcCalibrated) colors.warn else colors.text,
        )
    }
    live?.let {
        SettingsFact("Аптайм", formatUptime(it.uptimeSeconds))
        if (it.powerValid) {
            SettingsFact(
                "Питание",
                buildString {
                    append(it.powerSource.title)
                    if (it.reserveVoltageValid) append(" · ${formatVolts(it.reserveVoltageMv)}")
                },
                valueColor = if (it.reserveValid && it.reserveLow) colors.warn else colors.text,
            )
        }
        SettingsFact(
            "Линия",
            when {
                it.autoIsoValid && it.realShort -> "автоизоляция КЗ"
                state.staleState -> "нет свежих данных"
                else -> it.mode.title
            },
            valueColor = when {
                it.autoIsoValid && it.realShort -> colors.warn
                it.mode.dangerous -> colors.fault
                else -> colors.text
            },
        )
    }
    val rssi = state.linkRssi ?: state.selectedDevice?.rssi
    if (rssi != null) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Сигнал", color = colors.muted, fontSize = 13.sp, modifier = Modifier.width(88.dp), maxLines = 1, softWrap = false)
            HeaderRssi(rssi)
        }
    }
    state.selectedDevice?.address?.let { address ->
        SettingsFact("Адрес", address)
    }
}

@Composable
private fun SettingsFact(label: String, value: String, valueColor: Color? = null) {
    val colors = LocalBolidColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.muted, fontSize = 13.sp, modifier = Modifier.width(88.dp), maxLines = 1, softWrap = false)
        Text(
            value,
            color = valueColor ?: colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalBolidColors.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Bolid.ControlShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Bolid.Cyan.copy(alpha = 0.22f)),
                onClick = { onToggle(!checked) },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Bolid.Blue,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalBolidColors.current
    Column(Modifier.fillMaxWidth().bolidCard().padding(16.dp)) {
        Text(
            title.uppercase(),
            color = if (colors.isDark) Bolid.Cyan else Bolid.Blue,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ThemePicker(current: UiTheme, onSelect: (UiTheme) -> Unit) {
    val colors = LocalBolidColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Bolid.ControlShape)
            .background(colors.surfaceRaised)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UiTheme.entries.forEach { theme ->
            val selected = theme == current
            val interaction = remember(theme) { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .weight(1f)
                    .bolidFeel(pressed, hovered)
                    .clip(Bolid.ControlShape)
                    .background(if (selected) Bolid.Blue else Color.Transparent)
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(color = Color.White.copy(alpha = 0.25f)),
                        onClick = { onSelect(theme) },
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    theme.title,
                    color = if (selected) Color.White else colors.text,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun BolidField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    val colors = LocalBolidColors.current
    val accent = if (colors.isDark) Bolid.Cyan else Bolid.Blue
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                Text(
                    if (visible) "Скрыть" else "Показать",
                    color = accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { visible = !visible }.padding(end = 8.dp),
                )
            }
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = Bolid.ControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = colors.line,
            focusedLabelColor = accent,
            unfocusedLabelColor = colors.muted,
            cursorColor = accent,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
        ),
    )
}

private fun rssiColor(rssi: Int, colors: BolidColors): Color = when {
    rssi >= -60 -> colors.ok
    rssi >= -80 -> colors.warn
    else -> colors.fault
}

private fun deviceLedColor(state: DeviceState?, colors: BolidColors): Color = when {
    state?.autoIsoValid == true && state.realShort -> colors.warn
    state?.mode == DplsMode.SHORT_1 || state?.mode == DplsMode.SHORT_2 || state?.mode == DplsMode.SHORT_T -> colors.fault
    state?.mode?.dangerous == true -> colors.warn
    else -> colors.ok
}

@Composable
private fun FaultChip(label: String, color: Color) {
    Text(
        label,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun deviceListCaption(device: DiscoveredDevice): String {
    val id = device.deviceId?.let { id ->
        "ID ${(id and 0xffff).toString(16).uppercase().padStart(4, '0')}"
    }
    val fw = device.firmwareVersion?.takeIf { it.isNotBlank() }
    val source = when (device.kind) {
        "ble" -> "плата"
        "sim" -> "сим"
        else -> null
    }
    val fault = when {
        device.realShort -> "КЗ изолировано"
        device.reserveLow -> "Низкий резерв"
        device.fromReserve -> "Питание от резерва"
        else -> null
    }
    if (fault != null) return listOfNotNull(fw, fault).joinToString(" · ")
    val parts = listOfNotNull(fw, id, source)
    return if (parts.isEmpty()) "Test-DPLS" else parts.joinToString(" · ")
}

@Composable
private fun EventLogCard(record: EventRecord, whenText: String, colors: BolidColors, isNew: Boolean = false) {
    val accent = if (isNew) Bolid.Cyan else eventAccent(record, colors)
    val calendar = whenText.isNotEmpty() && whenText.first().isDigit()
    val split = if (calendar) whenText.lastIndexOf(", ") else -1
    val dateText = if (split >= 0) whenText.substring(0, split) else whenText
    val timeText = if (split >= 0) whenText.substring(split + 2) else ""
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .bolidCard()
            .then(
                if (isNew) Modifier.background(Bolid.Cyan.copy(alpha = if (colors.isDark) 0.14f else 0.10f))
                else Modifier,
            ),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    eventTitle(record),
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                if (isNew) {
                    Text(
                        "Новое",
                        color = if (colors.isDark) Bolid.Navy else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Bolid.Cyan)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "#${record.sequence}",
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (dateText.isNotEmpty() || timeText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dateText.isNotEmpty()) {
                        Text(dateText, color = colors.muted, fontSize = 12.sp)
                    }
                    if (timeText.isNotEmpty()) {
                        if (dateText.isNotEmpty()) {
                            Box(
                                Modifier
                                    .padding(horizontal = 8.dp)
                                    .size(3.dp)
                                    .clip(CircleShape)
                                    .background(colors.line),
                            )
                        }
                        Text(timeText, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun eventAccent(e: EventRecord, colors: BolidColors): Color = when (e.type) {
    4, 8, 11 -> colors.ok
    5, 6, 14 -> colors.fault
    7, 13 -> colors.warn
    2, 3 -> Bolid.Blue
    else -> colors.muted
}

private fun eventTitle(e: EventRecord): String = when (e.type) {
    1 -> "Запуск устройства"
    2 -> "BLE подключение"
    3 -> "BLE отключение"
    4 -> "Успешный вход"
    5 -> "Ошибка входа · попытка ${e.parameter}"
    6 -> "Вход заблокирован"
    7 -> "Режим: ${DplsMode.fromWire(e.parameter)?.title ?: e.parameter}"
    8 -> "Автовозврат в «Норма»"
    9 -> "Идентификация начата"
    10 -> "Идентификация остановлена"
    11 -> "Пароль установлен"
    12 -> "Питание: ${if (e.parameter == 0) "от ДПЛС" else "от резерва"}"
    13 -> if (e.parameter == 0) "Резерв восстановлен" else "Низкий резерв"
    14 -> if (e.parameter == 0) "Автоизоляция снята" else "Автоизоляция реального КЗ"
    else -> "Событие ${e.type} · ${e.parameter}"
}

private fun formatVoltsNumber(millivolts: Int): String {
    val hundredths = (millivolts + 5) / 10
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

private fun formatVolts(millivolts: Int): String = "${formatVoltsNumber(millivolts)} В"

private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0L -> "$hours ч ${minutes.toString().padStart(2, '0')} мин"
        minutes > 0L -> "$minutes мин $rest с"
        else -> "$rest с"
    }
}

private val ConnectionPhase.showsConnecting: Boolean
    get() = when (this) {
        ConnectionPhase.CONNECTING,
        ConnectionPhase.PAIRING,
        ConnectionPhase.NEGOTIATING_MTU,
        ConnectionPhase.DISCOVERING,
        ConnectionPhase.SUBSCRIBING,
        ConnectionPhase.AUTHENTICATING,
        ConnectionPhase.SYNCHRONIZING,
        ConnectionPhase.RECONNECTING -> true
        ConnectionPhase.IDLE,
        ConnectionPhase.SCANNING,
        ConnectionPhase.READY,
        ConnectionPhase.ERROR -> false
    }
