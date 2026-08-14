import SwiftUI

// MARK: - Shared chrome

struct ScreenTitle: View {
    let title: String
    var back: (() -> Void)?

    var body: some View {
        ZStack {
            if let back {
                HStack {
                    Button("‹", action: back)
                        .font(.system(size: 40))
                        .foregroundStyle(.white)
                    Spacer()
                }
            }
            Text(title)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .padding(.horizontal, 42)
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 64)
        .padding(.horizontal, 18)
    }
}

struct PrimaryButton: View {
    let title: String
    var enabled: Bool = true
    var color: Color = DplsTheme.blue
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(enabled ? color : color.opacity(0.35))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .disabled(!enabled)
        .padding(.horizontal, 18)
        .padding(.vertical, 8)
        .buttonStyle(.plain)
    }
}

struct SecondaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(DplsTheme.muted)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
        }
        .padding(.horizontal, 18)
        .buttonStyle(.plain)
    }
}

struct DarkField: View {
    let title: String
    @Binding var text: String
    var secure: Bool = false
    var hint: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 12))
                .foregroundStyle(DplsTheme.muted)
            Group {
                if secure {
                    SecureField("", text: $text)
                } else {
                    TextField("", text: $text)
                }
            }
            .padding(14)
            .background(DplsTheme.panel)
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(DplsTheme.line, lineWidth: 1)
            )
            .foregroundStyle(.white)
            if let hint {
                Text(hint)
                    .font(.system(size: 11))
                    .foregroundStyle(DplsTheme.muted.opacity(0.8))
            }
        }
    }
}

struct CardBox<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            content
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(DplsTheme.panel)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(DplsTheme.line, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Devices

struct DevicesView: View {
    let state: DplsUiState
    let scan: () -> Void
    let openDevice: (DiscoveredDevice) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Устройства рядом")
            HStack {
                Text(state.phase == .scanning ? "Ищем устройства по BLE..." : "Доступные устройства")
                    .font(.system(size: 12))
                    .foregroundStyle(DplsTheme.muted)
                Spacer()
                if state.phase == .scanning {
                    ProgressView().tint(DplsTheme.blue).scaleEffect(0.8)
                }
            }
            .padding(.horizontal, 20)

            if state.devices.isEmpty {
                Spacer()
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 48))
                    .foregroundStyle(DplsTheme.line)
                Text(state.phase == .scanning ? "Идёт поиск устройств…" : "Устройства не найдены")
                    .foregroundStyle(DplsTheme.muted)
                    .padding(.top, 14)
                if state.phase != .scanning {
                    Text("Включите Bluetooth и поднесите телефон\nближе к устройству, затем обновите")
                        .font(.system(size: 12))
                        .foregroundStyle(DplsTheme.muted.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.top, 6)
                }
                Spacer()
            } else {
                List(state.devices) { device in
                    Button { openDevice(device) } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "cpu")
                                .foregroundStyle(DplsTheme.blue)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.userName ?? device.advertisedName)
                                    .foregroundStyle(.white)
                                    .lineLimit(2)
                                Text(device.address)
                                    .font(.system(size: 11))
                                    .foregroundStyle(DplsTheme.muted)
                                    .lineLimit(1)
                            }
                            Spacer()
                            Text("\(device.rssi) дБм")
                                .font(.system(size: 12))
                                .foregroundStyle(DplsTheme.green)
                            Text("›")
                                .foregroundStyle(DplsTheme.muted)
                                .font(.system(size: 24))
                        }
                        .padding(.vertical, 6)
                    }
                    .listRowBackground(DplsTheme.bg)
                    .listRowSeparatorTint(DplsTheme.line)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }

            PrimaryButton(
                title: state.phase == .scanning ? "Обновление..." : "↻  Обновить",
                enabled: state.phase != .scanning,
                action: scan
            )
        }
    }
}

// MARK: - Identify / Connect / Login

struct IdentifyView: View {
    let device: DiscoveredDevice
    let state: DplsUiState
    let back: () -> Void
    let connect: () -> Void
    let startIdentify: (String) -> Void
    @State private var seconds = 60

    private var ledReady: Bool {
        state.identifyActive && state.identifyLedLive && state.error == nil
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Показать на объекте", back: back)
            Spacer()
            VStack(spacing: 16) {
                Image(systemName: state.phase == .pairing || (state.identifyActive && !ledReady)
                      ? "wave.3.right.circle" : "lightbulb")
                    .font(.system(size: 72))
                    .foregroundStyle(ledReady ? DplsTheme.green : DplsTheme.blue)
                Text(statusText)
                    .multilineTextAlignment(.center)
                    .font(.system(size: 17))
                    .foregroundStyle(state.error != nil ? DplsTheme.orange : .white)
                    .padding(.horizontal, 24)
                if state.phase == .pairing {
                    Text("Окно открылось поверх приложения")
                        .font(.system(size: 13))
                        .foregroundStyle(DplsTheme.muted)
                } else if ledReady {
                    Text(String(format: "%02d:%02d", seconds / 60, seconds % 60))
                        .font(.system(size: 28))
                        .foregroundStyle(.white)
                } else if state.identifyActive && state.error == nil {
                    ProgressView().tint(DplsTheme.blue)
                }
            }
            Spacer()
            let retry = state.error != nil
            PrimaryButton(
                title: retry ? "Повторить сопряжение"
                    : state.phase == .pairing ? "Ожидаем подтверждения…"
                    : "Это устройство",
                enabled: retry || ledReady,
                action: { retry ? startIdentify(device.address) : connect() }
            )
            SecondaryButton(title: "Остановить", action: back)
        }
        .onAppear { startIdentify(device.address) }
        .task(id: "\(device.address)-\(ledReady)") {
            guard ledReady else { return }
            seconds = 60
            while seconds > 0 && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                seconds -= 1
            }
        }
    }

    private var statusText: String {
        if let error = state.error { return error }
        if state.phase == .pairing { return "Подтвердите сопряжение\nв системном диалоге Bluetooth" }
        if ledReady { return "Светодиод на устройстве\nмигает с частотой 1 Гц" }
        return "Подключение к устройству…"
    }
}

struct ConnectingView: View {
    let state: DplsUiState
    let cancel: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Подключение", back: cancel)
            Spacer()
            ProgressView()
                .controlSize(.large)
                .tint(DplsTheme.blue)
            Text("Подключение к\n\(state.selectedDevice?.userName ?? state.selectedDevice?.advertisedName ?? "устройству")...")
                .multilineTextAlignment(.center)
                .font(.system(size: 18))
                .foregroundStyle(.white)
                .padding(.top, 30)
                .padding(.horizontal, 24)
            if let error = state.error {
                Text(error)
                    .foregroundStyle(DplsTheme.orange)
                    .multilineTextAlignment(.center)
                    .padding(.top, 18)
                    .padding(.horizontal, 24)
            }
            Spacer()
            SecondaryButton(title: "Отменить", action: cancel)
        }
    }
}

struct LoginView: View {
    let state: DplsUiState
    let onName: (String) -> Void
    let onPassword: (String) -> Void
    let onRepeat: (String) -> Void
    let auth: (String) -> Void
    let setup: (String, String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: state.initialized ? "Вход" : "Первичная настройка")
            if let target = state.selectedDevice?.userName ?? state.selectedDevice?.advertisedName {
                Text(target)
                    .font(.system(size: 13))
                    .foregroundStyle(DplsTheme.muted)
                    .lineLimit(1)
            }
            VStack(spacing: 14) {
                if !state.initialized {
                    DarkField(title: "Имя устройства", text: Binding(
                        get: { state.setupName },
                        set: onName
                    ))
                }
                DarkField(
                    title: "Пароль",
                    text: Binding(get: { state.setupPassword }, set: onPassword),
                    secure: true,
                    hint: state.initialized ? nil : "Не менее 8 символов, латинские буквы и цифры"
                )
                if !state.initialized {
                    DarkField(
                        title: "Повторите пароль",
                        text: Binding(get: { state.setupRepeatPassword }, set: onRepeat),
                        secure: true
                    )
                }
                if let error = state.error {
                    Text(error)
                        .foregroundStyle(DplsTheme.orange)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(18)
            Spacer()
            PrimaryButton(
                title: state.initialized ? "Подключиться" : "Сохранить",
                enabled: state.setupFormReady,
                action: {
                    if state.initialized {
                        auth(state.setupPassword)
                    } else {
                        setup(state.setupName, state.setupPassword)
                    }
                }
            )
        }
    }
}

// MARK: - Operation

struct OperationView: View {
    let state: DplsUiState
    let startTest: () -> Void
    let returnNormal: () -> Void
    @State private var tick = 0

    private var mode: DplsMode { state.state?.mode ?? .normal }
    private var testActive: Bool { mode.dangerous }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 2) {
                Text("Испытание")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.white)
                Text(deviceName)
                    .font(.system(size: 12))
                    .foregroundStyle(DplsTheme.muted)
                    .lineLimit(1)
            }
            .padding(.vertical, 8)

            CardBox {
                HStack(spacing: 12) {
                    Image(systemName: testActive ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                        .font(.system(size: 28))
                        .foregroundStyle(testActive ? DplsTheme.orange : DplsTheme.green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(mode.title)
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(testActive ? DplsTheme.orange : DplsTheme.green)
                        if !mode.portHint.isEmpty {
                            Text(mode.portHint)
                                .font(.system(size: 12))
                                .foregroundStyle(DplsTheme.muted)
                        }
                    }
                }
                Divider().overlay(DplsTheme.line)
                if state.deviceInfo?.adcPresent != false {
                    if state.deviceInfo?.multiVoltageReport == true, let s = state.state {
                        infoRow("Клемма +1", voltageText(s.port1VoltageMv, s.port1VoltageValid), s.port1VoltageValid ? DplsTheme.green : DplsTheme.muted)
                        infoRow("Клемма +2", voltageText(s.port2VoltageMv, s.port2VoltageValid), s.port2VoltageValid ? DplsTheme.green : DplsTheme.muted)
                        infoRow("Клемма +Т", voltageText(s.portTVoltageMv, s.portTVoltageValid), s.portTVoltageValid ? DplsTheme.green : DplsTheme.muted)
                        infoRow("Резерв", voltageText(s.reserveVoltageMv, s.reserveVoltageValid), s.reserveVoltageValid ? DplsTheme.green : DplsTheme.muted)
                    } else {
                        infoRow(
                            "Напряжение",
                            state.state?.lineVoltageValid == true
                                ? String(format: "%.1f В", Double(state.state!.voltageMv) / 1000)
                                : "—",
                            state.state?.lineVoltageValid == true ? DplsTheme.green : DplsTheme.muted
                        )
                    }
                }
                infoRow(
                    "Питание",
                    state.state?.powerValid == true ? "От \(state.state!.powerSource.title)" : "Не определён",
                    state.state?.powerValid == true ? DplsTheme.green : DplsTheme.muted
                )
                if state.state?.reserveValid == true && state.state?.reserveLow == true {
                    infoRow("Заряд резерва", "Низкий", DplsTheme.orange)
                }
                if state.state?.autoIsoValid == true && state.state?.realShort == true {
                    Divider().overlay(DplsTheme.line)
                    Text("⚠ Автоизоляция реального КЗ")
                        .foregroundStyle(DplsTheme.orange)
                        .font(.system(size: 14, weight: .medium))
                }
                if testActive {
                    Divider().overlay(DplsTheme.line)
                    HStack {
                        countdownBadge
                        Spacer()
                        VStack(alignment: .trailing, spacing: 6) {
                            Text("До автоматического\nвозврата в «Норма»")
                                .font(.system(size: 13))
                                .foregroundStyle(DplsTheme.muted)
                                .multilineTextAlignment(.trailing)
                            if !mode.controllerEffect.isEmpty {
                                Text(mode.controllerEffect)
                                    .font(.system(size: 11))
                                    .foregroundStyle(DplsTheme.orange)
                                    .multilineTextAlignment(.trailing)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 18)

            Spacer()
            if testActive {
                PrimaryButton(title: "Вернуть в «Норма»", enabled: state.controlsEnabled, color: DplsTheme.orange, action: returnNormal)
            } else {
                PrimaryButton(title: "Провести испытание", enabled: state.controlsEnabled, action: startTest)
            }
        }
        .task(id: "\(state.state?.revision ?? 0)") {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                tick += 1
            }
        }
    }

    private var deviceName: String {
        if let n = state.deviceInfo?.userName, !n.isEmpty { return n }
        return state.selectedDevice?.userName ?? state.selectedDevice?.advertisedName ?? "Устройство"
    }

    private var countdownSeconds: Int {
        _ = tick
        guard let s = state.state, s.mode.dangerous else { return 0 }
        let elapsed = Int64(Date().timeIntervalSince1970 * 1000) - s.receivedAtMillis
        return max(0, s.automaticReturnSeconds - Int(elapsed / 1000))
    }

    private var countdownBadge: some View {
        let seconds = countdownSeconds
        return ZStack {
            Circle().stroke(DplsTheme.line, lineWidth: 4)
            Circle()
                .trim(from: 0, to: CGFloat(min(seconds, 300)) / 300)
                .stroke(DplsTheme.orange, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(String(format: "%02d:%02d", seconds / 60, seconds % 60))
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.white)
        }
        .frame(width: 88, height: 88)
    }

    private func voltageText(_ millivolts: Int, _ valid: Bool) -> String {
        valid ? String(format: "%.1f В", Double(millivolts) / 1000) : "—"
    }

    private func infoRow(_ title: String, _ value: String, _ color: Color) -> some View {
        HStack {
            Text(title).foregroundStyle(DplsTheme.muted).font(.system(size: 14))
            Spacer()
            Text(value).foregroundStyle(color).font(.system(size: 14, weight: .medium))
        }
    }
}

struct TestPickerView: View {
    @Binding var selected: DplsMode
    let back: () -> Void
    let apply: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Выбор испытания", back: back)
            ScrollView {
                VStack(spacing: 8) {
                    ForEach(DplsMode.allCases.filter(\.dangerous)) { mode in
                        Button { selected = mode } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(mode.title)
                                        .font(.system(size: 16, weight: .medium))
                                        .foregroundStyle(.white)
                                    Text(mode.portHint)
                                        .font(.system(size: 12))
                                        .foregroundStyle(DplsTheme.muted)
                                }
                                Spacer()
                                Image(systemName: selected == mode ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(DplsTheme.blue)
                            }
                            .padding(14)
                            .overlay(
                                RoundedRectangle(cornerRadius: 5)
                                    .stroke(selected == mode ? DplsTheme.blue : DplsTheme.line, lineWidth: selected == mode ? 1 : 0.5)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 18)
            }
            PrimaryButton(title: "Применить", action: apply)
        }
    }
}

struct ConfirmOverlay: View {
    let mode: DplsMode
    let cancel: () -> Void
    let confirm: () -> Void

    var body: some View {
        ZStack {
            DplsTheme.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                ScreenTitle(title: "Подтверждение", back: cancel)
                ScrollView {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(DplsTheme.orange)
                        Text("Внимание!")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(DplsTheme.orange)
                        Text("Испытание «\(mode.title)»")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(.white)
                        if !mode.portHint.isEmpty {
                            Text(mode.portHint).foregroundStyle(DplsTheme.muted).font(.system(size: 13))
                        }
                        Text("Режим сформирует события на контроллере КДЛ и может нарушить работу участка ДПЛС на время испытания.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(DplsTheme.muted)
                            .font(.system(size: 15))
                            .padding(.horizontal, 24)
                        if !mode.controllerEffect.isEmpty {
                            Text(mode.controllerEffect)
                                .multilineTextAlignment(.center)
                                .foregroundStyle(DplsTheme.orange)
                                .font(.system(size: 14, weight: .medium))
                                .padding(.horizontal, 24)
                        }
                    }
                    .padding(.vertical, 12)
                }
                PrimaryButton(title: "Продолжить", color: DplsTheme.orange, action: confirm)
                SecondaryButton(title: "Отмена", action: cancel)
            }
        }
    }
}

// MARK: - Log / Export

struct LogView: View {
    let state: DplsUiState
    let load: () -> Void
    let export: () -> Void

    private var currentRunFirstSeq: UInt32 {
        state.eventLog.filter { $0.type == 1 }.map(\.sequence).max() ?? 0
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Журнал")
            if state.eventLog.isEmpty {
                Spacer()
                if let progress = state.logProgress {
                    ProgressView(value: Double(max(progress, 0.05)))
                        .tint(DplsTheme.blue)
                        .padding(.horizontal, 40)
                    Text("Загрузка журнала…")
                        .foregroundStyle(DplsTheme.muted)
                        .padding(.top, 16)
                } else {
                    Text("Журнал пуст")
                        .foregroundStyle(DplsTheme.muted)
                    PrimaryButton(title: "Обновить", action: load)
                }
                Spacer()
            } else {
                List(state.eventLog) { event in
                    let ts = dplsEventTime(event, currentRunFirstSeq: currentRunFirstSeq, bootEpochSec: state.deviceBootEpochSeconds)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(ts.full)
                                .font(.system(size: 12))
                                .foregroundStyle(DplsTheme.muted)
                            Spacer()
                            Text("#\(event.sequence)")
                                .font(.system(size: 11))
                                .foregroundStyle(DplsTheme.muted)
                        }
                        Text(dplsEventTitle(type: event.type, parameter: event.parameter))
                            .foregroundStyle(.white)
                            .font(.system(size: 14))
                    }
                    .listRowBackground(DplsTheme.bg)
                    .listRowSeparatorTint(DplsTheme.line)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                if state.logProgress != nil {
                    ProgressView(value: Double(state.logProgress ?? 0))
                        .tint(DplsTheme.blue)
                        .padding(.horizontal, 18)
                }
                PrimaryButton(title: "Выгрузить", enabled: state.logProgress == nil, action: export)
            }
        }
    }
}

struct ExportView: View {
    let back: () -> Void
    let exportCsv: () -> Void
    let exportTxt: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Выгрузка журнала", back: back)
            Spacer()
            VStack(spacing: 12) {
                PrimaryButton(title: "CSV", action: exportCsv)
                PrimaryButton(title: "Текст", action: exportTxt)
            }
            Spacer()
        }
    }
}

// MARK: - Settings

struct SettingsView: View {
    let state: DplsUiState
    let openName: () -> Void
    let openPassword: () -> Void
    let openAbout: () -> Void
    let disconnect: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Настройки")
            VStack(spacing: 0) {
                settingsRow("Имя устройства", action: openName)
                settingsRow("Сменить пароль", action: openPassword)
                settingsRow("Об устройстве", action: openAbout)
            }
            .padding(.horizontal, 18)
            Spacer()
            PrimaryButton(title: "Отключиться", color: DplsTheme.orange, action: disconnect)
        }
    }

    private func settingsRow(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(.white)
                Spacer()
                Text("›").foregroundStyle(DplsTheme.muted).font(.system(size: 24))
            }
            .padding(.vertical, 16)
            .overlay(alignment: .bottom) { DplsTheme.line.frame(height: 0.5) }
        }
        .buttonStyle(.plain)
    }
}

struct NameView: View {
    let state: DplsUiState
    let setName: (String) -> Void
    let clear: () -> Void
    let back: () -> Void
    @State private var name = ""

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Имя устройства", back: { clear(); back() })
            DarkField(title: "Новое имя", text: $name)
                .padding(18)
            if let err = state.settingsError {
                Text(err).foregroundStyle(DplsTheme.orange).padding(.horizontal, 18)
            } else if state.settingsOp == .done {
                Text("Имя сохранено").foregroundStyle(DplsTheme.green).padding(.horizontal, 18)
            }
            Spacer()
            PrimaryButton(
                title: state.settingsOp == .inProgress ? "Сохранение…" : "Сохранить",
                enabled: state.settingsOp != .inProgress && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                action: { setName(name) }
            )
        }
        .onAppear {
            clear()
            name = state.deviceInfo?.userName ?? state.selectedDevice?.userName ?? ""
        }
    }
}

struct PasswordView: View {
    let state: DplsUiState
    let change: (String, String) -> Void
    let clear: () -> Void
    let back: () -> Void
    @State private var current = ""
    @State private var newPassword = ""
    @State private var repeatPassword = ""

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Смена пароля", back: { clear(); back() })
            VStack(spacing: 14) {
                DarkField(title: "Текущий пароль", text: $current, secure: true)
                DarkField(title: "Новый пароль", text: $newPassword, secure: true, hint: "Не менее 8 символов")
                DarkField(title: "Повторите пароль", text: $repeatPassword, secure: true)
                if let err = state.settingsError {
                    Text(err).foregroundStyle(DplsTheme.orange)
                } else if state.settingsOp == .done {
                    Text("Пароль изменён").foregroundStyle(DplsTheme.green)
                }
            }
            .padding(18)
            Spacer()
            PrimaryButton(
                title: state.settingsOp == .inProgress ? "Сохранение…" : "Сохранить",
                enabled: state.settingsOp != .inProgress
                    && newPassword.count >= 8
                    && newPassword == repeatPassword
                    && !current.isEmpty,
                action: { change(current, newPassword) }
            )
        }
        .onAppear { clear() }
    }
}

struct AboutView: View {
    let state: DplsUiState
    let refresh: () -> Void
    let back: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenTitle(title: "Об устройстве", back: back)
            CardBox {
                row("Имя", state.deviceInfo?.userName ?? state.selectedDevice?.userName ?? "—")
                row("ID", state.deviceInfo?.shortId ?? state.selectedDevice?.deviceId.map { String(format: "DPLS-%08X", $0) } ?? "—")
                row("Прошивка", state.deviceInfo?.firmwareVersion ?? "—")
                row("Протокол", state.deviceInfo.map { "\($0.protocolVersion)" } ?? "—")
                row("АЦП", state.deviceInfo.map { $0.adcPresent ? ($0.adcCalibrated ? "есть, калиброван" : "есть") : "нет" } ?? "—")
                row("Feedback", state.deviceInfo.map { $0.hardwareReadback ? "да" : "нет" } ?? "—")
            }
            .padding(18)
            Spacer()
            PrimaryButton(title: "Обновить", action: refresh)
        }
        .onAppear { refresh() }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(DplsTheme.muted)
            Spacer()
            Text(value).foregroundStyle(.white).multilineTextAlignment(.trailing)
        }
        .font(.system(size: 14))
    }
}
