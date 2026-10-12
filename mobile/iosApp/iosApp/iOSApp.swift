// VectorCraft on iPadOS (and iPhone): the whole UI is the shared Compose app in the
// VectorCraftShared framework (mobile/shared); this file only hosts it.
import SwiftUI
import VectorCraftShared

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.keyboard)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        Platform_iosKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
