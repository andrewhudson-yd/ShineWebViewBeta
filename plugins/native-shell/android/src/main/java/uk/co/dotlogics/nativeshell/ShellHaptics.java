package uk.co.dotlogics.nativeshell;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Haptics tuned to feel close to iOS on every kind of Android motor. Types match NativeApp.haptic().
 *
 * 1. Phones with a modern actuator (Pixel, Galaxy S): composition primitives — crisp clicks and ticks.
 * 2. Phones reporting predefined effects: those effects.
 * 3. Budget phones (basic motor, no effect support — e.g. Galaxy A0x): hand-tuned pulses. The system's
 *    own fallback for "effects" on these is ~30 ms, which a cheap motor barely spins up for, so every
 *    type felt the same and faint. Pulses here are long enough to be felt and differ per type.
 *
 * intensity ("subtle" | "normal" | "strong", from app.config.json → haptics.intensity) scales tier 3.
 */
class ShellHaptics {

    private final Vibrator vibrator;
    private final float scale;

    ShellHaptics(Context context, String intensity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        scale = "subtle".equals(intensity) ? 0.7f : "strong".equals(intensity) ? 1.4f : 1f;
    }

    void play(String type) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (type == null) type = "light";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playComposition(type)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && playPredefined(type)) return;
        playPulses(type);
    }

    void vibrate(long ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(ms);
        }
    }

    // Tier 1

    private boolean playComposition(String type) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        int tick = VibrationEffect.Composition.PRIMITIVE_TICK;
        int click = VibrationEffect.Composition.PRIMITIVE_CLICK;
        int lowTick = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? VibrationEffect.Composition.PRIMITIVE_LOW_TICK : tick;
        int thud = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? VibrationEffect.Composition.PRIMITIVE_THUD : click;

        VibrationEffect.Composition c = VibrationEffect.startComposition();
        int[] used;
        switch (type) {
            case "selection":
                c.addPrimitive(tick, 0.5f);
                used = new int[] { tick };
                break;
            case "soft":
                c.addPrimitive(lowTick, 1f);
                used = new int[] { lowTick };
                break;
            case "medium":
                c.addPrimitive(click, 0.8f);
                used = new int[] { click };
                break;
            case "heavy":
                c.addPrimitive(click, 1f).addPrimitive(lowTick, 1f, 10);
                used = new int[] { click, lowTick };
                break;
            case "rigid":
                c.addPrimitive(tick, 1f);
                used = new int[] { tick };
                break;
            case "success":
                c.addPrimitive(click, 0.7f).addPrimitive(click, 1f, 90);
                used = new int[] { click };
                break;
            case "warning":
                c.addPrimitive(click, 1f).addPrimitive(click, 0.6f, 140);
                used = new int[] { click };
                break;
            case "error":
                c.addPrimitive(click, 1f).addPrimitive(click, 1f, 70).addPrimitive(thud, 1f, 70);
                used = new int[] { click, thud };
                break;
            case "light":
            default:
                c.addPrimitive(tick, 0.9f);
                used = new int[] { tick };
                break;
        }
        if (!vibrator.areAllPrimitivesSupported(used)) return false;
        vibrator.vibrate(c.compose());
        return true;
    }

    // Tier 2 — only when the hardware says it really supports the effect (otherwise Android
    // substitutes a short generic buzz).

    private boolean playPredefined(String type) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        int effect;
        switch (type) {
            case "selection":
            case "light":
            case "soft":
            case "rigid":
                effect = VibrationEffect.EFFECT_TICK;
                break;
            case "heavy":
            case "error":
                effect = VibrationEffect.EFFECT_HEAVY_CLICK;
                break;
            case "success":
            case "warning":
                effect = VibrationEffect.EFFECT_DOUBLE_CLICK;
                break;
            case "medium":
            default:
                effect = VibrationEffect.EFFECT_CLICK;
                break;
        }
        if (vibrator.areAllEffectsSupported(effect) != Vibrator.VIBRATION_EFFECT_SUPPORT_YES) return false;
        vibrator.vibrate(VibrationEffect.createPredefined(effect));
        return true;
    }

    // Tier 3 — pulses: {off, on, off, on…} in ms, with matching amplitudes where the motor supports them.

    private void playPulses(String type) {
        long[] timings;
        int[] amps;
        switch (type) {
            case "selection":
                timings = new long[] { 0, 28 };
                amps = new int[] { 0, 150 };
                break;
            case "soft":
                timings = new long[] { 0, 40 };
                amps = new int[] { 0, 110 };
                break;
            case "rigid":
                timings = new long[] { 0, 38 };
                amps = new int[] { 0, 255 };
                break;
            case "medium":
                timings = new long[] { 0, 60 };
                amps = new int[] { 0, 200 };
                break;
            case "heavy":
                timings = new long[] { 0, 90 };
                amps = new int[] { 0, 255 };
                break;
            case "success":
                timings = new long[] { 0, 45, 90, 75 };
                amps = new int[] { 0, 170, 0, 255 };
                break;
            case "warning":
                timings = new long[] { 0, 80, 130, 45 };
                amps = new int[] { 0, 255, 0, 160 };
                break;
            case "error":
                timings = new long[] { 0, 60, 60, 60, 60, 110 };
                amps = new int[] { 0, 255, 0, 255, 0, 255 };
                break;
            case "light":
            default:
                timings = new long[] { 0, 40 };
                amps = new int[] { 0, 150 };
                break;
        }
        for (int i = 1; i < timings.length; i += 2) timings[i] = Math.max(15, Math.round(timings[i] * scale));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1));
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
            }
        } else {
            vibrator.vibrate(timings, -1);
        }
    }
}
