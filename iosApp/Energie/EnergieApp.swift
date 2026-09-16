import SwiftUI
import Shared

@main
struct EnergieApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.keyboard)
        }
        .onChange(of: scenePhase) { phase in
            // Abgleich nur, solange die App sichtbar ist.
            switch phase {
            case .active: IosAppKt.onAppActive()
            case .background: IosAppKt.onAppBackground()
            default: break
            }
        }
    }
}
