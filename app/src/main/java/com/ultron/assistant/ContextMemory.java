package com.ultron.assistant;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * PHASE 4 - Keeps the last 3 (heard text -> spoken response) turns so the LLM prompt
 * can resolve follow-ups like "usko dubara bhejo" (send that again) or "usse zoom out karo"
 * without the user repeating full context every time.
 */
public class ContextMemory {

    public static class Turn {
        public final String heard;
        public final String response;

        Turn(String heard, String response) {
            this.heard = heard;
            this.response = response;
        }
    }

    private static final int MAX_TURNS = 3;
    private final Deque<Turn> turns = new ArrayDeque<>(MAX_TURNS);

    public synchronized void record(String heard, String response) {
        if (turns.size() == MAX_TURNS) {
            turns.removeFirst();
        }
        turns.addLast(new Turn(heard, response));
    }

    /** Renders the buffered turns as compact plain text for folding into the LLM prompt. */
    public synchronized String asPromptContext() {
        if (turns.isEmpty()) return "(no prior context)";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        Iterator<Turn> it = turns.iterator();
        while (it.hasNext()) {
            Turn t = it.next();
            sb.append(i++).append(". User: ").append(t.heard)
                    .append(" | Ultron: ").append(t.response).append('\n');
        }
        return sb.toString();
    }

    public synchronized void clear() {
        turns.clear();
    }
}
