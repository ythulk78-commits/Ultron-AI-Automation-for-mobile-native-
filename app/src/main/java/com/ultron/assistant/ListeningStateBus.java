package com.ultron.assistant;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 8 - Tracks whether Ultron is currently in "continuous listening" mode: mic held open,
 * restarting itself after every utterance, until the user explicitly turns it off (mic button
 * tap, or a spoken "stop listening"/"sunna band karo"). This lives outside both UltronService and
 * UltronHudActivity so the two agree on state no matter which one changes it or which is/isn't
 * alive right now - the service owns the actual mic loop, the HUD (when open) just reflects it.
 */
public class ListeningStateBus {

    public interface Listener {
        void onListeningStateChanged(boolean listening);
    }

    private static volatile boolean listening = false;
    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isListening() {
        return listening;
    }

    public static synchronized void setListening(boolean value) {
        if (listening == value) return;
        listening = value;
        List<Listener> snapshot = new ArrayList<>(listeners);
        mainHandler.post(() -> {
            for (Listener l : snapshot) l.onListeningStateChanged(value);
        });
    }

    public static synchronized void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
