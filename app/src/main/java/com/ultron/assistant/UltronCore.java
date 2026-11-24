package com.ultron.assistant;

import android.content.Context;

/**
 * The single shared brain: one HybridCommandRouter, one ActionExecutor, one ContextMemory,
 * one TTSManager - no matter whether the command came in spoken (UltronService after wake word)
 * or typed (UltronHudActivity's chat box). This is what makes "jo kaam voice se hota hai wahi
 * type karke bhi ho" true: both input paths call the exact same handle() method below, so
 * there's only one code path to keep fast and correct, and both channels share conversation
 * context (ContextMemory) and show up together (ConversationBus).
 */
public class UltronCore {

    public interface ResultCallback {
        void onResult(String spokenResponse);
    }

    private static volatile UltronCore instance;

    private final ContextMemory contextMemory;
    private final HybridCommandRouter router;
    private final ActionExecutor executor;
    private final TTSManager ttsManager;

    private UltronCore(Context appContext) {
        this.contextMemory = new ContextMemory();
        this.router = new HybridCommandRouter(appContext, new SettingsManager(appContext), contextMemory);
        this.executor = new ActionExecutor(appContext);
        this.ttsManager = new TTSManager(appContext);
    }

    public static UltronCore get() {
        if (instance == null) {
            synchronized (UltronCore.class) {
                if (instance == null) {
                    instance = new UltronCore(UltronApplication.get().getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * Runs text through hybrid routing (rules first, LLM only on a miss - keeps this fast) then
     * ActionExecutor, posts both sides of the turn to ConversationBus, and optionally speaks the
     * reply. Every entry point (wake-word STT result, typed chat message, scheduled action) goes
     * through this one method so behaviour never diverges between input channels.
     */
    public void handle(String heardText, boolean speak, ResultCallback callback) {
        if (heardText == null || heardText.trim().isEmpty()) return;
        ConversationBus.post(ConversationBus.Speaker.USER, heardText);

        router.route(heardText, command -> executor.execute(command, response -> {
            contextMemory.record(heardText, response);
            ConversationBus.post(ConversationBus.Speaker.ULTRON, response);
            if (speak) {
                // PHASE 8: wait for TTS to actually finish before telling the caller the turn is
                // done. This matters now that UltronService can loop straight back into listening
                // after a turn (continuous mode) - firing the callback before Ultron finishes
                // talking would re-open the mic while it's still speaking and let it hear itself.
                ttsManager.speak(response, () -> {
                    if (callback != null) callback.onResult(response);
                });
            } else {
                if (callback != null) callback.onResult(response);
            }
        }));
    }

    public TTSManager getTtsManager() {
        return ttsManager;
    }
}
