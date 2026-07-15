// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * Thin wrapper around the device's built-in {@link TextToSpeech} engine for spoken bike-navigation
 * guidance. It speaks ENGLISH text using whatever voice data is ALREADY installed on the phone - it
 * never triggers an install / download flow and never prompts the user to fetch voice data.
 *
 * <p>On init it tries {@link Locale#US}; if that voice data is missing/unsupported it falls back to
 * the device's default locale so it still speaks with whatever voice is present. If nothing works it
 * degrades silently to on-screen guidance only ({@link #isReady()} stays {@code false}).</p>
 */
final class TtsHelper {

    private static final String TAG = "lbnav";

    private TextToSpeech tts;
    private volatile boolean ready = false;

    TtsHelper(Context context) {
        try {
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS init failed: " + status);
                    return;
                }
                try {
                    int r = tts.setLanguage(Locale.US);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // Do NOT prompt to install voice data - fall back to whatever is present.
                        int r2 = tts.setLanguage(Locale.getDefault());
                        if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "no usable TTS voice installed; voice guidance disabled");
                            return;
                        }
                    }
                    ready = true;
                } catch (Throwable t) {
                    Log.w(TAG, "TTS setLanguage failed", t);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "TTS construction failed", t);
        }
    }

    /** True once the engine is initialized and a usable voice is set. */
    boolean isReady() {
        return ready && tts != null;
    }

    /** Speak the given English text, flushing anything currently queued. No-op if not ready. */
    void speak(String text) {
        if (!ready || tts == null || text == null || text.isEmpty()) return;
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lbnav");
        } catch (Throwable t) {
            Log.w(TAG, "TTS speak failed", t);
        }
    }

    /** Stop any current utterance immediately (e.g. when voice is toggled off). */
    void stop() {
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    /** Release the engine. Call from the host Activity's {@code onDestroy}. */
    void shutdown() {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
        } finally {
            tts = null;
            ready = false;
        }
    }
}
