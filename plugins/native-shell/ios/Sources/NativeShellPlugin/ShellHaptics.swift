import UIKit
import AudioToolbox

/// Taptic Engine feedback. Types match NativeApp.haptic() in native-shell.js.
enum ShellHaptics {
    static func play(_ type: String) {
        switch type {
        case "selection":
            let g = UISelectionFeedbackGenerator()
            g.prepare()
            g.selectionChanged()
        case "success", "warning", "error":
            let g = UINotificationFeedbackGenerator()
            g.prepare()
            g.notificationOccurred(type == "success" ? .success : type == "warning" ? .warning : .error)
        default:
            let style: UIImpactFeedbackGenerator.FeedbackStyle
            switch type {
            case "medium": style = .medium
            case "heavy": style = .heavy
            case "soft": style = .soft
            case "rigid": style = .rigid
            default: style = .light
            }
            let g = UIImpactFeedbackGenerator(style: style)
            g.prepare()
            g.impactOccurred()
        }
    }

    /// iOS has no timed vibration; the system vibrate is the closest match.
    static func vibrate() {
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }
}
