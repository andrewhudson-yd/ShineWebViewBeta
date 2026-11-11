import Foundation
import UIKit
import Capacitor

/// Values from `plugins.NativeShell` in capacitor.config (generated from app.config.json).
struct ShellSettings {
    struct Tab {
        let title: String
        let icon: String
        let url: URL
    }

    var startURL = URL(string: "about:blank")!
    var internalHosts: [String] = []
    var trustedOrigins: [String] = []
    var externalLinks = "inApp"
    var accentColor: UIColor?
    var accentHex = ""
    var backgroundHex = ""
    var backgroundColor: UIColor?
    var pullToRefresh = true
    var autoHaptics = true
    var oneSignalAppId = ""
    var promptOnLaunch = false
    var deepLinkScheme = ""
    var tabs: [Tab] = []
    var safeAreaApp = true
    var nativeDeepLinks = true
    var offlineMode = "banner"
    var offlineMessage = ""
    var offlineQueuedMessage = ""
    var onlineMessage = ""
    var offlineLinks: [[String: Any]] = []
    var splashVideo = ""
    var splashPlay = "everyLaunch"
    var splashSkippable = true
    var splashMinDuration: TimeInterval = 0
    var splashMaxWait: TimeInterval = 8
    var splashBackground: UIColor = .black
    var splashStatusBar = "auto"

    init() {}

    init(config: PluginConfig) {
        if let s = config.getString("startUrl"), let url = URL(string: s) { startURL = url }
        internalHosts = (config.getArray("internalHosts") ?? []).compactMap { $0 as? String }
        trustedOrigins = (config.getArray("trustedOrigins") ?? []).compactMap { $0 as? String }
        externalLinks = config.getString("externalLinks", "inApp") ?? "inApp"
        accentHex = config.getString("accentColor", "") ?? ""
        accentColor = UIColor.capacitor.color(fromHex: accentHex)
        backgroundHex = config.getString("backgroundColor", "") ?? ""
        backgroundColor = UIColor.capacitor.color(fromHex: backgroundHex)
        pullToRefresh = config.getBoolean("pullToRefresh", true)
        autoHaptics = config.getBoolean("autoHaptics", true)
        oneSignalAppId = config.getString("oneSignalAppId", "") ?? ""
        promptOnLaunch = config.getBoolean("promptOnLaunch", false)
        deepLinkScheme = config.getString("deepLinkScheme", "") ?? ""

        safeAreaApp = config.getString("safeArea", "app") != "web"
        nativeDeepLinks = config.getBoolean("nativeDeepLinks", true)
        if let off = config.getObject("offline") {
            offlineMode = off["mode"] as? String ?? "banner"
            offlineMessage = off["message"] as? String ?? ""
            offlineQueuedMessage = off["queuedMessage"] as? String ?? ""
            onlineMessage = off["onlineMessage"] as? String ?? ""
            offlineLinks = (off["links"] as? JSArray)?.compactMap { $0 as? [String: Any] } ?? []
        }

        if let splash = config.getObject("splash") {
            splashVideo = splash["video"] as? String ?? ""
            splashPlay = splash["play"] as? String ?? "everyLaunch"
            splashStatusBar = splash["statusBarStyle"] as? String ?? "auto"
            splashSkippable = splash["skippable"] as? Bool ?? true
            splashMinDuration = (splash["minDuration"] as? NSNumber)?.doubleValue ?? 0
            splashMaxWait = max(1, (splash["maxWait"] as? NSNumber)?.doubleValue ?? 8)
            splashBackground = UIColor.capacitor.color(fromHex: splash["backgroundColor"] as? String ?? "") ?? backgroundColor ?? .black
        }

        let base = startURL
        tabs = (config.getArray("tabs") ?? []).compactMap { item in
            guard let obj = item as? JSObject,
                  let raw = obj["url"] as? String,
                  let url = URL(string: raw, relativeTo: base)?.absoluteURL else { return nil }
            return Tab(title: obj["title"] as? String ?? "", icon: obj["icon"] as? String ?? "", url: url)
        }
    }

    /// Hosts that load inside the app. Supports "*.example.com" wildcards.
    func isInternal(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host?.lowercased() else { return false }
        if host == startURL.host?.lowercased() { return true }
        return internalHosts.contains { pattern in
            let p = pattern.lowercased()
            if p.hasPrefix("*.") {
                let root = String(p.dropFirst(2))
                return host == root || host.hasSuffix("." + root)
            }
            return host == p
        }
    }

    /// Turns `myapp://orders/12?x=1` or `/orders/12` into a full URL on the start origin.
    func resolve(_ url: URL) -> URL? {
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "http" || scheme == "https" { return url }
        if !deepLinkScheme.isEmpty, scheme == deepLinkScheme.lowercased() {
            var path = (url.host ?? "") + url.path
            if !path.hasPrefix("/") { path = "/" + path }
            var comps = URLComponents(url: startURL, resolvingAgainstBaseURL: false)
            comps?.path = path
            comps?.query = url.query
            comps?.fragment = url.fragment
            return comps?.url
        }
        return nil
    }

    func resolve(string: String) -> URL? {
        guard !string.isEmpty else { return nil }
        if let url = URL(string: string), url.scheme != nil { return resolve(url) ?? url }
        return URL(string: string, relativeTo: startURL)?.absoluteURL
    }

    var dictionary: [String: Any] {
        return [
            "startUrl": startURL.absoluteString,
            "internalHosts": internalHosts,
            "externalLinks": externalLinks,
            "accentColor": accentHex,
            "backgroundColor": backgroundHex,
            "pullToRefresh": pullToRefresh,
            "autoHaptics": autoHaptics,
            "pushEnabled": !oneSignalAppId.isEmpty,
            "tabs": tabs.map { ["title": $0.title, "icon": $0.icon, "url": $0.url.absoluteString] }
        ]
    }
}
