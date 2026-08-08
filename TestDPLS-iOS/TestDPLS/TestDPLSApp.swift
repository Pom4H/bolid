import SwiftUI

@main
struct TestDPLSApp: App {
    @StateObject private var bleClient = BleClient()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleClient)
                .onAppear {
                    // Kick an initial scan once Bluetooth is up (BleClient reacts to poweredOn).
                }
        }
    }
}
