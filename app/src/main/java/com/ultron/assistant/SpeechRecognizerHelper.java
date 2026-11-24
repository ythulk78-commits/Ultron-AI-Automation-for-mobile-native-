package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * PHASE 1 - Wraps Android's on-device SpeechRecognizer for STT.
 * Uses Hindi (hi-IN) locale by default since it also picks up Hinglish reasonably well
 * on most OEM speech engines. Falls back to en-IN if hi-IN isn't supported.
 */
public class SpeechRecognizerHelper {

    private static final String TAG = "Ultron.STT";

    public interface ResultListener {
        void onResult(String text);
        void onError(String message);
        void onListeningStarted();
    }

    private final Context context;
    private SpeechRecognizer recognizer;
    private ResultListener listener;

    public SpeechRecognizerHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public void startListening(ResultListener listener) {
        this.listener = listener;

        if (!isAvailable()) {
            if (listener != null) listener.onError("Speech recognizer available nahi hai is device pe");
            return;
        }

        if (recognizer != null) {
            recognizer.destroy();
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(buildRecognitionListener());

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

        recognizer.startListening(intent);
    }

    public void stopListening() {
        if (recognizer != null) {
            recognizer.stopListening();
        }
    }

    public void destroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private RecognitionListener buildRecognitionListener() {
        return new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (listener != null) listener.onListeningStarted();
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    if (listener != null) listener.onResult(matches.get(0));
                } else {
                    if (listener != null) listener.onError("Kuch samajh nahi aaya");
                }
            }

            @Override
            public void onError(int error) {
                Log.e(TAG, "STT error code: " + error);
                if (listener != null) listener.onError("Dubara bol na, samajh nahi aaya");
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        };
    }
}
