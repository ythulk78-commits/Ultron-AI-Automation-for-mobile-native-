package com.ultron.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.regex.Pattern;

/**
 * PHASE 1 (+ HUD update) - The persistent ForegroundService that keeps Ultron alive in the
 * background. Ties together: wake triggers (volume/shake/"Ultron"/"Mark 9" hotword) -> full-screen
 * HUD + overlay -> STT -> UltronCore (shared with the HUD's typed chat) -> TTS. MainActivity just
 * starts/stops it.
 *
 * All actual command handling now lives in UltronCore so the exact same pipeline runs whether
 * the trigger was spoken (here) or typed (UltronHudActivity) - this file just owns the mic
 * triggers, the foreground notification, and popping the HUD open.
 */
public class UltronService extends Service {

    private static final String TAG = "Ultron.Service";
    private static final String CHANNEL_ID = "ultron_foreground_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_WAKE = "com.ultron.assistant.action.WAKE";
    public static final String ACTION_STOP = "com.ultron.assistant.action.STOP";
    public static final String ACTION_EXECUTE_TEXT = "com.ultron.assistant.action.EXECUTE_TEXT";

    // PHASE 8: "stop listening" spoken while in continuous mode turns it off immediately instead
    // of being routed to the AI brain like a normal command (Hindi/Hinglish + English phrasing).
    private static final Pattern STOP_LISTENING_PHRASE = Pattern.compile(
            "\\b(stop listening|listening band karo|sunna band karo|suno mat|sunna band|chup ho ja|chup ho jao)\\b",
            Pattern.CASE_INSENSITIVE);

    private VolumeKeyDetector volumeKeyDetector;
    private ShakeDetector shakeDetector;
    private HotwordDetector hotwordDetector;
    private OverlayManager overlayManager;
    private SpeechRecognizerHelper speechHelper;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isListening = false;

    // PHASE 7: lets UltronHudActivity pause/resume the wake-word mic capture around its own
    // SpeechRecognizer session without needing a bound-service connection - see pauseWakeWord()/
    // resumeWakeWord() below.
    private static UltronService activeInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        activeInstance = this;

        // Shared brain - same instance UltronHudActivity's typed chat uses, so voice and text
        // share conversation context and never diverge in behaviour.
        UltronCore.get();
        overlayManager = new OverlayManager(this);

        speechHelper = new SpeechRecognizerHelper(this);

        volumeKeyDetector = new VolumeKeyDetector(this, this::wake);
        volumeKeyDetector.register();

        shakeDetector = new ShakeDetector(this, this::wake);
        shakeDetector.start();

        // "Ultron" / "Mark 9" hotword - see HotwordDetector. Uses Android's own on-device
        // SpeechRecognizer in a restart loop instead of the old openWakeWord model pipeline.
        hotwordDetector = new HotwordDetector(this, this::wake);
        hotwordDetector.start();

        Log.d(TAG, "UltronService created - triggers armed");
    }

    /** Called by UltronHudActivity right before it starts listening, so the two mics don't fight. */
    public static void pauseWakeWord() {
        if (activeInstance != null && activeInstance.hotwordDetector != null) {
            activeInstance.hotwordDetector.pause();
        }
    }

    /** Called by UltronHudActivity once its own SpeechRecognizer session is done. */
    public static void resumeWakeWord() {
        if (activeInstance != null && activeInstance.hotwordDetector != null) {
            activeInstance.hotwordDetector.resume();
        }
    }

    /**
     * PHASE 8 - "Listen until I say stop": starts (or is a no-op if already running) a loop that
     * re-arms the SpeechRecognizer after every result/error, instead of the old one-shot "listen
     * once then go silent" behaviour. Lives here (not in UltronHudActivity) so it keeps running
     * in the background even if the user closes the HUD - only an explicit stop (mic tap again,
     * or saying "stop listening") turns it off.
     */
    public static void startContinuousListening() {
        if (activeInstance != null) activeInstance.beginListeningLoop();
    }

    /** Explicit stop: mic tap while already listening, or the spoken "stop listening" phrase. */
    public static void stopContinuousListening() {
        if (activeInstance != null) activeInstance.endListeningLoop();
    }

    public static boolean isListeningNow() {
        return ListeningStateBus.isListening();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Sun raha hoon..."));

        if (intent != null && ACTION_WAKE.equals(intent.getAction())) {
            wake();
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        } else if (intent != null && ACTION_EXECUTE_TEXT.equals(intent.getAction())) {
            // Phase 6: a scheduled action firing - skip the wake word + STT + HUD entirely and
            // go straight to routing (no need to interrupt the user for a background action).
            String text = intent.getStringExtra(ScheduledActionReceiver.EXTRA_COMMAND_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                UltronCore.get().handle(text, true, response -> updateNotification("Sun raha hoon..."));
            }
        }
        return START_STICKY;
    }

    /**
     * Wake word / volume-hold / shake fired: pop the full-screen HUD (its circular-reveal
     * animation IS the "listening" indicator now) and start listening. Holding SYSTEM_ALERT_WINDOW
     * lets this Activity launch land even while Ultron isn't the foreground app (documented
     * background-activity-start exemption for that permission).
     *
     * PHASE 8: waking now defaults straight into continuous listening (mic stays open, turn after
     * turn) rather than a single one-shot capture - matches "jab bhi wake ho, listening on hi rahe"
     * ; the user turns it off explicitly (mic tap or "stop listening") whenever they're done.
     */
    private void wake() {
        if (isListening) return;
        isListening = true;

        Intent hudIntent = new Intent(this, UltronHudActivity.class);
        hudIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        hudIntent.putExtra(UltronHudActivity.EXTRA_AUTO_LISTEN, true);
        startActivity(hudIntent);

        beginListeningLoop();
        isListening = false; // that flag was just a debounce for this wake() call itself
    }

    /**
     * PHASE 8 - the actual continuous-listening loop. Owns the mic directly (not through the HUD)
     * so it survives the HUD being closed: startListening() -> onResult/onError -> (if still on)
     * startListening() again, forever, until endListeningLoop() is called. The wake-word engine
     * stays paused for the whole stretch since only one thing can hold the mic at a time.
     */
    private void beginListeningLoop() {
        if (ListeningStateBus.isListening()) return; // already running, nothing to do
        ListeningStateBus.setListening(true);
        if (hotwordDetector != null) hotwordDetector.pause();
        listenOnce();
    }

    private void endListeningLoop() {
        ListeningStateBus.setListening(false);
        if (speechHelper != null) speechHelper.stopListening();
        if (hotwordDetector != null) hotwordDetector.resume();
        updateNotification("Sun raha hoon...");
    }

    private void listenOnce() {
        if (!ListeningStateBus.isListening()) return; // stopped while we were queued up to restart

        speechHelper.startListening(new SpeechRecognizerHelper.ResultListener() {
            @Override
            public void onListeningStarted() {
                updateNotification("Sun raha hoon...");
            }

            @Override
            public void onResult(String text) {
                if (!ListeningStateBus.isListening()) return; // stopped mid-utterance

                if (STOP_LISTENING_PHRASE.matcher(text).find()) {
                    endListeningLoop();
                    UltronCore.get().getTtsManager().speak("Theek hai, sunna band kar raha hoon");
                    return;
                }

                updateNotification("Samajh raha hoon...");
                UltronCore.get().handle(text, true, response -> {
                    // Fires only after TTS finishes speaking (see UltronCore.handle) so Ultron
                    // never re-opens the mic while it's still talking over itself.
                    if (ListeningStateBus.isListening()) {
                        mainHandler.postDelayed(UltronService.this::listenOnce, 250);
                    }
                });
            }

            @Override
            public void onError(String message) {
                // Don't treat "didn't catch that" as a reason to stop - that's the whole point of
                // continuous mode. Just briefly pause (avoids a tight restart loop on devices that
                // error instantly with no mic input) and try again.
                if (ListeningStateBus.isListening()) {
                    mainHandler.postDelayed(UltronService.this::listenOnce, 700);
                }
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Ultron Assistant", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ultron background listening service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText) {
        Intent stopIntent = new Intent(this, UltronService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_IMMUTABLE
                        : 0);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ultron")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .addAction(0, "Stop", stopPendingIntent)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(contentText));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        ListeningStateBus.setListening(false);
        if (speechHelper != null) speechHelper.destroy();
        volumeKeyDetector.unregister();
        shakeDetector.stop();
        if (hotwordDetector != null) hotwordDetector.stop();
        overlayManager.hide();
        if (activeInstance == this) activeInstance = null;
        Log.d(TAG, "UltronService destroyed");
    }
}
