package com.ultron.assistant;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;

/**
 * PHASE 1 - Wraps Android TextToSpeech. Tries Hindi first (for Hinglish replies),
 * falls back to English if Hindi TTS data isn't installed on the device.
 */
public class TTSManager {

    private static final String TAG = "Ultron.TTS";

    public interface SpeakListener {
        void onDone();
    }

    private final Context context;
    private TextToSpeech tts;
    private boolean ready = false;

    public TTSManager(Context context) {
        this.context = context.getApplicationContext();
        init();
    }

    private void init() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("hi", "IN"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Hindi TTS not available, falling back to English");
                    tts.setLanguage(Locale.US);
                }
                ready = true;
            } else {
                Log.e(TAG, "TTS init failed: " + status);
            }
        });
    }

    public void speak(String text) {
        speak(text, null);
    }

    public void speak(String text, SpeakListener listener) {
        if (!ready || tts == null) {
            Log.w(TAG, "TTS not ready yet, dropping utterance: " + text);
            if (listener != null) listener.onDone();
            return;
        }
        String utteranceId = "ultron_" + System.currentTimeMillis();

        if (listener != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) { listener.onDone(); }
                @Override public void onError(String utteranceId) { listener.onDone(); }
            });
        }

        HashMap<String, String> params = new HashMap<>();
        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
    }

    public void stop() {
        if (tts != null) tts.stop();
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
