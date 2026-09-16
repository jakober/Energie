import UIKit
import UserNotifications
import Shared

/// Nimmt das Geraetetoken von Apple entgegen und reicht es an das Kotlin-Modul weiter,
/// das es in der Datenbank eintraegt. Die Hinweise selbst baut iOS aus der Nachricht;
/// die App muss dafuer nicht laufen.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        IosAppKt.onPushToken(token: token, name: UIDevice.current.name)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NSLog("Energie: Anmeldung fuer Hinweise fehlgeschlagen: %@", error.localizedDescription)
    }

    /// Auch anzeigen, wenn die App gerade offen ist.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }
}
