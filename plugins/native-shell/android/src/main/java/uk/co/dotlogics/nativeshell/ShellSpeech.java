package uk.co.dotlogics.nativeshell;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * Native dictation with Android's speech recogniser. Web views can't use the Web Speech API on
 * Android, so pages call NativeApp.speech.start() and receive results as they're spoken.
 *
 * Error names match the ones the Flutter speech_to_text plugin used (error_no_match, error_client…)
 * so websites written against that keep working.
 */
class ShellSpeech {

    interface Listener {
        void onSpeechResult(String text, boolean isFinal);
        void onSpeechLevel(float level);
        void onSpeechStopped();
        void onSpeechError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recogniser;
    private boolean listening = false;
    private long lastLevelAt = 0;

    ShellSpeech(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean available() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    boolean isListening() {
        return listening;
    }

    /** Starts listening; calling it while already listening stops instead (tap-to-toggle). */
    void start(String locale, int pauseForMs) {
        main.post(() -> {
            if (listening) {
                stop();
                return;
            }
            if (!available()) {
                listener.onSpeechError("Speech recognition isn't available on this phone.");
                return;
            }
            if (recogniser == null) {
                recogniser = SpeechRecognizer.createSpeechRecognizer(context);
                recogniser.setRecognitionListener(recognitionListener);
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseForMs);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseForMs);
            listening = true;
            try {
                recogniser.startListening(intent);
            } catch (Exception e) {
                listening = false;
                listener.onSpeechError("error_client");
            }
        });
    }

    void stop() {
        main.post(() -> {
            boolean was = listening;
            listening = false;
            if (recogniser != null) {
                try { recogniser.stopListening(); } catch (Exception ignored) { }
            }
            if (was) listener.onSpeechStopped();
        });
    }

    void destroy() {
        main.post(() -> {
            listening = false;
            if (recogniser != null) {
                try { recogniser.destroy(); } catch (Exception ignored) { }
                recogniser = null;
            }
        });
    }

    private static String best(Bundle results) {
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return matches == null || matches.isEmpty() ? "" : matches.get(0).trim();
    }

    private static String errorName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "error_no_match";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "error_speech_timeout";
            case SpeechRecognizer.ERROR_CLIENT: return "error_client";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "error_recognizer_busy";
            case SpeechRecognizer.ERROR_AUDIO: return "error_audio";
            case SpeechRecognizer.ERROR_NETWORK: return "error_network";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "error_network_timeout";
            case SpeechRecognizer.ERROR_SERVER: return "error_server";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "error_permission";
            default: return "error_unknown (" + code + ")";
        }
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onRmsChanged(float rmsdB) {
            long now = SystemClock.uptimeMillis();
            if (!listening || now - lastLevelAt < 80) return; // ~12 updates a second is plenty for a waveform
            lastLevelAt = now;
            listener.onSpeechLevel(rmsdB);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = best(partialResults);
            if (listening && !text.isEmpty()) listener.onSpeechResult(text, false);
        }

        @Override
        public void onResults(Bundle results) {
            String text = best(results);
            boolean was = listening;
            listening = false;
            if (!text.isEmpty()) listener.onSpeechResult(text, true);
            if (was || !text.isEmpty()) listener.onSpeechStopped();
        }

        @Override
        public void onError(int error) {
            // Some recognisers report ERROR_CLIENT after a deliberate stop; that's not worth showing.
            if (!listening && error == SpeechRecognizer.ERROR_CLIENT) return;
            listening = false;
            listener.onSpeechError(errorName(error));
        }
    };
}
