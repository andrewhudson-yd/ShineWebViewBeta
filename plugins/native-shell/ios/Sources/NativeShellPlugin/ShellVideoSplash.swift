import UIKit
import AVFoundation

/// Full-screen, muted intro video shown over the web view while the site loads.
///
/// Fades away once the video has finished AND the page has loaded (or after maxWait), holding the
/// last frame if the page is slow. Tap to skip if skippable. Skipped when Reduce Motion is on.
/// Uses the "ambient" audio session so it never stops the user's music.
final class ShellVideoSplash: UIView {
    struct Options {
        var url: URL
        var background: UIColor
        var skippable: Bool
        var minDuration: TimeInterval
        var maxWait: TimeInterval
    }

    var onDone: (() -> Void)?

    private let options: Options
    private let player: AVPlayer
    private let playerLayer = AVPlayerLayer()
    private var startedAt = Date()
    private var videoEnded = false
    private var pageReady = false
    private var finished = false
    private var previousAudioCategory: AVAudioSession.Category?
    private var observers: [NSObjectProtocol] = []
    private var readyObservation: NSKeyValueObservation?

    /// Returns nil when there's nothing to show (no file, Reduce Motion, or already played once).
    static func make(options: Options, playMode: String) -> ShellVideoSplash? {
        guard FileManager.default.fileExists(atPath: options.url.path), !UIAccessibility.isReduceMotionEnabled else { return nil }
        if playMode == "firstLaunch" {
            let key = "NativeShell.splash.played"
            if UserDefaults.standard.bool(forKey: key) { return nil }
            UserDefaults.standard.set(true, forKey: key)
        }
        return ShellVideoSplash(options: options)
    }

    private init(options: Options) {
        self.options = options
        self.player = AVPlayer(url: options.url)
        super.init(frame: .zero)

        backgroundColor = options.background
        player.isMuted = true
        player.actionAtItemEnd = .pause // keep the last frame on screen
        player.preventsDisplaySleepDuringVideoPlayback = false
        playerLayer.player = player
        playerLayer.videoGravity = .resizeAspectFill
        playerLayer.opacity = 0 // revealed on the first frame (no black flash)
        layer.addSublayer(playerLayer)

        readyObservation = playerLayer.observe(\.isReadyForDisplay, options: [.new]) { [weak self] layer, _ in
            guard layer.isReadyForDisplay else { return }
            DispatchQueue.main.async { self?.playerLayer.opacity = 1 }
        }
        observers.append(NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: player.currentItem, queue: .main) { [weak self] _ in
            self?.videoEnded = true
            self?.maybeFinish()
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: player.currentItem, queue: .main) { [weak self] _ in
            self?.videoEnded = true
            self?.maybeFinish()
        })

        if options.skippable {
            addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(skip)))
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    func show(in container: UIView) {
        frame = container.bounds
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(self)

        let session = AVAudioSession.sharedInstance()
        previousAudioCategory = session.category
        try? session.setCategory(.ambient, options: [.mixWithOthers])

        startedAt = Date()
        player.play()
        DispatchQueue.main.asyncAfter(deadline: .now() + options.maxWait) { [weak self] in self?.finish() }
    }

    func markPageReady() {
        pageReady = true
        maybeFinish()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }

    @objc private func skip() { finish() }

    private func maybeFinish() {
        guard !finished, videoEnded else { return }
        let elapsed = Date().timeIntervalSince(startedAt)
        if !pageReady && elapsed < options.maxWait { return } // hold the last frame
        if elapsed < options.minDuration {
            DispatchQueue.main.asyncAfter(deadline: .now() + (options.minDuration - elapsed)) { [weak self] in self?.maybeFinish() }
            return
        }
        finish()
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        UIView.animate(withDuration: 0.4, delay: 0, options: [.curveEaseOut], animations: {
            self.alpha = 0
        }, completion: { _ in
            self.player.pause()
            self.removeFromSuperview()
            if let category = self.previousAudioCategory {
                try? AVAudioSession.sharedInstance().setCategory(category)
            }
            self.onDone?()
        })
    }
}
