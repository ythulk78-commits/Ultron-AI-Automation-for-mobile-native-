package com.ultron.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 1 - Primary wake trigger without needing Accessibility/root.
 * Android does not expose a "long press volume key" broadcast, so instead we watch the
 * system's VOLUME_CHANGED_ACTION broadcast and detect a rapid TRIPLE press of Volume Up
 * within a short time window - press it 3 times quickly to wake Ultron.
 *
 * NOTE: Once the Phase 3 AccessibilityService is enabled, it can additionally intercept
 * raw key events for a true "long press" trigger (see UltronAccessibilityService.onKeyEvent).
 * This class remains as the no-accessibility-required fallback.
 */
public class VolumeKeyDetector {

    private static final String TAG = "Ultron.VolumeTrigger";
    private static final int REQUIRED_PRESSES = 3;
    private static final long WINDOW_MS = 1500;

    public interface TriggerListener {
        void onWakeTriggered();
    }

    private final Context context;
    private final AudioManager audioManager;
    private final TriggerListener listener;
    private final List<Long> pressTimestamps = new ArrayList<>();
    private int lastVolume = -1;
    private BroadcastReceiver receiver;
    private boolean registered = false;

    public VolumeKeyDetector(Context context, TriggerListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    public void register() {
        if (registered) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!"android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) return;
                handleVolumeChanged();
            }
        };
        context.registerReceiver(receiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        registered = true;
    }

    public void unregister() {
        if (registered && receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {}
            registered = false;
        }
    }

    private void handleVolumeChanged() {
        long now = System.currentTimeMillis();
        pressTimestamps.add(now);

        // Drop timestamps outside the window
        while (!pressTimestamps.isEmpty() && now - pressTimestamps.get(0) > WINDOW_MS) {
            pressTimestamps.remove(0);
        }

        if (pressTimestamps.size() >= REQUIRED_PRESSES) {
            pressTimestamps.clear();
            Log.d(TAG, "Triple volume press detected -> waking Ultron");
            if (listener != null) listener.onWakeTriggered();
        }
    }
}
