package com.ultron.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * PHASE 7 (REWRITE #2) - "Ultron" / "Mark 9" / "Jarvis" hotword.
 *
 * REWRITE #1 (previous version of this file) replaced openWakeWord's raw AudioRecord + ONNX/TFLite
 * pipeline with a tight restart loop of Android's SpeechRecognizer (start -> short silence timeout
 * -> restart, every ~1.5s). That fixed the chaotic *two different mic clients colliding* flicker,
 * but it still flickered - because every single one of those ~1.5s restart cycles is a brand new
 * OS-level audio capture session, and the status bar mic indicator toggles on every session
 * boundary. Doing that every 1.5s just looks like constant flicker even with only one mic owner.
 *
 * REWRITE #2 (this version) stops restarting a session on a timer at all:
 *  1. A single AudioRecord opens ONCE and stays open, continuously running simple on-device
 *     energy-based voice-activity-detection (VAD). No session start/stop while idle -> the OS mic
 *     indicator goes steady instead of blinking every 1.5s.
 *  2. Only when the VAD thread notices someone is actually talking does it hand off - just once -
 *     to Android's SpeechRecognizer for a single-shot transcription, check it against the
 *     "Ultron" / "Mark 9" / "Jarvis" pattern, then go straight back to step 1. That's the only
 *     point where a second session boundary happens, and it only happens when something was
 *     actually said, not on a fixed timer.
 *
 * Trade-off: this is a crude energy VAD, not a real speech model, so it triggers STT on any loud
 * sustained sound (including non-hotword talking nearby) - but that's a rare, real event, not a
 * constant 1.5s poll, so the mic indicator stays calm.
 */
public class HotwordDetector {

    private static final String TAG = "Ultron.Hotword";

    private static final Pattern HOTWORD_PATTERN = Pattern.compile(
            "\\b(ultron|altron|ultran|alteron|ultrun)\\b" +
                    "|\\bmark\\s*-?\\s*9\\b" +
                    "|\\bmark\\s*-?\\s*nine\\b" +
                    "|\\bmark\\s*-?\\s*nau\\b" +
                    "|\\bjarvis\\b",
            Pattern.CASE_INSENSITIVE);

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_MS = 20;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000; // 320 samples
    // Consecutive loud chunks needed before bothering to spin up STT (~300ms of real talking).
    private static final int LOUD_CHUNKS_TO_TRIGGER = 15;
    // RMS threshold - conservative; normal room ambience should sit well below this.
    private static final double RMS_THRESHOLD = 1200.0;
    private static final long DEBOUNCE_MS = 2500;

    public interface TriggerListener {
        void onWakeWordDetected();
    }

    private final Context context;
    private final TriggerListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile Thread vadThread;
    private volatile AudioRecord audioRecord;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private SpeechRecognizer recognizer;
    private long lastTriggerTime = 0;

    public HotwordDetector(Context context, TriggerListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isAvailable() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && SpeechRecognizer.isRecognitionAvailable(context);
    }

    /** Arms the hotword loop. Safe to call repeatedly. */
    public void start() {
        if (running) return;
        if (!isAvailable()) {
            Log.w(TAG, "RECORD_AUDIO not granted or recognizer unavailable - hotword stays off");
            return;
        }
        running = true;
        paused = false;
        startVadThread();
        Log.d(TAG, "Hotword VAD loop started (listening for Ultron / Mark 9 / Jarvis)");
    }

    /** Fully tears the loop down. */
    public void stop() {
        running = false;
        stopVadThread();
        mainHandler.post(this::teardownRecognizer);
    }

    /** Free the mic while the real command listener needs it. */
    public void pause() {
        if (paused) return;
        paused = true;
        stopVadThread();
        mainHandler.post(this::teardownRecognizer);
    }

    /** Re-arm listening once the mic is free again. */
    public void resume() {
        if (!paused) return;
        paused = false;
        if (running) startVadThread();
    }

    // ---------------- VAD loop (background thread, single continuous AudioRecord) ----------------

    private void startVadThread() {
        Thread existing = vadThread;
        if (existing != null && existing.isAlive()) return;
        Thread t = new Thread(this::vadLoop, "Ultron-HotwordVAD");
        vadThread = t;
        t.start();
    }

    private void stopVadThread() {
        Thread t = vadThread;
        vadThread = null;
        if (t != null) t.interrupt();
        releaseAudioRecord();
    }

    private void releaseAudioRecord() {
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try {
                if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop();
            } catch (Exception ignored) {}
            try { ar.release(); } catch (Exception ignored) {}
        }
    }

    private void vadLoop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = CHUNK_SAMPLES * 2;
        int bufferSize = Math.max(minBuf, CHUNK_SAMPLES * 2 * 4);

        AudioRecord localRecord;
        try {
            localRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (localRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to init for hotword VAD");
                localRecord.release();
                return;
            }
            audioRecord = localRecord;
            localRecord.startRecording();
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Couldn't open mic for hotword VAD", e);
            releaseAudioRecord();
            return;
        }

        short[] chunk = new short[CHUNK_SAMPLES];
        int loudStreak = 0;

        while (running && !paused && !Thread.currentThread().isInterrupted() && audioRecord != null) {
            int read;
            try {
                read = localRecord.read(chunk, 0, chunk.length);
            } catch (Exception e) {
                break;
            }
            if (read <= 0) continue;

            double sumSquares = 0;
            for (int i = 0; i < read; i++) sumSquares += (double) chunk[i] * chunk[i];
            double rms = Math.sqrt(sumSquares / read);

            if (rms > RMS_THRESHOLD) {
                loudStreak++;
            } else {
                loudStreak = 0;
            }

            if (loudStreak >= LOUD_CHUNKS_TO_TRIGGER) {
                // Hand off to STT: release this AudioRecord and run one single-shot recognition
                // on the main thread. That call restarts a fresh VAD thread once it's done.
                releaseAudioRecord();
                mainHandler.post(this::runOneShotSpeechCheck);
                return;
            }
        }

        releaseAudioRecord();
    }

    // ---------------- one-shot STT check (main thread, only runs when VAD heard real speech) ----------------

    private void runOneShotSpeechCheck() {
        if (!running || paused) {
            backToVad();
            return;
        }
        teardownRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                teardownRecognizer();
                if (checkForHotword(candidates)) {
                    fireTrigger();
                } else {
                    backToVad();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (checkForHotword(partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))) {
                    teardownRecognizer();
                    fireTrigger();
                }
            }

            @Override
            public void onError(int error) {
                teardownRecognizer();
                backToVad();
            }

            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 900);
        intent.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 900);

        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "One-shot hotword STT failed to start", e);
            teardownRecognizer();
            backToVad();
        }
    }

    private void backToVad() {
        if (running && !paused) startVadThread();
    }

    private void teardownRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private boolean checkForHotword(ArrayList<String> candidates) {
        if (candidates == null) return false;
        for (String phrase : candidates) {
            if (phrase != null && HOTWORD_PATTERN.matcher(phrase).find()) return true;
        }
        return false;
    }

    private void fireTrigger() {
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < DEBOUNCE_MS) {
            backToVad();
            return;
        }
        lastTriggerTime = now;
        Log.d(TAG, "Hotword matched - waking Ultron");
        if (listener != null) listener.onWakeWordDetected();
        // Deliberately not calling backToVad() here - UltronService.wake() immediately calls
        // pause() on this detector as it takes over the mic for the real command, so re-arming
        // the VAD here would just get torn down again a moment later.
    }
}
