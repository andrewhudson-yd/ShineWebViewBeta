import Foundation
import UIKit
import UserNotifications
import OneSignalFramework

/// Push notifications via OneSignal. If no OneSignal App ID is configured, falls back to
/// plain system permission handling so the JS API still behaves sensibly.
final class ShellPush: NSObject, OSNotificationClickListener, OSNotificationLifecycleListener, OSNotificationPermissionObserver {
    private static let externalIdKey = "NativeShell.push.externalId"

    var onClick: (([String: Any], String?) -> Void)?
    var onReceived: (([String: Any]) -> Void)?
    var onPermissionChange: ((Bool) -> Void)?

    private(set) var enabled = false

    func start(appId: String) {
        guard !appId.isEmpty else { return }
        enabled = true
        OneSignal.initialize(appId, withLaunchOptions: nil)
        OneSignal.Notifications.addClickListener(self)
        OneSignal.Notifications.addForegroundLifecycleListener(self)
        OneSignal.Notifications.addPermissionObserver(self)
    }

    // MARK: - Actions

    func requestPermission(_ done: @escaping (Bool) -> Void) {
        if enabled {
            OneSignal.Notifications.requestPermission({ accepted in
                DispatchQueue.main.async { done(accepted) }
            }, fallbackToSettings: true)
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
            DispatchQueue.main.async {
                if granted { UIApplication.shared.registerForRemoteNotifications() }
                done(granted)
            }
        }
    }

    func state(_ done: @escaping ([String: Any]) -> Void) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            var result: [String: Any] = [
                "provider": self.enabled ? "onesignal" : "none",
                "enabled": self.enabled,
                "permission": ShellPush.describe(settings.authorizationStatus),
                "externalId": UserDefaults.standard.string(forKey: ShellPush.externalIdKey) ?? NSNull()
            ]
            if self.enabled {
                result["optedIn"] = OneSignal.User.pushSubscription.optedIn
                result["subscriptionId"] = OneSignal.User.pushSubscription.id ?? NSNull()
                result["token"] = OneSignal.User.pushSubscription.token ?? NSNull()
            }
            DispatchQueue.main.async { done(result) }
        }
    }

    func login(_ externalId: String) {
        UserDefaults.standard.set(externalId, forKey: ShellPush.externalIdKey)
        if enabled { OneSignal.login(externalId) }
    }

    func logout() {
        UserDefaults.standard.removeObject(forKey: ShellPush.externalIdKey)
        if enabled { OneSignal.logout() }
    }

    func addTags(_ tags: [String: String]) { if enabled { OneSignal.User.addTags(tags) } }
    func removeTags(_ keys: [String]) { if enabled { OneSignal.User.removeTags(keys) } }
    func setEmail(_ email: String) { if enabled { OneSignal.User.addEmail(email) } }
    func optIn() { if enabled { OneSignal.User.pushSubscription.optIn() } }
    func optOut() { if enabled { OneSignal.User.pushSubscription.optOut() } }

    func clearAll() {
        if enabled { OneSignal.Notifications.clearAll() }
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    // MARK: - OneSignal callbacks

    func onClick(event: OSNotificationClickEvent) {
        var data = ShellPush.payload(event.notification)
        data["actionId"] = event.result.actionId ?? NSNull()
        let additional = event.notification.additionalData ?? [:]
        let url = (additional["url"] as? String) ?? event.result.url ?? event.notification.launchURL
        data["url"] = url ?? NSNull()
        DispatchQueue.main.async { self.onClick?(data, url) }
    }

    func onWillDisplay(event: OSNotificationWillDisplayEvent) {
        // Let OneSignal show the banner as normal; just tell the page about it.
        let data = ShellPush.payload(event.notification)
        DispatchQueue.main.async { self.onReceived?(data) }
    }

    func onNotificationPermissionDidChange(_ permission: Bool) {
        DispatchQueue.main.async { self.onPermissionChange?(permission) }
    }

    // MARK: - Helpers

    private static func payload(_ n: OSNotification) -> [String: Any] {
        var additional: [String: Any] = [:]
        for (key, value) in n.additionalData ?? [:] {
            if let k = key as? String { additional[k] = value }
        }
        return [
            "id": n.notificationId ?? NSNull(),
            "title": n.title ?? NSNull(),
            "body": n.body ?? NSNull(),
            "data": additional,
            "launchUrl": n.launchURL ?? NSNull()
        ]
    }

    static func describe(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .authorized, .provisional, .ephemeral: return "granted"
        case .denied: return "denied"
        default: return "prompt"
        }
    }
}
