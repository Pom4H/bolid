import SwiftUI

private enum Page {
    case main, log, export, settings, name, password, about
}

struct ContentView: View {
    @EnvironmentObject private var client: BleClient
    @State private var page: Page = .main
    @State private var chosenMode: DplsMode = .short1
    @State private var pickingTest = false
    @State private var identifying: DiscoveredDevice?
    @State private var showShare: SharePayload?

    private var state: DplsUiState { client.uiState }
    private var connected: Bool { state.selectedDevice != nil }
    private var showTabs: Bool {
        state.authenticated && [Page.main, .log, .settings].contains(page)
    }
    private var showConnecting: Bool {
        !state.authenticated && (!state.credentialsReady || !state.awaitingUserPassword)
    }
    private var showLogin: Bool {
        !state.authenticated && state.credentialsReady && state.awaitingUserPassword
    }

    var body: some View {
        ZStack {
            DplsTheme.bg.ignoresSafeArea()
            Group {
                if let device = identifying, !state.authenticated {
                    IdentifyView(
                        device: device,
                        state: state,
                        back: {
                            client.stopIdentify()
                            client.disconnect()
                            identifying = nil
                        },
                        connect: {
                            client.confirmIdentifiedDevice()
                            identifying = nil
                        },
                        startIdentify: { client.identify(address: $0) }
                    )
                } else if !connected {
                    DevicesView(state: state, scan: client.startScan) { device in
                        identifying = device
                    }
                } else if showConnecting {
                    ConnectingView(state: state, cancel: client.disconnect)
                } else if showLogin {
                    LoginView(
                        state: state,
                        onName: client.updateSetupName,
                        onPassword: client.updateSetupPassword,
                        onRepeat: client.updateSetupRepeatPassword,
                        auth: { client.authenticate(password: $0) },
                        setup: { client.setup(deviceName: $0, password: $1) }
                    )
                } else if pickingTest {
                    TestPickerView(
                        selected: $chosenMode,
                        back: { pickingTest = false },
                        apply: {
                            client.requestMode(chosenMode)
                            pickingTest = false
                        }
                    )
                } else {
                    switch page {
                    case .main:
                        OperationView(
                            state: state,
                            startTest: { pickingTest = true },
                            returnNormal: client.returnToNormal
                        )
                    case .log:
                        LogView(state: state, load: client.loadEventLog) { page = .export }
                            .task(id: "\(page)-\(state.state?.revision ?? 0)-\(state.controlsEnabled)") {
                                if state.controlsEnabled && state.logProgress == nil {
                                    client.loadEventLog()
                                }
                            }
                    case .export:
                        ExportView(
                            back: { page = .log },
                            exportCsv: {
                                showShare = SharePayload(items: [client.eventLogCsv()], name: "dpls-log.csv")
                            },
                            exportTxt: {
                                showShare = SharePayload(items: [client.eventLogTxt()], name: "dpls-log.txt")
                            }
                        )
                    case .settings:
                        SettingsView(
                            state: state,
                            openName: { page = .name },
                            openPassword: { page = .password },
                            openAbout: { page = .about },
                            disconnect: {
                                client.disconnect()
                                page = .main
                            }
                        )
                    case .name:
                        NameView(state: state, setName: client.setDeviceName, clear: client.clearSettingsOp) {
                            page = .settings
                        }
                    case .password:
                        PasswordView(state: state, change: client.changePassword, clear: client.clearSettingsOp) {
                            page = .settings
                        }
                    case .about:
                        AboutView(state: state, refresh: client.requestDeviceInfo) { page = .settings }
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if showTabs {
                    BottomNav(page: page) { page = $0; pickingTest = false }
                }
            }

            if let mode = state.pendingMode {
                ConfirmOverlay(mode: mode, cancel: client.cancelMode, confirm: client.confirmMode)
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: state.state?.mode?.rawValue) { _ in
            if state.state?.mode?.dangerous == true { pickingTest = false }
        }
        .onChange(of: state.authenticated) { authed in
            if authed { identifying = nil }
        }
        .task(id: "\(state.authenticated)-\(state.controlsEnabled)") {
            guard state.authenticated, !state.controlsEnabled, state.state != nil else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                client.refreshState()
            }
        }
        .sheet(item: $showShare) { payload in
            ActivityView(activityItems: payload.fileURLs)
        }
    }
}

private struct SharePayload: Identifiable {
    let id = UUID()
    let items: [String]
    let name: String

    var fileURLs: [URL] {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? items.first?.write(to: url, atomically: true, encoding: .utf8)
        return [url]
    }
}

private struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private struct BottomNav: View {
    let page: Page
    let onPage: (Page) -> Void

    var body: some View {
        HStack(spacing: 0) {
            navTab("Испытание", active: page == .main) { onPage(.main) }
            navTab("Журнал", active: page == .log) { onPage(.log) }
            navTab("Настройки", active: page == .settings) { onPage(.settings) }
        }
        .frame(height: 64)
        .background(DplsTheme.nav)
        .overlay(alignment: .top) { DplsTheme.line.frame(height: 1) }
    }

    private func navTab(_ title: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: title == "Испытание" ? "bolt.fill" : title == "Журнал" ? "list.bullet.rectangle" : "gearshape")
                    .font(.system(size: 18))
                Text(title)
                    .font(.system(size: 11))
            }
            .foregroundStyle(active ? DplsTheme.blue : DplsTheme.muted)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(.plain)
    }
}
