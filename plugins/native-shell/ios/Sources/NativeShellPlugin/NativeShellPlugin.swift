import Foundation
import UIKit
import WebKit
import SafariServices
import AVFoundation
import Photos
import CoreLocation
import UserNotifications
import Network
import Capacitor

@objc(NativeShellPlugin)
public class NativeShellPlugin: CAPPlugin, CAPBridgedPlugin, CLLocationManagerDelegate {
    public let identifier = "NativeShellPlugin"
    public let jsName = "NativeShell"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pageReady", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "haptic", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "vibrate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openUrl", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "retry", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "keepAwake", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPullToRefresh", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setRefreshBlocked", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "saveFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setBadge", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBadge", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "selectTab", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setTabBadge", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setTabsVisible", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushRequestPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushGetState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushLogin", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushLogout", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushAddTags", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushRemoveTags", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushSetEmail", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushOptIn", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushOptOut", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pushClearAll", returnType: CAPPluginReturnPromise)
    ]

    private var settings = ShellSettings()
    private let push = ShellPush()
    private var refreshControl: UIRefreshControl?
    private var refreshEnabled = true
    private var lastURL: URL?
    private var pendingURL: URL?
    private var isReady = false
    private var urlObservation: NSKeyValueObservation?
    private var observers: [NSObjectProtocol] = []
    private var locationManager: CLLocationManager?
    private var locationCallbacks: [() -> Void] = []
    private var videoSplash: ShellVideoSplash?
    private var splashDone = false
    private var loadingObservation: NSKeyValueObservation?
    private let pathMonitor = NWPathMonitor()
    private var online = true
    private var pageShown = false

    private var webView: WKWebView? { bridge?.webView }
    private var rootController: ShellRootViewController? { bridge?.viewController?.parent as? ShellRootViewController }

    // MARK: - Lifecycle

    override public func load() {
        settings = ShellSettings(config: getConfig())
        refreshEnabled = settings.pullToRefresh

        injectBridgeScript()
        setUpPullToRefresh()
        setUpPush()
        observeNavigation()
        observeDeepLinks()
        showVideoSplash()
        watchConnectivity()

        // The container is attached after the bridge loads.
        DispatchQueue.main.async { [weak self] in self?.setUpTabs() }
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    /// Intro video over the web view (app.config.json → splash.video).
    private func showVideoSplash() {
        guard !settings.splashVideo.isEmpty,
              let container = rootController?.view ?? bridge?.viewController?.view,
              let url = Bundle.main.url(forResource: (settings.splashVideo as NSString).lastPathComponent, withExtension: nil,
                                        subdirectory: "public/" + (settings.splashVideo as NSString).deletingLastPathComponent) else {
            splashDone = true
            return
        }
        let options = ShellVideoSplash.Options(url: url, background: settings.splashBackground, skippable: settings.splashSkippable,
                                               minDuration: settings.splashMinDuration, maxWait: settings.splashMaxWait)
        guard let splash = ShellVideoSplash.make(options: options, playMode: settings.splashPlay) else {
            splashDone = true
            return
        }
        splash.onDone = { [weak self] in
            guard let self = self else { return }
            self.rootController?.statusBarOverride = nil
            self.splashDone = true
            self.videoSplash = nil
            self.loadingObservation = nil
            self.webView?.evaluateJavaScript("document.documentElement.classList.add('native-splash-done')", completionHandler: nil)
            self.notifyListeners("splashDone", data: [:])
        }
        videoSplash = splash
        rootController?.statusBarOverride = splashUsesLightIcons() ? .lightContent : .darkContent
        splash.show(in: container)
        // The page (or the offline page) has finished loading.
        loadingObservation = webView?.observe(.isLoading, options: [.new]) { [weak self] webView, _ in
            guard !webView.isLoading, webView.url != nil else { return }
            DispatchQueue.main.async { self?.videoSplash?.markPageReady() }
        }
    }

    /// "light" / "dark", or "auto": white icons when the splash background is dark.
    private func splashUsesLightIcons() -> Bool {
        switch settings.splashStatusBar {
        case "light": return true
        case "dark": return false
        default:
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            settings.splashBackground.getRed(&r, green: &g, blue: &b, alpha: &a)
            return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 0.5
        }
    }

    /// Adds native-shell.js (from the app's web assets) to every page, before the page's own scripts.
    private func injectBridgeScript() {
        guard let webView = webView,
              let file = Bundle.main.url(forResource: "native-shell", withExtension: "js", subdirectory: "public"),
              let source = try? String(contentsOf: file, encoding: .utf8) else {
            CAPLog.print("⚡️  NativeShell: public/native-shell.js not found — run `npx cap sync`")
            return
        }
        let info = Bundle.main.infoDictionary ?? [:]
        let boot: [String: Any] = [
            "platform": "ios",
            "appVersion": info["CFBundleShortVersionString"] as? String ?? "",
            "trustedOrigins": settings.trustedOrigins,
            "internalHosts": settings.internalHosts + [settings.startURL.host ?? ""],
            "localOrigin": bridge.map { ShellSettings.origin(of: $0.config.localURL) } ?? "",
            "autoHaptics": settings.autoHaptics,
            "offlineMode": settings.offlineMode,
            "offlineMessage": settings.offlineMessage,
            "offlineQueuedMessage": settings.offlineQueuedMessage,
            "onlineMessage": settings.onlineMessage,
            "offlineLinks": settings.offlineLinks
        ]
        let json = (try? JSONSerialization.data(withJSONObject: boot)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let script = WKUserScript(source: "window.__NATIVE_SHELL__=\(json);\n\(source)",
                                  injectionTime: .atDocumentStart, forMainFrameOnly: true)
        webView.configuration.userContentController.addUserScript(script)
    }

    private func setUpPullToRefresh() {
        guard let scrollView = webView?.scrollView else { return }
        let control = UIRefreshControl()
        if let tint = settings.accentColor { control.tintColor = tint }
        control.addTarget(self, action: #selector(handleRefresh), for: .valueChanged)
        refreshControl = control
        applyPullToRefresh(refreshEnabled)
        scrollView.bounces = true
    }

    private func applyPullToRefresh(_ enabled: Bool) {
        guard let scrollView = webView?.scrollView, let control = refreshControl else { return }
        if enabled {
            if control.superview == nil { scrollView.refreshControl = control }
        } else {
            control.endRefreshing()
            scrollView.refreshControl = nil
        }
    }

    @objc private func handleRefresh() {
        if settings.autoHaptics { ShellHaptics.play("light") }
        reloadCurrent()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            self?.refreshControl?.endRefreshing()
        }
    }

    private func setUpTabs() {
        guard let root = rootController else { return }
        root.useSafeArea(settings.safeAreaApp, background: settings.backgroundColor)
        root.configureTabs(settings.tabs, tint: settings.accentColor)
        root.onSelectTab = { [weak self] index in
            guard let self = self, self.settings.tabs.indices.contains(index) else { return }
            if self.settings.autoHaptics { ShellHaptics.play("selection") }
            self.navigate(to: self.settings.tabs[index].url)
            self.notifyListeners("tabSelected", data: ["index": index, "url": self.settings.tabs[index].url.absoluteString])
        }
    }

    private func setUpPush() {
        push.onClick = { [weak self] data, url in
            guard let self = self else { return }
            if let raw = url, let target = self.settings.resolve(string: raw) {
                if self.settings.isInternal(target) { self.navigate(to: target) } else { self.open(target, mode: "inApp") }
            }
            self.notifyListeners("pushClick", data: data, retainUntilConsumed: true)
        }
        push.onReceived = { [weak self] data in
            // A push with Additional Data {"badge": 3} updates the icon while the app is open.
            // (When the app is closed, send OneSignal's ios_badgeType/ios_badgeCount instead — see README.)
            if let extra = data["data"] as? [String: Any], let badge = (extra["badge"] as? NSNumber)?.intValue ?? Int(extra["badge"] as? String ?? "") {
                self?.applyBadge(max(0, badge))
            }
            self?.notifyListeners("pushReceived", data: data)
        }
        push.onPermissionChange = { [weak self] granted in self?.notifyListeners("pushPermission", data: ["granted": granted]) }
        push.start(appId: settings.oneSignalAppId)

        if settings.promptOnLaunch {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                self?.push.requestPermission { _ in }
            }
        }
    }

    private func observeNavigation() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .capacitorDecidePolicyForNavigationAction, object: nil, queue: .main) { [weak self] note in
            guard let action = note.object as? WKNavigationAction,
                  action.targetFrame?.isMainFrame ?? true,
                  let url = action.request.url,
                  let self = self, self.settings.isInternal(url) else { return }
            self.lastURL = url
        })
        observers.append(center.addObserver(forName: .capacitorViewDidAppear, object: nil, queue: .main) { [weak self] _ in
            self?.markReady()
        })
        urlObservation = webView?.observe(\.url, options: [.new]) { [weak self] webView, _ in
            DispatchQueue.main.async { self?.syncSelectedTab(webView.url) }
        }
    }

    private func observeDeepLinks() {
        guard settings.nativeDeepLinks else { return }
        let handler: (Notification) -> Void = { [weak self] note in
            guard let self = self,
                  let info = note.object as? [String: Any],
                  let url = info["url"] as? URL,
                  let target = self.settings.resolve(url) else { return }
            if self.settings.isInternal(target) { self.navigate(to: target) }
        }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .capacitorOpenURL, object: nil, queue: .main, using: handler))
        observers.append(center.addObserver(forName: .capacitorOpenUniversalLink, object: nil, queue: .main, using: handler))
    }

    // MARK: - Offline

    /// In banner mode, taps and reloads wait for a connection instead of ending on the error page.
    private func watchConnectivity() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async { self?.online = path.status == .satisfied }
        }
        pathMonitor.start(queue: DispatchQueue(label: "NativeShell.network"))
    }

    private func blockWhileOffline(_ url: String?) -> Bool {
        guard !online, pageShown, settings.offlineMode != "screen" else { return false }
        notifyListeners("offlineBlocked", data: ["url": url ?? NSNull()])
        DispatchQueue.main.async {
            ShellHaptics.play("warning")
            self.refreshControl?.endRefreshing()
        }
        return true
    }

    // MARK: - Navigation

    private func markReady() {
        guard !isReady else { return }
        isReady = true
        if let url = pendingURL {
            pendingURL = nil
            navigate(to: url)
        }
    }

    private func navigate(to url: URL) {
        guard isReady, let webView = webView else {
            pendingURL = url
            return
        }
        webView.load(URLRequest(url: url))
    }

    private func reloadCurrent() {
        guard let webView = webView else { return }
        // Bundled pages live on the phone, so they can always reload.
        let bundled = webView.url?.host == bridge?.config.localURL.host
        if !bundled, blockWhileOffline(nil) { return }
        if let current = webView.url, settings.isInternal(current) || current.host == bridge?.config.localURL.host {
            webView.reload()
        } else {
            navigate(to: lastURL ?? settings.startURL)
        }
    }

    private func syncSelectedTab(_ url: URL?) {
        guard let url = url, let root = rootController, !settings.tabs.isEmpty else { return }
        var best: (index: Int, length: Int)?
        for (index, tab) in settings.tabs.enumerated() where tab.url.host == url.host {
            let path = tab.url.path.isEmpty ? "/" : tab.url.path
            if url.path.hasPrefix(path), path.count > (best?.length ?? -1) {
                best = (index, path.count)
            }
        }
        if let best = best { root.selectTab(best.index) }
    }

    private func open(_ url: URL, mode: String) {
        let scheme = url.scheme?.lowercased() ?? ""
        if mode == "inApp", scheme == "http" || scheme == "https", let presenter = bridge?.viewController {
            let safari = SFSafariViewController(url: url)
            if let tint = settings.accentColor { safari.preferredControlTintColor = tint }
            safari.dismissButtonStyle = .close
            presenter.present(safari, animated: true)
        } else {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    /// Decides where links go: internal pages stay in the web view, external sites open in an
    /// in-app browser (or Safari), and tel:/mailto:/maps etc. go to the system.
    override public func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> NSNumber? {
        guard let url = navigationAction.request.url, let scheme = url.scheme?.lowercased() else { return nil }

        if ["about", "blob", "data", "file", "capacitor", "javascript"].contains(scheme) { return nil }

        if scheme == "http" || scheme == "https" {
            if settings.isInternal(url) {
                let userNavigation = navigationAction.navigationType == .linkActivated || navigationAction.navigationType == .formSubmitted
                if userNavigation, navigationAction.targetFrame?.isMainFrame ?? true, blockWhileOffline(url.absoluteString) { return true }
                return nil
            }
            if let local = bridge?.config.localURL, url.host == local.host { return nil }
            // Embedded content (iframes, players, payment widgets) loads normally.
            guard navigationAction.targetFrame?.isMainFrame ?? true else { return nil }
            if settings.externalLinks == "webview" { return false }
            DispatchQueue.main.async { self.open(url, mode: self.settings.externalLinks) }
            return true
        }

        // tel:, mailto:, sms:, maps:, whatsapp:, app store links…
        if !settings.deepLinkScheme.isEmpty, scheme == settings.deepLinkScheme.lowercased(),
           let target = settings.resolve(url) {
            DispatchQueue.main.async { self.navigate(to: target) }
            return true
        }
        DispatchQueue.main.async { UIApplication.shared.open(url, options: [:], completionHandler: nil) }
        return true
    }

    // MARK: - JS methods: general

    @objc func getConfig(_ call: CAPPluginCall) {
        call.resolve(settings.dictionary)
    }

    @objc func pageReady(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.pageShown = true
            self.markReady()
            if let override = call.getBool("pullToRefresh") {
                self.applyPullToRefresh(override && self.refreshEnabled)
            } else {
                self.applyPullToRefresh(self.refreshEnabled)
            }
            self.syncSelectedTab(self.webView?.url)
            self.videoSplash?.markPageReady()
            call.resolve(["splashDone": self.splashDone])
        }
    }

    @objc func haptic(_ call: CAPPluginCall) {
        let type = call.getString("type") ?? "light"
        DispatchQueue.main.async { ShellHaptics.play(type) }
        call.resolve()
    }

    @objc func vibrate(_ call: CAPPluginCall) {
        DispatchQueue.main.async { ShellHaptics.vibrate() }
        call.resolve()
    }

    @objc func openUrl(_ call: CAPPluginCall) {
        guard let raw = call.getString("url"), let url = settings.resolve(string: raw) else {
            return call.reject("A valid url is required")
        }
        let mode = call.getString("mode") ?? "inApp"
        DispatchQueue.main.async { self.open(url, mode: mode) }
        call.resolve()
    }

    @objc func openSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
        }
        call.resolve()
    }

    @objc func reload(_ call: CAPPluginCall) {
        DispatchQueue.main.async { self.reloadCurrent() }
        call.resolve()
    }

    @objc func retry(_ call: CAPPluginCall) {
        DispatchQueue.main.async { self.navigate(to: self.lastURL ?? self.settings.startURL) }
        call.resolve()
    }

    @objc func keepAwake(_ call: CAPPluginCall) {
        let enabled = call.getBool("enabled") ?? true
        DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = enabled }
        call.resolve()
    }

    @objc func setPullToRefresh(_ call: CAPPluginCall) {
        refreshEnabled = call.getBool("enabled") ?? true
        DispatchQueue.main.async { self.applyPullToRefresh(self.refreshEnabled) }
        call.resolve()
    }

    @objc func setRefreshBlocked(_ call: CAPPluginCall) {
        // Android only: iOS pull-to-refresh only follows the page's main scroll position.
        call.resolve()
    }

    @objc func saveFile(_ call: CAPPluginCall) {
        guard let base64 = call.getString("data"), let data = Data(base64Encoded: base64) else {
            return call.reject("No file data")
        }
        let name = (call.getString("filename") ?? "download").replacingOccurrences(of: "/", with: "_")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            return call.reject("Could not save file", nil, error)
        }
        DispatchQueue.main.async {
            guard let presenter = self.bridge?.viewController else { return call.reject("No view controller") }
            let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let popover = sheet.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.maxY - 40, width: 1, height: 1)
            }
            sheet.completionWithItemsHandler = { _, completed, _, _ in
                call.resolve(["saved": completed, "path": url.path])
            }
            presenter.present(sheet, animated: true)
        }
    }

    @objc func setBadge(_ call: CAPPluginCall) {
        let count = max(0, call.getInt("count") ?? 0)
        applyBadge(count) { applied in
            var result: [String: Any] = ["count": count, "applied": applied]
            if !applied { result["reason"] = "permission" }
            call.resolve(result)
        }
    }

    @objc func getBadge(_ call: CAPPluginCall) {
        call.resolve(["count": UserDefaults.standard.integer(forKey: Self.badgeKey)])
    }

    private static let badgeKey = "NativeShell.badge.count"

    /// Sets the number on the app icon. iOS only shows it if the user allowed notifications (with badges).
    private func applyBadge(_ count: Int, done: ((Bool) -> Void)? = nil) {
        UserDefaults.standard.set(count, forKey: Self.badgeKey)
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let allowed = settings.badgeSetting == .enabled
            DispatchQueue.main.async {
                if #available(iOS 16.0, *) {
                    UNUserNotificationCenter.current().setBadgeCount(count)
                } else {
                    UIApplication.shared.applicationIconBadgeNumber = count
                }
                done?(allowed || count == 0)
            }
        }
    }

    // MARK: - JS methods: tabs

    @objc func selectTab(_ call: CAPPluginCall) {
        let index = call.getInt("index") ?? 0
        DispatchQueue.main.async {
            guard self.settings.tabs.indices.contains(index) else { return }
            self.rootController?.selectTab(index)
            self.navigate(to: self.settings.tabs[index].url)
        }
        call.resolve()
    }

    @objc func setTabBadge(_ call: CAPPluginCall) {
        let index = call.getInt("index") ?? 0
        let value = call.getString("value")
        DispatchQueue.main.async { self.rootController?.setBadge(value, at: index) }
        call.resolve()
    }

    @objc func setTabsVisible(_ call: CAPPluginCall) {
        let visible = call.getBool("visible") ?? true
        DispatchQueue.main.async { self.rootController?.setTabsVisible(visible) }
        call.resolve()
    }

    // MARK: - JS methods: permissions

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        permissionStates(["camera", "microphone", "photos", "location", "notifications"]) { call.resolve($0) }
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        let names = (call.getArray("permissions") as? [String]) ?? ["camera", "microphone"]
        requestSequentially(names) { [weak self] in
            self?.permissionStates(names) { call.resolve($0) }
        }
    }

    private func requestSequentially(_ names: [String], done: @escaping () -> Void) {
        guard let name = names.first else { return DispatchQueue.main.async(execute: done) }
        let next = { self.requestSequentially(Array(names.dropFirst()), done: done) }
        switch name {
        case "camera": AVCaptureDevice.requestAccess(for: .video) { _ in next() }
        case "microphone": AVCaptureDevice.requestAccess(for: .audio) { _ in next() }
        case "photos": PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in next() }
        case "notifications": DispatchQueue.main.async { self.push.requestPermission { _ in next() } }
        case "location":
            DispatchQueue.main.async {
                let manager = self.locationManager ?? CLLocationManager()
                self.locationManager = manager
                manager.delegate = self
                guard manager.authorizationStatus == .notDetermined else { return next() }
                self.locationCallbacks.append(next)
                manager.requestWhenInUseAuthorization()
            }
        default: next()
        }
    }

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager.authorizationStatus != .notDetermined else { return }
        let callbacks = locationCallbacks
        locationCallbacks.removeAll()
        callbacks.forEach { $0() }
    }

    private func permissionStates(_ names: [String], done: @escaping ([String: Any]) -> Void) {
        UNUserNotificationCenter.current().getNotificationSettings { notificationSettings in
            DispatchQueue.main.async {
                var result: [String: Any] = [:]
                for name in names {
                    switch name {
                    case "camera": result[name] = Self.describe(AVCaptureDevice.authorizationStatus(for: .video))
                    case "microphone": result[name] = Self.describe(AVCaptureDevice.authorizationStatus(for: .audio))
                    case "photos":
                        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
                        case .authorized: result[name] = "granted"
                        case .limited: result[name] = "limited"
                        case .denied, .restricted: result[name] = "denied"
                        default: result[name] = "prompt"
                        }
                    case "location":
                        switch CLLocationManager().authorizationStatus {
                        case .authorizedAlways, .authorizedWhenInUse: result[name] = "granted"
                        case .denied, .restricted: result[name] = "denied"
                        default: result[name] = "prompt"
                        }
                    case "notifications": result[name] = ShellPush.describe(notificationSettings.authorizationStatus)
                    default: break
                    }
                }
                done(result)
            }
        }
    }

    private static func describe(_ status: AVAuthorizationStatus) -> String {
        switch status {
        case .authorized: return "granted"
        case .denied, .restricted: return "denied"
        default: return "prompt"
        }
    }

    // MARK: - JS methods: push

    @objc func pushRequestPermission(_ call: CAPPluginCall) {
        DispatchQueue.main.async { self.push.requestPermission { call.resolve(["granted": $0]) } }
    }

    @objc func pushGetState(_ call: CAPPluginCall) {
        push.state { call.resolve($0) }
    }

    @objc func pushLogin(_ call: CAPPluginCall) {
        guard let id = call.getString("externalId"), !id.isEmpty else { return call.reject("externalId is required") }
        push.login(id)
        call.resolve()
    }

    @objc func pushLogout(_ call: CAPPluginCall) {
        push.logout()
        call.resolve()
    }

    @objc func pushAddTags(_ call: CAPPluginCall) {
        var tags: [String: String] = [:]
        for (key, value) in call.getObject("tags") ?? [:] { tags[key] = "\(value)" }
        push.addTags(tags)
        call.resolve()
    }

    @objc func pushRemoveTags(_ call: CAPPluginCall) {
        push.removeTags((call.getArray("keys") as? [String]) ?? [])
        call.resolve()
    }

    @objc func pushSetEmail(_ call: CAPPluginCall) {
        guard let email = call.getString("email"), !email.isEmpty else { return call.reject("email is required") }
        push.setEmail(email)
        call.resolve()
    }

    @objc func pushOptIn(_ call: CAPPluginCall) {
        push.optIn()
        call.resolve()
    }

    @objc func pushOptOut(_ call: CAPPluginCall) {
        push.optOut()
        call.resolve()
    }

    @objc func pushClearAll(_ call: CAPPluginCall) {
        DispatchQueue.main.async { self.push.clearAll() }
        call.resolve()
    }
}

extension ShellSettings {
    static func origin(of url: URL) -> String {
        guard let scheme = url.scheme, let host = url.host else { return "" }
        if let port = url.port { return "\(scheme)://\(host):\(port)" }
        return "\(scheme)://\(host)"
    }
}
