package com.ultron.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Long-polls Telegram (TelegramApiClient.getUpdates) on a dedicated background thread and routes
 * authenticated chat text into the exact same UltronCore.handle() pipeline voice and HUD-typed
 * commands go through (see UltronCore's javadoc) - so a command typed in Telegram does exactly,
 * and only, what typing it in the HUD would do. There is no separate "Telegram command set" and
 * no raw shell/root execution path here - it's the same rule-based CommandParser + LLM-fallback +
 * ActionExecutor as everywhere else in the app.
 *
 * Every incoming message is gated through TelegramAuthManager BEFORE it ever reaches UltronCore -
 * see that class's javadoc for the pairing model. Unauthenticated chats only ever see the pairing
 * prompt or silence; they never get a command executed or a command result echoed back.
 */
public class TelegramBridgeService extends Service {

    private static final String TAG = "Ultron.TelegramBridge";
    private static final String CHANNEL_ID = "ultron_telegram_channel";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_STOP = "com.ultron.assistant.action.TELEGRAM_STOP";

    private static final Pattern START_CMD = Pattern.compile("^/start(@\\w+)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGOUT_CMD = Pattern.compile("^/(logout|unbind|reset)$", Pattern.CASE_INSENSITIVE);

    private Thread pollThread;
    private volatile boolean running = false;
    private TelegramApiClient api;
    private TelegramAuthManager authManager;
    private SettingsManager settingsManager;
    private long offset = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        settingsManager = new SettingsManager(this);
        authManager = TelegramAuthManager.get();
        // Shared brain - same instance voice (UltronService) and HUD typing use, so a command
        // sent from Telegram shares conversation context with everything else.
        UltronCore.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Telegram bridge active"));

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;
            settingsManager.setTelegramBridgeEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!settingsManager.hasTelegramBotToken()) {
            Log.w(TAG, "No bot token saved - not starting bridge");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            settingsManager.setTelegramBridgeEnabled(true);
            api = new TelegramApiClient(settingsManager.getTelegramBotToken());
            pollThread = new Thread(this::pollLoop, "Ultron-TelegramPoll");
            pollThread.start();
        }
        return START_STICKY;
    }

    private void pollLoop() {
        while (running) {
            try {
                List<TelegramApiClient.Update> updates = api.getUpdates(offset);
                for (TelegramApiClient.Update u : updates) {
                    offset = u.updateId + 1;
                    if (running) handleUpdate(u);
                }
            } catch (Exception e) {
                // Network blip, DNS hiccup, Telegram hiccup, etc. - back off briefly and keep
                // long-polling; getUpdates() itself already blocks for up to ~25s per call when
                // things ARE working, so this only fires on genuine failures.
                Log.e(TAG, "Poll error, retrying shortly", e);
                sleep(3000);
            }
        }
    }

    private void handleUpdate(TelegramApiClient.Update u) {
        String text = u.text == null ? "" : u.text.trim();
        if (text.isEmpty()) return;
        long chatId = u.chatId;

        if (START_CMD.matcher(text).matches()) {
            if (authManager.isBoundTo(chatId)) {
                api.sendMessage(chatId, "Pehle se connected ho - seedha command likho.");
            } else if (!authManager.isBound()) {
                api.sendMessage(chatId,
                        "\uD83D\uDD12 Ultron secured hai.\n\nPhone par Ultron app kholo -> Ultron Heart -> "
                                + "\"Generate Remote PIN\" dabao, aur wahi 4-digit PIN 5 minute ke andar yahin bhej do.");
            }
            // Bound to someone else already: say nothing - don't even confirm the bot is alive.
            return;
        }

        if (authManager.isBoundTo(chatId)) {
            if (LOGOUT_CMD.matcher(text).matches()) {
                authManager.unbind();
                api.sendMessage(chatId, "\uD83D\uDD13 Disconnect ho gaya. Phone se naya PIN generate karke dobara connect karo.");
                return;
            }
            // The poll loop runs on a plain background thread (no Looper) - hop to main before
            // touching UltronCore, same as every other entry point into it (voice via UltronService,
            // typed via UltronHudActivity, scheduled via ScheduledActionReceiver all run on main).
            mainHandler.post(() ->
                    UltronCore.get().handle(text, false, response -> api.sendMessage(chatId, response)));
            return;
        }

        if (authManager.isBound()) {
            // Some other chat already holds the pairing - stay completely silent for anyone else,
            // including on plausible-looking PIN guesses, so there's nothing to probe against.
            return;
        }

        // Nobody paired yet - this message is a PIN attempt.
        if (authManager.tryBind(chatId, text)) {
            api.sendMessage(chatId,
                    "\u2705 Access granted. Ab yahin se Ultron ko command bhej sakte ho - jo HUD mein type karte ho, wahi yahan bhi chalega.");
        } else if (!authManager.isRateLimited(chatId)) {
            api.sendMessage(chatId, "\u274C Galat ya expired PIN. Phone se naya PIN generate karo.");
        }
        // Rate-limited chats get total silence - no signal that helps narrow down the PIN.
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Ultron Telegram Bridge", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the Telegram remote-control bridge alive in the background");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ultron")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (pollThread != null) pollThread.interrupt();
        Log.d(TAG, "TelegramBridgeService destroyed");
    }
}
