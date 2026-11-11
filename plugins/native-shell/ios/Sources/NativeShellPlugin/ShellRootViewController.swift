import UIKit
import Capacitor

/// Hosts the Capacitor web view and an optional native tab bar underneath it.
/// Set as the window's root in SceneDelegate.swift.
open class ShellRootViewController: UIViewController, UITabBarDelegate {
    public let bridgeViewController: CAPBridgeViewController
    public let tabBar = UITabBar()
    private let stack = UIStackView()
    private var edgeConstraints: [NSLayoutConstraint] = []
    private var safeConstraints: [NSLayoutConstraint] = []

    /// Called when the user taps a tab.
    var onSelectTab: ((Int) -> Void)?

    public init(bridgeViewController: CAPBridgeViewController = CAPBridgeViewController()) {
        self.bridgeViewController = bridgeViewController
        super.init(nibName: nil, bundle: nil)
    }

    public required init?(coder: NSCoder) {
        self.bridgeViewController = CAPBridgeViewController()
        super.init(coder: coder)
    }

    override open func viewDidLoad() {
        super.viewDidLoad()

        stack.axis = .vertical
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        edgeConstraints = [
            stack.topAnchor.constraint(equalTo: view.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ]
        let guide = view.safeAreaLayoutGuide
        safeConstraints = [
            stack.topAnchor.constraint(equalTo: guide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: guide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: guide.trailingAnchor)
        ]
        NSLayoutConstraint.activate(edgeConstraints)

        addChild(bridgeViewController)
        stack.addArrangedSubview(bridgeViewController.view)
        bridgeViewController.didMove(toParent: self)

        tabBar.delegate = self
        tabBar.isHidden = true
        stack.addArrangedSubview(tabBar)
        view.backgroundColor = bridgeViewController.view.backgroundColor
    }

    /// true: keep the page clear of the status bar, notch and home indicator (bars show `background`).
    func useSafeArea(_ enabled: Bool, background: UIColor?) {
        NSLayoutConstraint.deactivate(enabled ? edgeConstraints : safeConstraints)
        NSLayoutConstraint.activate(enabled ? safeConstraints : edgeConstraints)
        if let background = background { view.backgroundColor = background }
    }

    // MARK: - Tabs

    func configureTabs(_ tabs: [ShellSettings.Tab], tint: UIColor?) {
        tabBar.items = tabs.enumerated().map { index, tab in
            UITabBarItem(title: tab.title, image: ShellRootViewController.icon(tab.icon), tag: index)
        }
        if let tint = tint { tabBar.tintColor = tint }
        tabBar.selectedItem = tabBar.items?.first
        tabBar.isHidden = tabs.isEmpty
    }

    func selectTab(_ index: Int) {
        guard let items = tabBar.items, items.indices.contains(index) else { return }
        tabBar.selectedItem = items[index]
    }

    func setBadge(_ value: String?, at index: Int) {
        guard let items = tabBar.items, items.indices.contains(index) else { return }
        items[index].badgeValue = (value?.isEmpty ?? true) ? nil : value
    }

    func setTabsVisible(_ visible: Bool) {
        guard !(tabBar.items?.isEmpty ?? true) else { return }
        UIView.animate(withDuration: 0.2) { self.tabBar.isHidden = !visible }
    }

    public func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) {
        onSelectTab?(item.tag)
    }

    /// Friendly names shared with Android, or any SF Symbol name.
    static func icon(_ name: String) -> UIImage? {
        let symbols = [
            "home": "house", "search": "magnifyingglass", "bell": "bell", "notifications": "bell",
            "user": "person", "profile": "person", "account": "person.crop.circle", "settings": "gearshape",
            "cart": "cart", "bag": "bag", "heart": "heart", "star": "star", "calendar": "calendar",
            "chat": "bubble.left", "messages": "bubble.left.and.bubble.right", "menu": "line.3.horizontal",
            "grid": "square.grid.2x2", "info": "info.circle", "list": "list.bullet", "map": "map",
            "camera": "camera", "plus": "plus.circle", "bookmark": "bookmark", "play": "play.circle"
        ]
        return UIImage(systemName: symbols[name] ?? name) ?? UIImage(systemName: "circle")
    }

    // MARK: - Forward system appearance to the web view controller

    /// Set while the intro video shows; nil hands status bar styling back to the web view controller.
    var statusBarOverride: UIStatusBarStyle? {
        didSet { setNeedsStatusBarAppearanceUpdate() }
    }

    override open var childForStatusBarStyle: UIViewController? { statusBarOverride == nil ? bridgeViewController : nil }
    override open var preferredStatusBarStyle: UIStatusBarStyle { statusBarOverride ?? .default }
    override open var childForStatusBarHidden: UIViewController? { bridgeViewController }
    override open var childForHomeIndicatorAutoHidden: UIViewController? { bridgeViewController }
    override open var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        bridgeViewController.supportedInterfaceOrientations
    }
}
