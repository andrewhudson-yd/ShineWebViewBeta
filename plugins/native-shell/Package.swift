// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorNativeShell",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorNativeShell",
            targets: ["NativeShellPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
        .package(url: "https://github.com/OneSignal/OneSignal-XCFramework", "5.2.0"..<"6.0.0")
    ],
    targets: [
        .target(
            name: "NativeShellPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "OneSignalFramework", package: "OneSignal-XCFramework"),
                .product(name: "OneSignalInAppMessages", package: "OneSignal-XCFramework")
            ],
            path: "ios/Sources/NativeShellPlugin")
    ]
)
