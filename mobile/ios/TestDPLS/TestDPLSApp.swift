import SwiftUI
import UIKit
import DplsCore

private struct ComposeRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosAppKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct TestDPLSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeRoot()
                .ignoresSafeArea()
        }
    }
}
