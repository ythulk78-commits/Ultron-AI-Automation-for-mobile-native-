package com.ultron.assistant;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Whatever channel a turn comes in on - spoken (UltronService, via wake word) or typed
 * (UltronHudActivity's chat box) - it's posted here so BOTH the HUD's conversation log and
 * any other listener always show the exact same combined history. Keeps a rolling buffer so
 * the HUD activity can replay recent turns if it's opened mid-conversation.
 */
public class ConversationBus {

    public enum Speaker { USER, ULTRON }

    public static class Message {
        public final Speaker speaker;
        public final String text;
        public final long timestamp;

        Message(Speaker speaker, String text) {
            this.speaker = speaker;
            this.text = text;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public interface Listener {
        void onMessage(Message message);
    }

    private static final int MAX_BUFFER = 50;
    private static final List<Message> buffer = new ArrayList<>();
    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized void post(Speaker speaker, String text) {
        if (text == null || text.trim().isEmpty()) return;
        Message message = new Message(speaker, text);
        buffer.add(message);
        if (buffer.size() > MAX_BUFFER) buffer.remove(0);

        List<Listener> snapshot = new ArrayList<>(listeners);
        mainHandler.post(() -> {
            for (Listener l : snapshot) l.onMessage(message);
        });
    }

    public static synchronized void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Snapshot of the recent history, for a freshly opened HUD to replay before live-listening. */
    public static synchronized List<Message> history() {
        return new ArrayList<>(buffer);
    }
}
